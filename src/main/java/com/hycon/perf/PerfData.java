package com.hycon.perf;

import java.util.List;
import java.util.Map;

/** What the profiler keeps: one record per minute, and one per lag spike. Times are in milliseconds. */
final class PerfData {

    private PerfData() {
    }

    /**
     * One minute of the server. {@code owners} is how long each plugin was running on the world
     * threads, out of {@code worldMs} watched (busy and idle); {@code sched*} is the same for Hytale's
     * shared scheduler thread. {@code where} is keyed {@code owner + "\t" + Class.method}.
     */
    record Minute(long time, double worldMs, double worldBusyMs, Map<String, Double> owners,
                  double schedMs, double schedBusyMs, Map<String, Double> schedOwners,
                  Map<String, Double> where, Map<String, Double> io,
                  List<WorldMinute> worlds, Map<String, Double> systemMs, List<SystemTime> systems,
                  double schedLateAvgMs, double schedLateMaxMs, long gcMs, long heapMb, long heapMaxMb,
                  int spikes, int sampleEveryMs) {
    }

    /** A world's ticks in one minute. A healthy world ticks in under 33 ms (30 a second). */
    record WorldMinute(String name, double tickAvgMs, double tickMaxMs, int ticks, int entities, int chunks) {
    }

    /** One of Hytale's ECS systems: its plugin and its average time per tick, as Hytale itself measures it. */
    record SystemTime(String name, String owner, String world, double msPerTick) {
    }

    /**
     * A tick (or scheduler task) that ran long. {@code owners} and {@code where} are shares (0..1)
     * of the samples taken while it ran; {@code gcMs} is garbage collection during it.
     */
    record Spike(long time, String lane, long ms, Map<String, Double> owners, Map<String, Double> where, long gcMs) {

        /** The plugin that ran most during the spike, or "garbage collection" when that took most of it. */
        String cause() {
            if (gcMs * 2 >= ms) {
                return "garbage collection";
            }
            return owners.isEmpty() ? "unknown" : owners.keySet().iterator().next();
        }
    }
}
