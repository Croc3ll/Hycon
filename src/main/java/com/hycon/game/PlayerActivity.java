package com.hycon.game;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DiscoverZoneEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player does with the world, counted per kind and type since Hycon was installed:
 * blocks broken, blocks placed, blocks used (doors, chests, benches...), items crafted and zones
 * discovered. One file per player ({@code activity/<uuid>.json}); counters for players who are
 * offline and saved are dropped from memory, so a big player base costs nothing while away.
 */
public final class PlayerActivity {

    public enum Kind { BROKEN, PLACED, USED, CRAFTED, ZONES }

    /** Different types kept per kind; past this, new types are ignored (protects against odd blocks). */
    private static final int MAX_TYPES = 400;

    private final Path dir;
    private final Map<UUID, Map<Kind, Map<String, Long>>> counts = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    public PlayerActivity(Path dataDir) {
        this.dir = dataDir.resolve("activity");
    }

    /** Counts of one kind for a player, type to count (a copy). */
    public Map<String, Long> get(UUID player, Kind kind) {
        Map<String, Long> m = of(player).get(kind);
        synchronized (m) {
            return new HashMap<>(m);
        }
    }

    public void add(UUID player, Kind kind, String type, long n) {
        if (type == null || type.isEmpty() || n <= 0) {
            return;
        }
        Map<String, Long> m = of(player).get(kind);
        synchronized (m) {
            if (m.size() < MAX_TYPES || m.containsKey(type)) {
                m.merge(type, n, Long::sum);
                dirty.add(player);
            }
        }
    }

    /** Writes changed players; forgets saved players who are offline. Run every minute. */
    public void save(Set<UUID> online) {
        for (UUID u : Set.copyOf(dirty)) {
            dirty.remove(u);
            Map<Kind, Map<String, Long>> all = counts.get(u);
            if (all == null) {
                continue;
            }
            Json j = new Json().beginObject();
            for (Kind k : Kind.values()) {
                Map<String, Long> m = all.get(k);
                j.name(k.name()).beginObject();
                synchronized (m) {
                    m.forEach((t, n) -> j.field(t, n));
                }
                j.endObject();
            }
            j.endObject();
            try {
                Files.createDirectories(dir);
                Path tmp = dir.resolve(u + ".json.tmp");
                Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, dir.resolve(u + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                dirty.add(u); // try again next minute
            }
        }
        counts.keySet().removeIf(u -> !online.contains(u) && !dirty.contains(u));
    }

    private Map<Kind, Map<String, Long>> of(UUID player) {
        return counts.computeIfAbsent(player, this::load);
    }

    private Map<Kind, Map<String, Long>> load(UUID player) {
        Map<Kind, Map<String, Long>> all = new HashMap<>();
        for (Kind k : Kind.values()) {
            all.put(k, new HashMap<>());
        }
        Path f = dir.resolve(player + ".json");
        if (Files.exists(f)) {
            try {
                Map<String, Object> m = JsonReader.object(Files.readString(f, StandardCharsets.UTF_8));
                for (Kind k : Kind.values()) {
                    if (m.get(k.name()) instanceof Map<?, ?> km) {
                        km.forEach((t, n) -> {
                            if (t instanceof String type && n instanceof Double d) {
                                all.get(k).put(type, d.longValue());
                            }
                        });
                    }
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // a damaged file starts the counters over for this player
            }
        }
        return all;
    }

    // ---------- Game event hooks (one small system per event, on players only) ----------

    private abstract class Hook<E extends com.hypixel.hytale.component.system.EcsEvent> extends EntityEventSystem<EntityStore, E> {
        Hook(Class<E> type) {
            super(type);
        }

        abstract void count(UUID player, E event);

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull E event) {
            PlayerRef p = store.getComponent(chunk.getReferenceTo(index), PlayerRef.getComponentType());
            if (p != null) {
                try {
                    count(p.getUuid(), event);
                } catch (RuntimeException ignored) {
                    // never let statistics break the game action itself
                }
            }
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }
    }

    /** All systems to register with the entity store registry. */
    public java.util.List<EntityEventSystem<EntityStore, ?>> systems() {
        return java.util.List.of(
                new Hook<>(BreakBlockEvent.class) {
                    void count(UUID p, BreakBlockEvent e) {
                        if (!e.isCancelled()) {
                            add(p, Kind.BROKEN, e.getBlockType().getId(), 1);
                        }
                    }
                },
                new Hook<>(PlaceBlockEvent.class) {
                    void count(UUID p, PlaceBlockEvent e) {
                        if (!e.isCancelled() && e.getItemInHand() != null) {
                            add(p, Kind.PLACED, e.getItemInHand().getItemId(), 1);
                        }
                    }
                },
                new Hook<>(UseBlockEvent.Post.class) {
                    void count(UUID p, UseBlockEvent.Post e) {
                        add(p, Kind.USED, e.getBlockType().getId(), 1);
                    }
                },
                new Hook<>(CraftRecipeEvent.Post.class) {
                    void count(UUID p, CraftRecipeEvent.Post e) {
                        MaterialQuantity out = e.getCraftedRecipe().getPrimaryOutput();
                        String id = out != null && out.getItemId() != null ? out.getItemId() : e.getCraftedRecipe().getId();
                        add(p, Kind.CRAFTED, id, Math.max(1, e.getQuantity()));
                    }
                },
                new Hook<>(DiscoverZoneEvent.Display.class) {
                    void count(UUID p, DiscoverZoneEvent.Display e) {
                        add(p, Kind.ZONES, e.getDiscoveryInfo().zoneName(), 1);
                    }
                });
    }
}
