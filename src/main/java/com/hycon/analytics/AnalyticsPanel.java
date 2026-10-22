package com.hycon.analytics;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hycon.api.HyconPanel;
import com.hycon.api.PanelData;
import com.hycon.game.KnownPlayers;
import com.hycon.game.PositionLog;
import com.hypixel.hytale.server.core.universe.world.World;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Hycon's own "Analytics" tab: players online over time with outages, unique players, peak,
 * average, uptime, players by hour of day, memory, and the most active players. Built through the
 * same {@link HyconPanel} API other plugins use.
 */
public final class AnalyticsPanel implements HyconPanel {

    private final Samples samples;
    private final KnownPlayers known;
    private final PositionLog positions;

    public AnalyticsPanel(Samples samples, KnownPlayers known, PositionLog positions) {
        this.samples = samples;
        this.known = known;
        this.positions = positions;
    }

    @Override
    public String id() {
        return "analytics";
    }

    @Override
    public String title() {
        return "Analytics";
    }

    @Override
    public void render(PanelData out) {
        long now = System.currentTimeMillis();
        long range = out.rangeMillis();
        long since = now - range;
        List<Samples.Sample> list = samples.since(since);

        int peak = 0;
        long sum = 0;
        for (Samples.Sample s : list) {
            peak = Math.max(peak, s.online());
            sum += s.online();
        }
        int online = Universe.get().getPlayers().size();
        Set<UUID> onlineIds = new HashSet<>();
        for (PlayerRef p : Universe.get().getPlayers()) {
            onlineIds.add(p.getUuid());
        }
        int unique = 0;
        for (KnownPlayers.Info i : known.all()) {
            if (i.lastSeen() >= since || onlineIds.contains(i.uuid())) {
                unique++;
            }
        }
        // Uptime: share of the range (since the first sample, if later) that has samples.
        long covered = list.isEmpty() ? 0 : Math.min(range, now - list.get(0).time());
        double uptime = covered <= 0 ? 100 : Math.min(100, list.size() * Samples.INTERVAL_MS * 100.0 / covered);
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;

        out.stat("Online now", String.valueOf(online))
                .stat("Unique players", String.valueOf(unique))
                .stat("Peak", String.valueOf(Math.max(peak, online)))
                .stat("Average", list.isEmpty() ? "-" : String.format("%.1f", sum / (double) list.size()))
                .stat("Uptime", String.format("%.1f%%", uptime), "running " + duration((System.nanoTime() - HytaleServer.get().getBootStart()) / 1_000_000)) // boot time is System.nanoTime()
                .stat("Memory", usedMb + " MB", "of " + (rt.maxMemory() >> 20) + " MB");

        retention(out, now, since, onlineIds);

        long bucket = bucketFor(range);
        PanelData.Series players = out.series("Players online", "players").area()
                .subtitle("average per " + duration(bucket) + " · red bands are outages").line("Players");
        PanelData.Series memory = out.series("Memory used", "MB").subtitle("average per " + duration(bucket)).line("Memory");
        long start = -1, last = -1, n = 0, sumOn = 0, sumMem = 0;
        for (Samples.Sample s : list) {
            if (last >= 0 && s.time() - last > 3 * Samples.INTERVAL_MS) {
                players.gap(last, s.time()); // no samples for a while: the server was off
            }
            long b = s.time() / bucket * bucket;
            if (b != start && n > 0) {
                players.point(start + bucket / 2, sumOn / (double) n);
                memory.point(start + bucket / 2, sumMem / (double) n);
                n = sumOn = sumMem = 0;
            }
            start = b;
            n++;
            sumOn += s.online();
            sumMem += s.memoryMb();
            last = s.time();
        }
        if (n > 0) {
            players.point(start + bucket / 2, sumOn / (double) n);
            memory.point(start + bucket / 2, sumMem / (double) n);
        }

        // Average players per hour of the day, in the server's time zone.
        double[] hourSum = new double[24];
        int[] hourCount = new int[24];
        ZoneId zone = ZoneId.systemDefault();
        for (Samples.Sample s : list) {
            int h = Instant.ofEpochMilli(s.time()).atZone(zone).getHour();
            hourSum[h] += s.online();
            hourCount[h]++;
        }
        PanelData.Columns hours = out.columns("Average players by hour (" + zone.getId() + ")");
        for (int h = 0; h < 24; h++) {
            hours.column(String.format("%02d", h), hourCount[h] == 0 ? 0 : hourSum[h] / hourCount[h]);
        }

        // Top playtime as bars next to the hour chart, then the full table below.
        List<KnownPlayers.Info> active = known.all();
        active.sort(Comparator.comparingLong(known::playtimeNow).reversed());
        PanelData.Bars top = out.bars("Most playtime");
        long max = active.isEmpty() ? 0 : known.playtimeNow(active.get(0));
        for (KnownPlayers.Info i : active.subList(0, Math.min(5, active.size()))) {
            long t = known.playtimeNow(i);
            if (t > 0) {
                top.bar(i.name(), i.sessions() + (i.sessions() == 1 ? " session" : " sessions"), max == 0 ? 0 : t, duration(t));
            }
        }
        PanelData.Table table = out.table("Most active players", "Player", "Playtime", "Sessions", "First seen", "Last seen");
        for (KnownPlayers.Info i : active.subList(0, Math.min(25, active.size()))) {
            table.row(i.name(), duration(known.playtimeNow(i)), i.sessions(),
                    i.firstSeen() == 0 ? "" : PanelData.time(i.firstSeen()),
                    onlineIds.contains(i.uuid()) ? "online now" : i.lastSeen() == 0 ? "" : PanelData.time(i.lastSeen()));
        }
        regulars(out, now, onlineIds);

        // Where everyone spends time (since Hycon was installed), busiest worlds first.
        for (String w : positions.worlds().subList(0, Math.min(2, positions.worlds().size()))) {
            PanelData.Heatmap h = out.heatmap("Where players spend time - " + w, PositionLog.CELL, "player-min");
            positions.heat(w).forEach((k, v) -> {
                int comma = k.indexOf(',');
                h.cell(Integer.parseInt(k.substring(0, comma)), Integer.parseInt(k.substring(comma + 1)), v);
            });
            for (PlayerRef p : Universe.get().getPlayers()) {
                World pw = p.getWorldUuid() == null ? null : Universe.get().getWorld(p.getWorldUuid());
                if (pw != null && pw.getName().equals(w) && p.getTransform() != null) {
                    h.pin(p.getUsername(), (int) p.getTransform().getPosition().x, (int) p.getTransform().getPosition().z);
                }
            }
        }
    }

    /**
     * Who comes back: new versus returning players in the range, and how many first-time players
     * returned on a later day.
     */
    private void retention(PanelData out, long now, long since, Set<UUID> onlineIds) {
        long today = now / DAY_MS;
        int fresh = 0, returning = 0, firstTimers = 0, cameBack = 0;
        for (KnownPlayers.Info i : known.all()) {
            boolean seen = i.lastSeen() >= since || onlineIds.contains(i.uuid());
            if (seen && i.firstSeen() >= since) {
                fresh++;
            } else if (seen && i.firstSeen() > 0) {
                returning++;
            }
            // Among players whose first recorded day is over, how many played on another day too.
            if (i.days().length > 0 && i.days()[0] < today) {
                firstTimers++;
                if (i.days().length >= 2) {
                    cameBack++;
                }
            }
        }
        out.stat("New players", String.valueOf(fresh), "first time in this period")
                .stat("Returning", String.valueOf(returning), "played here before")
                .stat("Came back", firstTimers == 0 ? "-" : Math.round(cameBack * 100.0 / firstTimers) + "%",
                        "played again on a later day (last " + KnownPlayers.KEEP_DAYS + " days)");
    }

    /** Regulars: at least a week of history, so a 24 h view still shows who sticks around. */
    private void regulars(PanelData out, long now, Set<UUID> onlineIds) {
        long today = now / DAY_MS;
        // Regulars: at least a week of history, so a 24 h view still shows who sticks around.
        // Days played are only kept KEEP_DAYS, so that's as far back as this table can look.
        long window = Math.min(Math.max(out.rangeMillis(), TimeUnit.DAYS.toMillis(7)), TimeUnit.DAYS.toMillis(KnownPlayers.KEEP_DAYS));
        long fromDay = (now - window) / DAY_MS + 1;
        int windowDays = (int) (today - fromDay + 1);
        List<KnownPlayers.Info> regulars = new ArrayList<>();
        for (KnownPlayers.Info i : known.all()) {
            if (i.daysSince(fromDay) >= 2) {
                regulars.add(i);
            }
        }
        regulars.sort(Comparator.<KnownPlayers.Info>comparingInt(i -> i.daysSince(fromDay)).reversed()
                .thenComparing(Comparator.comparingLong(known::playtimeNow).reversed()));
        PanelData.Table t = out.table("Players who stick around (last " + windowDays + " days)",
                "Player", "Days played", "Sessions", "Playtime", "Avg session", "First seen", "Last seen");
        for (KnownPlayers.Info i : regulars.subList(0, Math.min(25, regulars.size()))) {
            long play = known.playtimeNow(i);
            t.row(i.name(), i.daysSince(fromDay) + " of " + windowDays, i.sessions(), duration(play),
                    i.sessions() == 0 ? "" : duration(play / i.sessions()),
                    i.firstSeen() == 0 ? "" : PanelData.time(i.firstSeen()),
                    onlineIds.contains(i.uuid()) ? "online now" : PanelData.time(i.lastSeen()));
        }
    }

    private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);

    /** Chart bucket per range: about 300 points whatever the range. */
    private static long bucketFor(long range) {
        if (range <= TimeUnit.HOURS.toMillis(1)) {
            return Samples.INTERVAL_MS;
        }
        if (range <= TimeUnit.DAYS.toMillis(1)) {
            return TimeUnit.MINUTES.toMillis(5);
        }
        return range <= TimeUnit.DAYS.toMillis(7) ? TimeUnit.MINUTES.toMillis(30) : TimeUnit.HOURS.toMillis(2);
    }

    static String duration(long ms) {
        Duration d = Duration.ofMillis(Math.max(0, ms));
        if (d.toDays() > 0) {
            return d.toDays() + "d " + d.toHoursPart() + "h";
        }
        if (d.toHours() > 0) {
            return d.toHours() + "h " + d.toMinutesPart() + "m";
        }
        return d.toMinutes() > 0 ? d.toMinutes() + " min" : d.toSeconds() + " s";
    }
}
