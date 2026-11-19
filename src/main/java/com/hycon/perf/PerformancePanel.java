package com.hycon.perf;

import com.hycon.api.HyconApi;
import com.hycon.api.HyconPanel;
import com.hycon.api.PanelData;
import com.hycon.perf.PerfData.Late;
import com.hycon.perf.PerfData.Minute;
import com.hycon.perf.PerfData.Spike;
import com.hycon.perf.PerfData.SystemTime;
import com.hycon.perf.PerfData.TaskRun;
import com.hycon.perf.PerfData.Traffic;
import com.hycon.perf.PerfData.WorldMinute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The "Performance" tab: a plain verdict, which plugins use the world threads, tick times over
 * time, lag spikes with their cause, the slowest ECS systems, blocking file or database work, and
 * how each plugin changed between versions. Detail covers the last 24 hours; history goes back 30 days.
 */
public final class PerformancePanel implements HyconPanel {

    private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);
    /** Plugins drawn as their own line in the share chart. */
    private static final int CHART_PLUGINS = 5;

    private final Perf perf;

    public PerformancePanel(Perf perf) {
        this.perf = perf;
    }

    @Override
    public String id() {
        return "performance";
    }

    @Override
    public String title() {
        return "Performance";
    }

    @Override
    public int minLevel() {
        return HyconApi.MODERATOR;
    }

    @Override
    public void render(PanelData out) {
        long now = System.currentTimeMillis();
        long range = Math.min(out.rangeMillis(), DAY_MS);
        List<Minute> list = perf.profiler().minutes(now - range);
        Report r = Report.of(list);

        out.note(r.verdict() + (out.rangeMillis() > DAY_MS ? " Detail covers the last 24 hours; the version table goes back 30 days." : ""));
        double memPct = r.heapMaxMb <= 0 ? 0 : r.heapMb * 100.0 / r.heapMaxMb;
        out.stat("Tick time", String.format("%.1f ms", r.tickAvgMs), grade(r.tickAvgMs, 20, 33)
                        + ": how long the server needs for one game step, on average. Under 20 ms is good; over 33 ms the game slows down for everyone.")
                .stat("Worst tick", String.format("%.0f ms", r.tickMaxMs), grade(r.tickMaxMs, 100, 500)
                        + ": the single slowest step. Under 100 ms nobody notices; over 500 ms players see a freeze or rubber-banding.")
                .stat("Lag spikes", String.valueOf(r.spikes), grade(r.minutes == 0 ? 0 : r.spikes / (double) r.minutes, 0.2, 2)
                        + ": steps slower than the spike limit. A few a day is normal (saves, players joining); several a minute is a problem.")
                .stat("World threads busy", String.format("%.0f%%", r.busy * 100), grade(r.busy * 100, 50, 80)
                        + ": how much of the time the worlds are working instead of waiting. Under 50% leaves room for more players; over 80% means little headroom.")
                .stat("Scheduler", String.format("%.0f%% busy", r.schedBusy * 100), grade(Math.max(r.schedBusy * 100, r.schedLateMaxMs / 5), 10, 50)
                        + String.format(": the one helper thread all plugins share. Tasks ran up to %.0f ms late. Under 10%% busy and under 50 ms late is good; slow tasks here delay every plugin.", r.schedLateMaxMs))
                .stat("Memory", r.heapMb + " MB", grade(memPct, 70, 90)
                        + String.format(": %.0f%% of the %d MB the server may use. Under 70%% is good; staying over 90%% risks freezes and crashes. "
                        + "Cleaning up (GC) took %.0f ms a minute, longest single pause %d ms (under 100 ms is fine).", memPct, r.heapMaxMb,
                        r.gcMsPerMin, r.gcMaxPauseMs));
        out.note("How to read this page: each number above says Good, Watch or Bad with what it means. Below, the bars and tables show "
                + "which plugin or task caused it, so you know who to ask or what to remove. \"ms\" is a thousandth of a second; "
                + "the server does 30 game steps (ticks) a second, so each has 33 ms.");

        out.note("Who uses the time: each plugin's share of the world threads (where the game runs) and of the shared scheduler. "
                + "\"Hytale\" is the game itself. A plugin that alone takes more than about 10% is worth a closer look.");
        shareBars(out, "Who uses the world threads", r.worldShare, r.busy);
        shareBars(out, "Who uses the shared scheduler thread", r.schedShare, r.schedBusy);
        charts(out, list, r);
        history(out, list);
        spikes(out);
        scheduler(out, r);
        where(out, r);
        drillDown(out, r);
        systems(out, r);
        io(out, r);
        worlds(out, r);
        worldSplit(out, r);
        traffic(out, r);
        versions(out, now);
        out.note(String.format("Sampling every %d ms. Shares are of all world-thread time, idle included. A plugin is blamed for the Hytale code it calls.",
                perf.profiler().sampleEveryMs()));
    }

    /** "Good", "Watch" or "Bad" for a value where lower is better. */
    private static String grade(double value, double good, double bad) {
        return value <= good ? "Good" : value <= bad ? "Watch" : "Bad";
    }

    private static void shareBars(PanelData out, String title, Map<String, Double> shares, double busy) {
        PanelData.Bars bars = out.bars(title);
        shares.entrySet().stream().limit(12).forEach(e -> bars.bar(e.getKey(),
                busy <= 0 ? "" : String.format("%.0f%% of the busy time", e.getValue() / busy * 100),
                e.getValue(), String.format("%.1f%%", e.getValue() * 100)));
    }

    /** Tick time and the top plugins' shares over time, a point per few minutes. */
    private static void charts(PanelData out, List<Minute> list, Report r) {
        long bucket = bucket(list);
        PanelData.Series ticks = out.series("Tick time", "ms").subtitle("average and worst per " + bucket / 60_000 + " min");
        PanelData.Series shares = out.series("World-thread share by plugin", "%").subtitle("top " + CHART_PLUGINS);
        List<String> top = r.worldShare.keySet().stream().limit(CHART_PLUGINS).toList();

        Map<Long, double[]> tickByBucket = new LinkedHashMap<>(); // sum, ticks, max
        Map<Long, Map<String, Double>> ownerByBucket = new LinkedHashMap<>();
        Map<Long, Double> worldByBucket = new HashMap<>();
        for (Minute m : list) {
            long b = m.time() / bucket * bucket + bucket / 2;
            double[] t = tickByBucket.computeIfAbsent(b, k -> new double[3]);
            for (WorldMinute w : m.worlds()) {
                t[0] += w.tickAvgMs() * w.ticks();
                t[1] += w.ticks();
                t[2] = Math.max(t[2], w.tickMaxMs());
            }
            Map<String, Double> o = ownerByBucket.computeIfAbsent(b, k -> new HashMap<>());
            m.owners().forEach((k, v) -> o.merge(k, v, Double::sum));
            worldByBucket.merge(b, m.worldMs(), Double::sum);
        }
        ticks.line("Average");
        tickByBucket.forEach((b, t) -> ticks.point(b, t[1] == 0 ? 0 : t[0] / t[1]));
        ticks.line("Worst");
        tickByBucket.forEach((b, t) -> ticks.point(b, t[2]));
        for (String owner : top) {
            shares.line(owner);
            ownerByBucket.forEach((b, o) -> {
                double total = worldByBucket.getOrDefault(b, 0.0);
                shares.point(b, total <= 0 ? 0 : o.getOrDefault(owner, 0.0) / total * 100);
            });
        }
    }

    /** Points per bucket for the history charts: a minute for an hour, 2 minutes up to 6 hours, 10 for a day. */
    private static long bucket(List<Minute> list) {
        return list.size() > 360 ? TimeUnit.MINUTES.toMillis(10) : list.size() > 60 ? TimeUnit.MINUTES.toMillis(2) : 60_000;
    }

    /** Spikes, memory, players, and each world's chunks, entities and NPCs over the panel's range. */
    private static void history(PanelData out, List<Minute> list) {
        long bucket = bucket(list);
        String per = "per " + bucket / 60_000 + " min";
        Map<Long, double[]> agg = new LinkedHashMap<>(); // spikes, heap max, heap limit, players max
        Map<String, Map<Long, int[]>> perWorld = new LinkedHashMap<>(); // chunks, entities, npcs (last in bucket)
        for (Minute m : list) {
            long b = m.time() / bucket * bucket + bucket / 2;
            double[] a = agg.computeIfAbsent(b, k -> new double[4]);
            a[0] += m.spikes();
            a[1] = Math.max(a[1], m.heapMb());
            a[2] = Math.max(a[2], m.heapMaxMb());
            a[3] = Math.max(a[3], m.players());
            for (WorldMinute w : m.worlds()) {
                perWorld.computeIfAbsent(w.name(), k -> new LinkedHashMap<>()).put(b, new int[]{w.chunks(), w.entities(), w.npcs()});
            }
        }
        out.note("History: how the server changed over the chosen time. Look for the moment things went up: a jump in spikes "
                + "with a jump in players or chunks means load; memory that only climbs and never drops can mean a leak.");
        PanelData.Series spikes = out.series("Lag spikes", "spikes").subtitle(per);
        spikes.line("Spikes");
        agg.forEach((b, a) -> spikes.point(b, a[0]));
        PanelData.Series mem = out.series("Memory", "MB").subtitle("highest in use " + per);
        mem.line("In use");
        agg.forEach((b, a) -> mem.point(b, a[1]));
        mem.line("Maximum");
        agg.forEach((b, a) -> mem.point(b, a[2]));
        PanelData.Series players = out.series("Players online", "players").subtitle("most " + per);
        players.line("Players");
        agg.forEach((b, a) -> players.point(b, a[3]));
        PanelData.Series chunks = out.series("Loaded chunks by world", "chunks");
        PanelData.Series entities = out.series("Entities by world", "entities");
        PanelData.Series npcs = out.series("NPCs by world", "NPCs");
        boolean anyNpcs = false;
        for (Map.Entry<String, Map<Long, int[]>> w : perWorld.entrySet()) {
            chunks.line(w.getKey());
            w.getValue().forEach((b, v) -> chunks.point(b, v[0]));
            entities.line(w.getKey());
            w.getValue().forEach((b, v) -> entities.point(b, v[1]));
            if (w.getValue().values().stream().anyMatch(v -> v[2] >= 0)) {
                anyNpcs = true;
                npcs.line(w.getKey());
                w.getValue().forEach((b, v) -> npcs.point(b, Math.max(0, v[2])));
            }
        }
        if (!anyNpcs) {
            npcs.subtitle("NPC counts arrive a minute after start");
        }
    }

    private void spikes(PanelData out) {
        out.note("Lag spikes: each slow game step. \"Cause\" is the plugin that ran most during it; \"System or task\" the part of it; "
                + "\"Also\" shows chunk loading, world generation or saving, which are normal in small amounts. GC is memory clean-up.");
        PanelData.Table t = out.table("Lag spikes (newest first)", "When", "Where", "Length", "Cause", "System or task", "Also",
                "Running", "GC");
        for (Spike s : perf.profiler().spikes()) {
            t.row(PanelData.time(s.time()), s.lane(), s.ms() + " ms", s.cause(), s.system() == null ? "" : s.system(),
                    s.phases() == null ? "" : String.join(", ", s.phases()), joinShares(s.where()), s.gcMs() + " ms");
        }
    }

    /** The tasks behind "tasks up to N ms late", and the slowest scheduler tasks. */
    private static void scheduler(PanelData out, Report r) {
        out.note("Scheduler: every plugin shares one helper thread for timers. One slow task makes all the others wait. "
                + "A task late by under 50 ms is fine; one that runs 100 ms or more should move its work to its own thread (tell its author).");
        if (!r.lates.isEmpty()) {
            PanelData.Table t = out.table("Late scheduler tasks (worst first)", "When", "Late by", "Held up by");
            for (Late l : r.lates) {
                t.row(PanelData.time(l.time()), String.format("%.0f ms", l.lateMs()), l.blame());
            }
        }
        PanelData.Table t = out.table("Slowest scheduler tasks", "Task", "Plugin", "Longest run", "Busy ms a minute", "Runs seen");
        int n = Math.max(1, r.minutes);
        r.tasks.stream().limit(15).forEach(k -> t.row(k.task(), k.owner(), String.format("%.0f ms", k.longestMs()),
                String.format("%.1f", k.busyMs() / n), k.runs()));
        out.note("Scheduler task lengths come from samples, so short tasks are missed and lengths are accurate to about one sample.");
    }

    /** Each plugin's top methods and its ECS systems, side by side. */
    private static void drillDown(PanelData out, Report r) {
        Map<String, List<String>> methods = new LinkedHashMap<>();
        r.where.forEach((k, v) -> {
            String[] parts = k.split("\t", 2);
            List<String> l = methods.computeIfAbsent(parts[0], x -> new ArrayList<>());
            if (l.size() < 3 && parts.length > 1) {
                l.add(parts[1] + String.format(" %.1f%%", v * 100));
            }
        });
        Map<String, List<String>> systems = new HashMap<>();
        for (SystemTime st : r.slowSystems) {
            List<String> l = systems.computeIfAbsent(st.owner(), x -> new ArrayList<>());
            if (l.size() < 3) {
                l.add(st.name() + String.format(" %.2f ms", st.msPerTick()));
            }
        }
        Map<String, List<String>> tasks = new HashMap<>();
        for (TaskRun k : r.tasks) {
            List<String> l = tasks.computeIfAbsent(k.owner(), x -> new ArrayList<>());
            if (l.size() < 3) {
                l.add(k.task() + String.format(" %.0f ms", k.longestMs()));
            }
        }
        out.note("Plugin drill-down: for each plugin, where its time goes. Useful to send to a plugin's author when it is slow.");
        PanelData.Table t = out.table("Plugin drill-down", "Plugin", "World share", "Top methods", "Top ECS systems", "Top scheduler tasks");
        r.worldShare.keySet().stream().limit(12).forEach(owner -> t.row(owner, String.format("%.1f%%", r.worldShare.get(owner) * 100),
                String.join(", ", methods.getOrDefault(owner, List.of())), String.join(", ", systems.getOrDefault(owner, List.of())),
                String.join(", ", tasks.getOrDefault(owner, List.of()))));
    }

    private static void where(PanelData out, Report r) {
        PanelData.Table t = out.table("Where the time goes", "Plugin", "Method", "Share");
        r.where.entrySet().stream().limit(25).forEach(e -> {
            String[] parts = e.getKey().split("\t", 2);
            t.row(parts[0], parts.length > 1 ? parts[1] : "", String.format("%.1f%%", e.getValue() * 100));
        });
    }

    private static void systems(PanelData out, Report r) {
        PanelData.Bars bars = out.bars("ECS system time per tick, by plugin");
        double max = r.systemMs.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        r.systemMs.entrySet().stream().limit(12).forEach(e ->
                bars.bar(e.getKey(), "", max <= 0 ? 0 : e.getValue(), String.format("%.2f ms", e.getValue())));
        PanelData.Table t = out.table("Slowest ECS systems (last minute)", "System", "Plugin", "World", "ms per tick");
        for (SystemTime s : r.slowSystems) {
            t.row(s.name(), s.owner(), s.world(), String.format("%.2f", s.msPerTick()));
        }
    }

    private static void io(PanelData out, Report r) {
        if (r.ioMsPerMin.isEmpty()) {
            return;
        }
        out.note("Waiting on files or a database: while this happens the world stands still. Any amount here is worth fixing; "
                + "over 50 ms a minute players can feel it.");
        PanelData.Table t = out.table("Waiting on files, network or a database on a game thread", "Plugin", "Called from", "ms per minute");
        r.ioWhereMsPerMin.entrySet().stream().limit(20).forEach(e -> {
            String[] parts = e.getKey().split("\t", 2);
            t.row(parts[0], parts.length > 1 ? parts[1] : "", String.format("%.0f", e.getValue()));
        });
        out.note("\"Called from\" is the method that started the wait; the file name itself isn't visible to a sampler. "
                + "Move this work to its own thread: the world stops while it waits.");
    }

    private static void worlds(PanelData out, Report r) {
        PanelData.Table t = out.table("Worlds (last minute)", "World", "Tick avg", "Tick worst", "Entities", "NPCs", "Chunks loaded");
        for (WorldMinute w : r.worlds.values()) {
            t.row(w.name(), String.format("%.1f ms", w.tickAvgMs()), String.format("%.0f ms", w.tickMaxMs()), w.entities(),
                    w.npcs() < 0 ? "" : w.npcs(), w.chunks());
        }
    }

    /** Busy world-thread time per world, and who uses it. */
    private static void worldSplit(PanelData out, Report r) {
        Map<String, Double> busy = new LinkedHashMap<>();
        Map<String, List<String>> top = new HashMap<>();
        r.worldOwnersMsPerMin.forEach((k, v) -> {
            String[] parts = k.split("\t", 2);
            busy.merge(parts[0], v, Double::sum);
            List<String> l = top.computeIfAbsent(parts[0], x -> new ArrayList<>());
            if (l.size() < 3 && parts.length > 1) {
                l.add(parts[1] + String.format(" %.0f ms", v));
            }
        });
        if (busy.isEmpty()) {
            return;
        }
        PanelData.Bars bars = out.bars("World-thread time by world (busy ms a minute)");
        double max = busy.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        busy.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).forEach(e ->
                bars.bar(e.getKey(), String.join(", ", top.getOrDefault(e.getKey(), List.of())), max <= 0 ? 0 : e.getValue() / max,
                        String.format("%.0f ms (%.1f%%)", e.getValue(), e.getValue() / 600)));
    }

    /** Each player's network use in the last minute. */
    private static void traffic(PanelData out, Report r) {
        if (r.traffic.isEmpty()) {
            return;
        }
        out.note("Network: data sent to and from each player. Up to about 50 KB/s each is normal; far more for one player can mean "
                + "a busy area (many entities) or a plugin sending too much.");
        PanelData.Table t = out.table("Network traffic per player (last minute)", "Player", "Sent to them", "Received", "Sent per second");
        for (Traffic tr : r.traffic) {
            t.row(tr.player(), kb(tr.sent()), kb(tr.received()), kb(tr.sent() / 60) + "/s");
        }
    }

    private static String kb(long bytes) {
        return bytes >= 10L << 20 ? String.format("%.1f MB", bytes / 1048576.0) : String.format("%.1f KB", bytes / 1024.0);
    }

    /** Each plugin's average share on its current version against the version before, from the hourly history. */
    private void versions(PanelData out, long now) {
        Map<String, String> current = Owners.versions();
        Map<String, double[]> nowShare = new HashMap<>(), before = new HashMap<>(); // sum, hours
        Map<String, String> beforeVersion = new HashMap<>();
        for (PerfHistory.Hour h : perf.history().since(now - TimeUnit.DAYS.toMillis(30))) {
            String version = current.get(h.plugin());
            if (version == null) {
                continue;
            }
            if (version.equals(h.version())) {
                add(nowShare, h);
            } else {
                add(before, h);
                beforeVersion.put(h.plugin(), h.version()); // hours are oldest first, so this ends on the latest older version
            }
        }
        if (nowShare.isEmpty()) {
            out.note("Version history fills in after the first full hour.");
            return;
        }
        PanelData.Table t = out.table("Plugins by version (hourly averages, 30 days)", "Plugin", "Version", "Share now", "Before", "Change");
        nowShare.entrySet().stream().sorted((a, b) -> Double.compare(avg(b.getValue()), avg(a.getValue()))).forEach(e -> {
            String plugin = e.getKey();
            double[] old = before.get(plugin);
            String change = old == null ? "" : String.format("%+.1f points", (avg(e.getValue()) - avg(old)) * 100);
            t.row(plugin, current.get(plugin), String.format("%.1f%%", avg(e.getValue()) * 100),
                    old == null ? "" : String.format("%.1f%% (%s)", avg(old) * 100, beforeVersion.get(plugin)), change);
        });
    }

    private static void add(Map<String, double[]> m, PerfHistory.Hour h) {
        double[] v = m.computeIfAbsent(h.plugin(), k -> new double[2]);
        v[0] += h.share();
        v[1]++;
    }

    private static double avg(double[] v) {
        return v[1] == 0 ? 0 : v[0] / v[1];
    }

    private static String joinShares(Map<String, Double> shares) {
        StringBuilder s = new StringBuilder();
        shares.entrySet().stream().limit(3).forEach(e -> s.append(s.isEmpty() ? "" : ", ")
                .append(e.getKey().replace('\t', ' ')).append(String.format(" %.0f%%", e.getValue() * 100)));
        return s.toString();
    }
}
