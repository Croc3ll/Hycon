package com.hycon.analytics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Players online and memory, sampled every {@link #INTERVAL_MS} and kept for {@link #KEEP_MS}.
 * Stored in {@code samples.log} as plain {@code time,online,memoryMb} lines (about 3 MB a month),
 * appended as they come and trimmed when the server starts. While the server is off nothing is
 * recorded, and those holes are what the charts show as outages.
 */
public final class Samples {

    public static final long INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);
    public static final long KEEP_MS = TimeUnit.DAYS.toMillis(31);

    /** One sample: time (epoch ms), players online, memory used in MB. */
    public record Sample(long time, int online, int memoryMb) {
    }

    private final Path file;
    private final List<Sample> samples = new ArrayList<>();
    private BufferedWriter out;

    public Samples(Path dataDir) {
        this.file = dataDir.resolve("samples.log");
    }

    /** Loads the kept samples, rewrites the file without older ones, and opens it for appending. */
    public synchronized void open() throws IOException {
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        int dropped = 0;
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split(",");
                try {
                    Sample s = new Sample(Long.parseLong(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    if (s.time() >= cutoff) {
                        samples.add(s);
                    } else {
                        dropped++;
                    }
                } catch (RuntimeException e) {
                    dropped++; // damaged line, e.g. cut off by a crash
                }
            }
        }
        Files.createDirectories(file.getParent());
        if (dropped > 0) {
            Path tmp = file.resolveSibling("samples.log.tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Sample s : samples) {
                    w.write(line(s));
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void close() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
            // shutting down
        }
        out = null;
    }

    public synchronized void add(int online, int memoryMb) {
        Sample s = new Sample(System.currentTimeMillis(), online, memoryMb);
        samples.add(s);
        long cutoff = s.time() - KEEP_MS;
        if (samples.get(0).time() < cutoff - TimeUnit.DAYS.toMillis(1)) {
            samples.removeIf(x -> x.time() < cutoff); // once a day at most, not every sample
        }
        if (out != null) {
            try {
                out.write(line(s));
                out.flush();
            } catch (IOException ignored) {
                // the in-memory copy still serves the charts
            }
        }
    }

    /** Samples at or after {@code since}, oldest first. */
    public synchronized List<Sample> since(long since) {
        int lo = 0, hi = samples.size();
        while (lo < hi) { // binary search: samples are in time order
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).time() < since) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return new ArrayList<>(samples.subList(lo, samples.size()));
    }

    private static String line(Sample s) {
        return s.time() + "," + s.online() + "," + s.memoryMb() + "\n";
    }
}
