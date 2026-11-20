package com.hycon.perf;

import com.hypixel.hytale.logger.HytaleLogger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Once a week (Monday 12:00 UTC) posts a short summary to the Discord webhook: average and lowest health, lag
 * spikes, peak players, highest memory and the plugin that used the most world-thread time. Built from the hourly
 * histories, so a restart in between loses nothing. {@code WeeklyReport: false} in hycon.json turns it off.
 * Profiler thread (the only file access is a timestamp in {@code weekly-report.txt}).
 */
final class WeeklyReport {

    private static final long WEEK_MS = TimeUnit.DAYS.toMillis(7);

    private final Path stamp;
    private final HytaleLogger log;
    private long lastSent = -1;

    WeeklyReport(Path dataDir, HytaleLogger log) {
        this.stamp = dataDir.resolve("weekly-report.txt");
        this.log = log;
    }

    /** Monday 12:00 UTC of this week. */
    static long dueThisWeek(long now) {
        ZonedDateTime t = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneOffset.UTC);
        ZonedDateTime monday = t.with(DayOfWeek.MONDAY).withHour(12).withMinute(0).withSecond(0).withNano(0);
        return monday.toInstant().toEpochMilli();
    }

    /** Called once a minute; sends when this week's report time has passed and it hasn't gone yet. */
    void minute(boolean enabled, HealthHistory health, PerfHistory plugins, java.util.function.Consumer<String> send) {
        long now = System.currentTimeMillis();
        long due = dueThisWeek(now);
        if (!enabled || now < due) {
            return;
        }
        if (lastSent < 0) {
            try {
                lastSent = Files.exists(stamp) ? Long.parseLong(Files.readString(stamp, StandardCharsets.UTF_8).trim()) : 0;
            } catch (Exception e) {
                lastSent = 0;
            }
        }
        if (lastSent >= due) {
            return;
        }
        List<HealthHistory.Hour> hours = health.since(now - WEEK_MS);
        lastSent = now;
        try {
            Files.writeString(stamp, String.valueOf(now), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.at(Level.FINE).log("Hycon weekly report stamp: %s", e);
        }
        if (hours.size() < 24) {
            return; // less than a day of history (a new install): nothing worth sending yet
        }
        send.accept(text(hours, plugins.since(now - WEEK_MS)));
    }

    static String text(List<HealthHistory.Hour> hours, List<PerfHistory.Hour> plugins) {
        double avg = hours.stream().mapToDouble(HealthHistory.Hour::health).average().orElse(0);
        HealthHistory.Hour worst = hours.stream().min(java.util.Comparator.comparingDouble(HealthHistory.Hour::health)).orElse(null);
        int spikes = hours.stream().mapToInt(HealthHistory.Hour::spikes).sum();
        int players = hours.stream().mapToInt(HealthHistory.Hour::playersMax).max().orElse(0);
        double mem = hours.stream().mapToDouble(HealthHistory.Hour::memPctMax).max().orElse(0);
        Map<String, double[]> share = new HashMap<>();
        for (PerfHistory.Hour h : plugins) {
            double[] v = share.computeIfAbsent(h.plugin(), k -> new double[2]);
            v[0] += h.share();
            v[1]++;
        }
        String top = share.entrySet().stream().max(java.util.Comparator.comparingDouble(e -> e.getValue()[0] / e.getValue()[1]))
                .map(e -> String.format("%s (%.1f%% of world-thread time)", e.getKey(), e.getValue()[0] / e.getValue()[1] * 100)).orElse("none");
        StringBuilder s = new StringBuilder("**Weekly server report**\n");
        s.append(String.format("Health: %.0f / 100 on average (%s)", avg, Report.healthWord((int) Math.round(avg))));
        if (worst != null) {
            s.append(String.format(", lowest %.0f on %s UTC", worst.health(),
                    java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:00").withZone(ZoneOffset.UTC)
                            .format(java.time.Instant.ofEpochMilli(worst.time()))));
        }
        s.append(String.format("\nLag spikes: %,d (%.1f a day)\nPeak players: %d\nHighest memory use: %.0f%%\nMost server time: %s",
                spikes, spikes / Math.max(1.0, hours.size() / 24.0), players, mem, top));
        return s.toString();
    }
}
