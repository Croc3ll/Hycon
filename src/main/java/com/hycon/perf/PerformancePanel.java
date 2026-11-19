package com.hycon.perf;

import com.hycon.api.HyconApi;
import com.hycon.api.HyconPanel;
import com.hycon.api.PanelData;
import com.hycon.perf.PerfData.Minute;
import com.hycon.perf.PerfData.Spike;
import com.hycon.perf.PerfData.SystemTime;
import com.hycon.perf.PerfData.WorldMinute;

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
        out.stat("Tick time", String.format("%.1f ms", r.tickAvgMs), "average, budget 33 ms")
                .stat("Worst tick", String.format("%.0f ms", r.tickMaxMs))
                .stat("Lag spikes", String.valueOf(r.spikes), "ticks over the spike limit")
                .stat("World threads busy", String.format("%.0f%%", r.busy * 100))
                .stat("Scheduler", String.format("%.0f%% busy", r.schedBusy * 100), String.format("tasks up to %.0f ms late", r.schedLateMaxMs))
                .stat("Memory", r.heapMb + " MB", String.format("of %d MB, GC %.0f ms/min", r.heapMaxMb, r.gcMsPerMin));

        shareBars(out, "Who uses the world threads", r.worldShare, r.busy);
        shareBars(out, "Who uses the shared scheduler thread", r.schedShare, r.schedBusy);
        charts(out, list, r);
        spikes(out);
        where(out, r);
        systems(out, r);
        io(out, r);
        worlds(out, r);
        versions(out, now);
        out.note(String.format("Sampling every %d ms. Shares are of all world-thread time, idle included. A plugin is blamed for the Hytale code it calls.",
                perf.profiler().sampleEveryMs()));
    }

    private static void shareBars(PanelData out, String title, Map<String, Double> shares, double busy) {
        PanelData.Bars bars = out.bars(title);
        shares.entrySet().stream().limit(12).forEach(e -> bars.bar(e.getKey(),
                busy <= 0 ? "" : String.format("%.0f%% of the busy time", e.getValue() / busy * 100),
                e.getValue(), String.format("%.1f%%", e.getValue() * 100)));
    }

    /** Tick time and the top plugins' shares over time, a point per few minutes. */
    private static void charts(PanelData out, List<Minute> list, Report r) {
        long bucket = list.size() > 360 ? TimeUnit.MINUTES.toMillis(10) : list.size() > 60 ? TimeUnit.MINUTES.toMillis(2) : 60_000;
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

    private void spikes(PanelData out) {
        PanelData.Table t = out.table("Lag spikes (newest first)", "When", "Where", "Length", "Cause", "Running", "GC");
        for (Spike s : perf.profiler().spikes()) {
            t.row(PanelData.time(s.time()), s.lane(), s.ms() + " ms", s.cause(), joinShares(s.where()), s.gcMs() + " ms");
        }
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
        PanelData.Table t = out.table("Waiting on files, network or a database on a game thread", "Plugin", "ms per minute", "Tip");
        r.ioMsPerMin.forEach((owner, ms) -> t.row(owner, String.format("%.0f", ms),
                "Move this work to its own thread; it stops the world while it waits."));
    }

    private static void worlds(PanelData out, Report r) {
        PanelData.Table t = out.table("Worlds (last minute)", "World", "Tick avg", "Tick worst", "Entities", "Chunks loaded");
        for (WorldMinute w : r.worlds.values()) {
            t.row(w.name(), String.format("%.1f ms", w.tickAvgMs()), String.format("%.0f ms", w.tickMaxMs()), w.entities(), w.chunks());
        }
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
