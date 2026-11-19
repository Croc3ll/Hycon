package com.hycon.perf;

import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hycon.config.HyconConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The profiler with its history and alerts: what {@code HyconPlugin}, {@code /lag} and the
 * Performance tab use. Turned off with {@code "Profiler": false} in hycon.json.
 */
public final class Perf {

    private final Profiler profiler;
    private final PerfHistory history;

    public Perf(Path dataDir, HytaleLogger log, Supplier<HyconConfig> config) {
        history = new PerfHistory(dataDir);
        LagAlerts alerts = new LagAlerts(log, config);
        profiler = new Profiler(log, () -> config.get().lagSpikeMs(), m -> {
            history.add(m);
            alerts.minute(m);
        }, alerts::spike);
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
