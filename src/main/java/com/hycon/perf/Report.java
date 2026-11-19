package com.hycon.perf;

import com.hycon.perf.PerfData.Late;
import com.hycon.perf.PerfData.Minute;
import com.hycon.perf.PerfData.SystemTime;
import com.hycon.perf.PerfData.TaskRun;
import com.hycon.perf.PerfData.Traffic;
import com.hycon.perf.PerfData.WorldMinute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minutes added up for one view (the panel's range, or /lag's last few minutes). Shares are 0..1. */
final class Report {

    /** 30 ticks a second: a tick over this is a late tick. */
    static final double TICK_BUDGET_MS = 1000.0 / 30;

    final int minutes;
    /** Share of all world-thread time each owner used, biggest first. */
    final Map<String, Double> worldShare;
    /** Share of the scheduler thread each owner used, biggest first. */
    final Map<String, Double> schedShare;
    /** Share of world-thread time spent in each {@code owner \t Class.method}, biggest first. */
    final Map<String, Double> where;
    /** Blocking file, network or database work on a game thread, in ms per minute, by owner. */
    final Map<String, Double> ioMsPerMin;
    /** Average ms per tick in ECS systems, by owner. */
    final Map<String, Double> systemMs;
    final List<SystemTime> slowSystems;
    final double busy, schedBusy, tickAvgMs, tickMaxMs, schedLateAvgMs, schedLateMaxMs, gcMsPerMin;
    final int spikes;
    final long heapMb, heapMaxMb;
    final Map<String, WorldMinute> worlds;
    /** Blocking IO by {@code owner 	 calling method}, in ms per minute, biggest first. */
    final Map<String, Double> ioWhereMsPerMin;
    /** World-thread time by {@code world 	 owner}, in ms per minute, biggest first. */
    final Map<String, Double> worldOwnersMsPerMin;
    /** Scheduler tasks over the range, longest single run first. */
    final List<TaskRun> tasks;
    /** The latest scheduler tasks, latest first. */
    final List<Late> lates;
    /** The last minute's traffic per player. */
    final List<Traffic> traffic;
    final long gcMaxPauseMs;

    private Report(List<Minute> list) {
        minutes = list.size();
        double worldMs = 0, busyMs = 0, schedMs = 0, schedBusyMs = 0, tickSum = 0, tickMax = 0, lateSum = 0, lateMax = 0;
        long gc = 0;
        int ticks = 0, spikeCount = 0;
        Map<String, Double> owners = new HashMap<>(), sched = new HashMap<>(), whereMs = new HashMap<>(), io = new HashMap<>(),
                sys = new HashMap<>();
        Map<String, WorldMinute> lastWorld = new LinkedHashMap<>();
        Map<String, Double> ioWhereMs = new HashMap<>(), worldOwnerMs = new HashMap<>();
        Map<String, TaskRun> taskMap = new HashMap<>();
        List<Late> lateAll = new ArrayList<>();
        long gcPause = 0;
        for (Minute m : list) {
            worldMs += m.worldMs();
            busyMs += m.worldBusyMs();
            schedMs += m.schedMs();
            schedBusyMs += m.schedBusyMs();
            m.owners().forEach((k, v) -> owners.merge(k, v, Double::sum));
            m.schedOwners().forEach((k, v) -> sched.merge(k, v, Double::sum));
            m.where().forEach((k, v) -> whereMs.merge(k, v, Double::sum));
            m.io().forEach((k, v) -> io.merge(k, v, Double::sum));
            m.systemMs().forEach((k, v) -> sys.merge(k, v, Double::sum));
            for (WorldMinute w : m.worlds()) {
                tickSum += w.tickAvgMs() * w.ticks();
                ticks += w.ticks();
                tickMax = Math.max(tickMax, w.tickMaxMs());
                lastWorld.put(w.name(), w);
            }
            lateSum += m.schedLateAvgMs();
            lateMax = Math.max(lateMax, m.schedLateMaxMs());
            gc += m.gcMs();
            spikeCount += m.spikes();
            if (m.ioWhere() != null) {
                m.ioWhere().forEach((k, v) -> ioWhereMs.merge(k, v, Double::sum));
                m.worldOwners().forEach((k, v) -> worldOwnerMs.merge(k, v, Double::sum));
                for (TaskRun t : m.tasks()) {
                    taskMap.merge(t.owner() + "	" + t.task(), t, (a, b) -> new TaskRun(a.owner(), a.task(),
                            Math.max(a.longestMs(), b.longestMs()), a.busyMs() + b.busyMs(), a.runs() + b.runs()));
                }
                lateAll.addAll(m.lates());
                gcPause = Math.max(gcPause, m.gcMaxPauseMs());
            }
        }
        int n = Math.max(1, minutes);
        worldShare = sorted(owners, worldMs);
        schedShare = sorted(sched, schedMs);
        where = sorted(whereMs, worldMs + schedMs);
        ioMsPerMin = sorted(io, n);
        systemMs = sorted(sys, n);
        busy = worldMs <= 0 ? 0 : busyMs / worldMs;
        schedBusy = schedMs <= 0 ? 0 : schedBusyMs / schedMs;
        tickAvgMs = ticks == 0 ? 0 : tickSum / ticks;
        tickMaxMs = tickMax;
        schedLateAvgMs = lateSum / n;
        schedLateMaxMs = lateMax;
        gcMsPerMin = gc / (double) n;
        spikes = spikeCount;
        Minute last = list.isEmpty() ? null : list.get(list.size() - 1);
        heapMb = last == null ? 0 : last.heapMb();
        heapMaxMb = last == null ? 0 : last.heapMaxMb();
        slowSystems = last == null ? List.of() : last.systems();
        worlds = lastWorld;
        ioWhereMsPerMin = sorted(ioWhereMs, n);
        worldOwnersMsPerMin = sorted(worldOwnerMs, n);
        List<TaskRun> t = new ArrayList<>(taskMap.values());
        t.sort(Comparator.comparingDouble(TaskRun::longestMs).reversed());
        tasks = t;
        lateAll.sort(Comparator.comparingDouble(Late::lateMs).reversed());
        lates = lateAll.subList(0, Math.min(25, lateAll.size()));
        traffic = last == null || last.traffic() == null ? List.of() : last.traffic();
        gcMaxPauseMs = gcPause;
    }

    static Report of(List<Minute> list) {
        return new Report(list);
    }

    /** One plain sentence on how the server is doing and what to look at first. */
    String verdict() {
        if (minutes == 0) {
            return "Still collecting: check back in a minute.";
        }
        String top = topPlugin();
        String health = tickAvgMs <= TICK_BUDGET_MS * 0.6 ? "Healthy" : tickAvgMs <= TICK_BUDGET_MS ? "Busy" : "Lagging";
        StringBuilder s = new StringBuilder(health).append(String.format(": ticks take %.1f ms on average (budget 33 ms)", tickAvgMs));
        if (spikes > 0) {
            s.append(", ").append(spikes).append(spikes == 1 ? " lag spike" : " lag spikes");
        }
        s.append('.');
        if (top != null) {
            s.append(String.format(" %s uses the most world-thread time (%.0f%%).", top, worldShare.get(top) * 100));
        }
        if (!ioMsPerMin.isEmpty()) {
            String io = ioMsPerMin.keySet().iterator().next();
            String from = ioWhereMsPerMin.keySet().stream().filter(k -> k.startsWith(io + "	")).findFirst()
                    .map(k -> ", mostly " + k.substring(io.length() + 1)).orElse("");
            s.append(String.format(" %s waits on files or a database on a game thread (%.0f ms a minute%s).", io, ioMsPerMin.get(io), from));
        }
        return s.toString();
    }

    /** The third-party plugin using the most world-thread time, or null. */
    String topPlugin() {
        return worldShare.keySet().stream().filter(Owners::isPlugin).findFirst().orElse(null);
    }

    private static Map<String, Double> sorted(Map<String, Double> m, double divide) {
        Map<String, Double> out = new LinkedHashMap<>();
        m.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> out.put(e.getKey(), divide <= 0 ? 0 : e.getValue() / divide));
        return out;
    }
}
