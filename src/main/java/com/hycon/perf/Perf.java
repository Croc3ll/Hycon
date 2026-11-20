package com.hycon.perf;

import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hycon.config.HyconConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import com.hycon.perf.PerfData.Minute;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The profiler with its history and alerts: what {@code HyconPlugin}, {@code /lag} and the
 * Performance tab use. Turned off with {@code "Profiler": false} in hycon.json.
 */
public final class Perf {

    private final Profiler profiler;
    private final PerfHistory history;
    private final HealthHistory health;

    public Perf(Path dataDir, HytaleLogger log, Supplier<HyconConfig> config) {
        history = new PerfHistory(dataDir);
        health = new HealthHistory(dataDir);
        LagAlerts alerts = new LagAlerts(log, config);
        WeeklyReport weekly = new WeeklyReport(dataDir, log);
        profiler = new Profiler(log, () -> config.get().lagSpikeMs(), m -> {
            history.add(m);
            alerts.minute(m);
            health.add(m, Report.of(List.of(m)).health(memPct(m)));
            // Alerts look at the last 10 minutes, so one bad minute doesn't page anyone.
            Report recent = recent(10);
            Minute last = m;
            alerts.health(recent.health(memPct(last)), recent.healthReasons);
            weekly.minute(config.get().weeklyReport(), health, history, alerts::webhook);
        }, alerts::spike);
        try {
            health.load();
        } catch (IOException e) {
            log.at(Level.WARNING).log("Hycon: could not read health-history.jsonl (%s); starting a new one", e.getMessage());
        }
        try {
            history.load();
        } catch (IOException e) {
            log.at(Level.WARNING).log("Hycon: could not read profiler-history.jsonl (%s); starting a new history", e.getMessage());
        }
    }

    /** Register with the entity store registry in setup, before worlds start ticking. */
    public TickingSystem<EntityStore> heartbeat() {
        return profiler.heartbeat();
    }

    public void start() {
        profiler.start();
    }

    public void stop() {
        profiler.stop();
    }

    Profiler profiler() {
        return profiler;
    }

    HealthHistory health() {
        return health;
    }

    static double memPct(Minute m) {
        return m.heapMaxMb() <= 0 ? 0 : m.heapMb() * 100.0 / m.heapMaxMb();
    }

    PerfHistory history() {
        return history;
    }

    /** The last {@code minutes} minutes, added up. */
    Report recent(int minutes) {
        return Report.of(profiler.minutes(System.currentTimeMillis() - minutes * 60_000L - 30_000));
    }

    /** A few lines for {@code /hycon debug}. */
    public String debugSummary() {
        Report r = recent(5);
        return "Profiler: " + (profiler.running() ? "running, sampling every " + profiler.sampleEveryMs() + " ms" : "STOPPED")
                + "\nProfiler (5 min): " + r.verdict() + "\n";
    }
}
