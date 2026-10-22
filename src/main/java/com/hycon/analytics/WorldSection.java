package com.hycon.analytics;

import com.hycon.api.HyconProfileSection;
import com.hycon.api.PanelData;
import com.hycon.game.PlayerActivity;
import com.hycon.game.PositionLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * "Where and what" on every profile: a heatmap of where the player spends time (from the
 * once-a-minute position log, up to 14 days), how far they travel, and what they do with the world:
 * blocks broken, placed and used, items crafted, zones discovered (since Hycon was installed).
 */
public final class WorldSection implements HyconProfileSection {

    /** Finer cells than the server map: one player's paths are narrow. */
    private static final int CELL = 8;
    /** Samples further apart than this are separate sessions, not travel. */
    private static final long SAME_SESSION_MS = TimeUnit.MINUTES.toMillis(3);

    private final PositionLog positions;
    private final PlayerActivity activity;

    public WorldSection(PositionLog positions, PlayerActivity activity) {
        this.positions = positions;
        this.activity = activity;
    }

    @Override
    public String id() {
        return "world";
    }

    @Override
    public String title() {
        return "Where and what";
    }

    @Override
    public void render(UUID player, String name, PanelData out) {
        long since = System.currentTimeMillis() - Math.min(out.rangeMillis(), TimeUnit.DAYS.toMillis(PositionLog.KEEP_DAYS));
        List<PositionLog.Spot> spots;
        try {
            spots = positions.read(player, since);
        } catch (IOException e) {
            spots = List.of();
        }

        // Minutes per world, grid per world, and distance walked within sessions.
        Map<String, Map<Long, Integer>> grids = new HashMap<>();
        Map<String, Integer> perWorld = new HashMap<>();
        double travelled = 0;
        PositionLog.Spot prev = null;
        for (PositionLog.Spot s : spots) {
            perWorld.merge(s.world(), 1, Integer::sum);
            long key = ((long) Math.floorDiv(s.x(), CELL) << 32) ^ (Math.floorDiv(s.z(), CELL) & 0xffffffffL);
            grids.computeIfAbsent(s.world(), k -> new HashMap<>()).merge(key, 1, Integer::sum);
            if (prev != null && prev.world().equals(s.world()) && s.time() - prev.time() <= SAME_SESSION_MS) {
                travelled += Math.hypot(s.x() - prev.x(), s.z() - prev.z());
            }
            prev = s;
        }
        List<String> worlds = new ArrayList<>(perWorld.keySet());
        worlds.sort((a, b) -> perWorld.get(b) - perWorld.get(a));

        Map<String, Long> broken = activity.get(player, PlayerActivity.Kind.BROKEN);
        out.stat("Most time in", worlds.isEmpty() ? "-" : worlds.get(0), worlds.isEmpty() ? null : perWorld.get(worlds.get(0)) + " min")
                .stat("Travelled", String.format("%,.0f blocks", travelled), "walking distance, last " + PositionLog.KEEP_DAYS + " days at most")
                .stat("Blocks broken", String.format("%,d", sum(broken)), "since Hycon was installed")
                .stat("Blocks placed", String.format("%,d", sum(activity.get(player, PlayerActivity.Kind.PLACED))))
                .stat("Zones found", String.valueOf(activity.get(player, PlayerActivity.Kind.ZONES).size()));

        for (String w : worlds.subList(0, Math.min(2, worlds.size()))) {
            PanelData.Heatmap h = out.heatmap("Where " + name + " spends time - " + w, CELL, "min");
            grids.get(w).forEach((k, v) -> h.cell((int) (k >> 32), (int) k.longValue(), v));
            PositionLog.Spot last = null;
            for (PositionLog.Spot s : spots) {
                if (s.world().equals(w)) {
                    last = s;
                }
            }
            if (last != null) {
                h.pin("Last seen here", last.x(), last.z());
            }
        }

        pie(out, "Blocks broken", broken, "blocks");
        pie(out, "Blocks placed", activity.get(player, PlayerActivity.Kind.PLACED), "blocks");
        pie(out, "Blocks used (doors, chests, benches...)", activity.get(player, PlayerActivity.Kind.USED), "times");
        pie(out, "Crafted", activity.get(player, PlayerActivity.Kind.CRAFTED), "items");
        Map<String, Long> zones = activity.get(player, PlayerActivity.Kind.ZONES);
        if (!zones.isEmpty()) {
            PanelData.Bars b = out.bars("Zones discovered");
            zones.keySet().stream().sorted().forEach(z -> b.bar(pretty(z), null, 1, ""));
        }
    }

    private static void pie(PanelData out, String title, Map<String, Long> counts, String unit) {
        PanelData.Pie p = out.pie(title).unit(unit);
        counts.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> p.slice(pretty(e.getKey()), e.getValue()));
    }

    /** "Rock_Stone_Cobble" -> "Rock Stone Cobble". */
    static String pretty(String id) {
        return id.replace('_', ' ');
    }

    private static long sum(Map<String, Long> m) {
        long n = 0;
        for (long v : m.values()) {
            n += v;
        }
        return n;
    }
}
