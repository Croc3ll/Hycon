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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Each plugin's share of the world threads, one line per plugin per hour with its version, in
 * {@code profiler-history.jsonl}. Kept 30 days, so the panel can show how a plugin changed after
 * an update. Only the profiler thread adds; readers copy under the lock.
 */
final class PerfHistory {

    private static final long HOUR_MS = TimeUnit.HOURS.toMillis(1);
    private static final long KEEP_MS = TimeUnit.DAYS.toMillis(30);

    record Hour(long time, String plugin, String version, double share, double systemMs) {
    }

    private final Path file;
    private final List<Hour> hours = new ArrayList<>();
    private long hourStart = -1;
    private double worldMs;
    private int minutes;
    private final Map<String, Double> ownerMs = new HashMap<>(), sysMs = new HashMap<>();

    PerfHistory(Path dataDir) {
        file = dataDir.resolve("profiler-history.jsonl");
    }

    void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        List<Hour> read = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            try {
                Map<String, Object> m = JsonReader.object(line);
                Hour h = new Hour(((Double) m.get("t")).longValue(), (String) m.get("p"), (String) m.get("v"),
                        (Double) m.get("s"), (Double) m.get("ms"));
                if (h.time() >= cutoff) {
                    read.add(h);
                }
            } catch (RuntimeException e) {
                // A half-written last line after a crash: skip it, the rest is fine.
            }
        }
        synchronized (hours) {
            hours.addAll(read);
        }
        if (read.size() < Files.readAllLines(file, StandardCharsets.UTF_8).size()) {
            rewrite(read);
        }
    }

    /** Adds a minute; when an hour is complete it is written out. */
    void add(Minute m) {
        long hour = m.time() / HOUR_MS * HOUR_MS;
        if (hourStart >= 0 && hour != hourStart) {
            flush();
        }
        hourStart = hour;
        worldMs += m.worldMs();
        minutes++;
        m.owners().forEach((k, v) -> ownerMs.merge(k, v, Double::sum));
        m.systemMs().forEach((k, v) -> sysMs.merge(k, v, Double::sum));
    }

    private void flush() {
        if (worldMs <= 0 || minutes == 0) {
            return;
        }
        Map<String, String> versions = Owners.versions();
        List<Hour> done = new ArrayList<>();
        StringBuilder lines = new StringBuilder();
        for (String plugin : versions.keySet()) {
            if (!Owners.isPlugin(plugin)) {
                continue;
            }
            Hour h = new Hour(hourStart, plugin, versions.get(plugin), ownerMs.getOrDefault(plugin, 0.0) / worldMs,
                    sysMs.getOrDefault(plugin, 0.0) / minutes);
            done.add(h);
            lines.append(new Json().beginObject().field("t", h.time()).field("p", h.plugin()).field("v", h.version())
                    .field("s", h.share()).field("ms", h.systemMs()).endObject()).append('\n');
        }
        worldMs = 0;
        minutes = 0;
        ownerMs.clear();
        sysMs.clear();
        synchronized (hours) {
            hours.addAll(done);
        }
        try {
            Files.writeString(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // History is a nice-to-have: the live view still works, so this only loses the hour on disk.
        }
    }

    /** Hours since {@code sinceMillis}, oldest first. */
    List<Hour> since(long sinceMillis) {
        synchronized (hours) {
            return hours.stream().filter(h -> h.time() >= sinceMillis).toList();
        }
    }

    private void rewrite(List<Hour> keep) throws IOException {
        StringBuilder out = new StringBuilder();
        for (Hour h : keep) {
            out.append(new Json().beginObject().field("t", h.time()).field("p", h.plugin()).field("v", h.version())
                    .field("s", h.share()).field("ms", h.systemMs()).endObject()).append('\n');
        }
        Files.writeString(file, out, StandardCharsets.UTF_8);
    }
}
