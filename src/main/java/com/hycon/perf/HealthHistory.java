package com.hycon.perf;

import com.hycon.perf.PerfData.Minute;
import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The server's hourly health in {@code health-history.jsonl}, kept 30 days: the average health score, lag spikes,
 * most players online and highest memory use of each hour. For the week-long health chart and the weekly report.
 * Only the profiler thread adds; readers copy under the lock.
 */
final class HealthHistory {

    private static final long HOUR_MS = TimeUnit.HOURS.toMillis(1);
    private static final long KEEP_MS = TimeUnit.DAYS.toMillis(30);

    record Hour(long time, double health, int spikes, int playersMax, double memPctMax) {
    }

    private final Path file;
    private final List<Hour> hours = new ArrayList<>();
    private long hourStart = -1;
    private double healthSum;
    private int minutes, spikes, playersMax;
    private double memMax;

    HealthHistory(Path dataDir) {
        file = dataDir.resolve("health-history.jsonl");
    }

    void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        List<Hour> read = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            try {
                Map<String, Object> m = JsonReader.object(line);
                Hour h = new Hour(((Double) m.get("t")).longValue(), (Double) m.get("h"), ((Double) m.get("s")).intValue(),
                        ((Double) m.get("p")).intValue(), (Double) m.get("m"));
                if (h.time() >= cutoff) {
                    read.add(h);
                }
            } catch (RuntimeException e) {
                // A half-written last line after a crash: skip it.
            }
        }
        synchronized (hours) {
            hours.addAll(read);
        }
        if (read.size() < lines.size()) {
            StringBuilder out = new StringBuilder();
            read.forEach(h -> out.append(json(h)).append('\n'));
            Files.writeString(file, out, StandardCharsets.UTF_8);
        }
    }

    /** Adds a finished minute with its health score; a finished hour is written out. Profiler thread. */
    void add(Minute m, int health) {
        long hour = m.time() / HOUR_MS * HOUR_MS;
        if (hourStart >= 0 && hour != hourStart) {
            flush();
        }
        hourStart = hour;
        if (health >= 0) {
            healthSum += health;
            minutes++;
        }
        spikes += m.spikes();
        playersMax = Math.max(playersMax, m.players());
        memMax = Math.max(memMax, m.heapMaxMb() <= 0 ? 0 : m.heapMb() * 100.0 / m.heapMaxMb());
    }

    private void flush() {
        if (minutes == 0) {
            return;
        }
        Hour h = new Hour(hourStart, healthSum / minutes, spikes, playersMax, memMax);
        healthSum = 0;
        minutes = spikes = playersMax = 0;
        memMax = 0;
        synchronized (hours) {
            hours.add(h);
            hours.removeIf(x -> x.time() < System.currentTimeMillis() - KEEP_MS);
        }
        try {
            Files.writeString(file, json(h) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // The hour stays in memory; only the file misses it.
        }
    }

    /** Hours since {@code sinceMillis}, oldest first. */
    List<Hour> since(long sinceMillis) {
        synchronized (hours) {
            return hours.stream().filter(h -> h.time() >= sinceMillis).toList();
        }
    }

    private static String json(Hour h) {
        return new Json().beginObject().field("t", h.time()).field("h", h.health()).field("s", h.spikes())
                .field("p", h.playersMax()).field("m", h.memPctMax()).endObject().toString();
    }
}
