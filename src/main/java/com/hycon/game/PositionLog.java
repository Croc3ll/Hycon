package com.hycon.game;

import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Where players spend their time, for the heatmaps. Every minute each online player's world and
 * position is noted:
 * <ul>
 *   <li>per player in {@code positions/<uuid>.log} ({@code time,world,x,z}), kept {@link #KEEP_DAYS} days,
 *       for the map on their profile;</li>
 *   <li>server-wide as minutes per {@link #CELL}x{@link #CELL}-block cell per world in {@code heat.json},
 *       for the map on the Analytics tab (since Hycon was installed).</li>
 * </ul>
 */
public final class PositionLog {

    public static final int KEEP_DAYS = 14;
    /** Map cell size in blocks: fine enough to tell a mine from the spawn, coarse enough to stay small. */
    public static final int CELL = 16;
    private static final int MAX_CELLS_PER_WORLD = 40_000;

    public record Spot(long time, String world, int x, int z) {
    }

    private final Path dir;
    private final Path heatFile;
    /** world -> "cellX,cellZ" -> minutes */
    private final Map<String, Map<String, Integer>> heat = new ConcurrentHashMap<>();
    private volatile boolean heatDirty;

    public PositionLog(Path dataDir) {
        this.dir = dataDir.resolve("positions");
        this.heatFile = dataDir.resolve("heat.json");
    }

    public void load() {
        try {
            if (Files.exists(heatFile)) {
                JsonReader.object(Files.readString(heatFile, StandardCharsets.UTF_8)).forEach((w, cells) -> {
                    if (cells instanceof Map<?, ?> m) {
                        Map<String, Integer> grid = new ConcurrentHashMap<>();
                        m.forEach((k, v) -> {
                            if (k instanceof String key && v instanceof Double d) {
                                grid.put(key, d.intValue());
                            }
                        });
                        heat.put(w, grid);
                    }
                });
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // an unreadable heat map just starts over
        }
    }

    /** Every minute: note where each online player is. */
    public void sample() {
        long now = System.currentTimeMillis();
        for (PlayerRef p : Universe.get().getPlayers()) {
            try {
                UUID worldId = p.getWorldUuid();
                World w = worldId == null ? null : Universe.get().getWorld(worldId);
                if (w == null || p.getTransform() == null) {
                    continue;
                }
                Vector3d pos = p.getTransform().getPosition();
                int x = (int) Math.floor(pos.x), z = (int) Math.floor(pos.z);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(p.getUuid() + ".log"), now + "," + w.getName().replace(",", "_") + "," + x + "," + z + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Map<String, Integer> grid = heat.computeIfAbsent(w.getName(), k -> new ConcurrentHashMap<>());
                String cell = Math.floorDiv(x, CELL) + "," + Math.floorDiv(z, CELL);
                if (grid.size() < MAX_CELLS_PER_WORLD || grid.containsKey(cell)) {
                    grid.merge(cell, 1, Integer::sum);
                    heatDirty = true;
                }
            } catch (IOException | RuntimeException ignored) {
                // a player leaving mid-sample; next minute tries again
            }
        }
    }

    /** This player's spots since {@code since}; trims lines older than {@link #KEEP_DAYS} days. */
    public synchronized List<Spot> read(UUID player, long since) throws IOException {
        Path f = dir.resolve(player + ".log");
        if (!Files.exists(f)) {
            return List.of();
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(KEEP_DAYS);
        List<Spot> out = new ArrayList<>();
        List<String> keep = new ArrayList<>();
        boolean dropped = false;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] p = line.split(",");
            try {
                long t = Long.parseLong(p[0]);
                if (t < cutoff) {
                    dropped = true;
                    continue;
                }
                keep.add(line);
                if (t >= since) {
                    out.add(new Spot(t, p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3])));
                }
            } catch (RuntimeException e) {
                dropped = true;
            }
        }
        if (dropped) {
            Files.write(f, keep, StandardCharsets.UTF_8);
        }
        return out;
    }

    /** Server-wide minutes per cell for one world (a copy), keyed "cellX,cellZ". */
    public Map<String, Integer> heat(String world) {
        Map<String, Integer> g = heat.get(world);
        return g == null ? Map.of() : new HashMap<>(g);
    }

    /** Worlds with heat, most minutes first. */
    public List<String> worlds() {
        List<String> list = new ArrayList<>(heat.keySet());
        list.sort((a, b) -> Long.compare(total(b), total(a)));
        return list;
    }

    private long total(String world) {
        long n = 0;
        for (int v : heat.getOrDefault(world, Map.of()).values()) {
            n += v;
        }
        return n;
    }

    public synchronized void saveIfChanged() {
        if (!heatDirty) {
            return;
        }
        heatDirty = false;
        Json j = new Json().beginObject();
        heat.forEach((w, grid) -> {
            j.name(w).beginObject();
            grid.forEach((k, v) -> j.field(k, v));
            j.endObject();
        });
        j.endObject();
        try {
            Path tmp = heatFile.resolveSibling("heat.json.tmp");
            Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, heatFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            heatDirty = true;
        }
    }
}
