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
                  int spikes, int sampleEveryMs,
                  Map<String, Double> ioWhere, Map<String, Double> worldOwners, List<TaskRun> tasks,
                  List<Late> lates, List<Traffic> traffic, int players, long gcMaxPauseMs) {
    }

    /** A world's ticks in one minute. A healthy world ticks in under 33 ms (30 a second). {@code npcs} is -1 when unknown. */
    record WorldMinute(String name, double tickAvgMs, double tickMaxMs, int ticks, int entities, int chunks, int npcs) {
    }

    /**
     * One scheduler task in a minute, told apart by its run method. Lengths come from samples, so
     * {@code longestMs} is accurate to about one sample interval; {@code busyMs} is the sampled total.
     */
    record TaskRun(String owner, String task, double longestMs, double busyMs, int runs) {
    }

    /** The scheduler started a task {@code lateMs} late; {@code blame} is the task it was last seen running before. */
    record Late(long time, double lateMs, String blame) {
    }

    /** One player's network use in a minute, in bytes as sent on the wire (compressed). */
    record Traffic(String player, long sent, long received) {
    }

    /** One of Hytale's ECS systems: its plugin and its average time per tick, as Hytale itself measures it. */
    record SystemTime(String name, String owner, String world, double msPerTick) {
    }

    /**
     * A tick (or scheduler task) that ran long. {@code owners} and {@code where} are shares (0..1)
     * of the samples taken while it ran; {@code gcMs} is garbage collection during it. {@code system} is the
     * ECS system (or scheduler task) seen most, {@code phases} what else showed up (chunk load, save, world gen).
     */
    record Spike(long time, String lane, long ms, Map<String, Double> owners, Map<String, Double> where, long gcMs,
                 String system, List<String> phases) {

        /** The plugin that ran most during the spike, or "garbage collection" when that took most of it. */
        String cause() {
            if (gcMs * 2 >= ms) {
                return "garbage collection";
            }
            return owners.isEmpty() ? "unknown" : owners.keySet().iterator().next();
        }
    }
}
