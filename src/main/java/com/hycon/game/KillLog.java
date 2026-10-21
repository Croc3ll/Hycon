package com.hycon.game;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.packets.connection.PongType;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.entity.damage.event.KillFeedEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Player-versus-player kills, each with the killer's ping at that second, so the profile can tell
 * whether someone's kills come while their connection looks bad (the "lag switch" pattern: lag on
 * purpose so other players can't hit back). Kept {@link PingLog#KEEP_DAYS} days in {@code kills.log}
 * ({@code time,killer,victim,pingMs}); mobs and other deaths are ignored.
 */
public final class KillLog {

    public record Kill(long time, UUID killer, UUID victim, int ping) {
    }

    private static final long KEEP_MS = TimeUnit.DAYS.toMillis(PingLog.KEEP_DAYS);

    private final Path file;
    private final List<Kill> kills = new ArrayList<>();
    private BufferedWriter out;

    public KillLog(Path dataDir) {
        this.file = dataDir.resolve("kills.log");
    }

    public synchronized void open() throws IOException {
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        boolean dropped = false;
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split(",");
                try {
                    Kill k = new Kill(Long.parseLong(p[0]), UUID.fromString(p[1]), UUID.fromString(p[2]), Integer.parseInt(p[3]));
                    if (k.time() >= cutoff) {
                        kills.add(k);
                    } else {
                        dropped = true;
                    }
                } catch (RuntimeException e) {
                    dropped = true;
                }
            }
        }
        Files.createDirectories(file.getParent());
        if (dropped) {
            Files.write(file, kills.stream().map(KillLog::line).toList(), StandardCharsets.UTF_8);
        }
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void close() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
            // shutting down
        }
        out = null;
    }

    synchronized void record(Kill k) {
        kills.add(k);
        if (out != null) {
            try {
                out.write(line(k));
                out.newLine();
                out.flush();
            } catch (IOException ignored) {
                // still counted in memory
            }
        }
    }

    /** Kills by or deaths of this player since {@code since}, oldest first. */
    public synchronized List<Kill> involving(UUID player, long since) {
        List<Kill> list = new ArrayList<>();
        for (Kill k : kills) {
            if (k.time() >= since && (k.killer().equals(player) || k.victim().equals(player))) {
                list.add(k);
            }
        }
        return list;
    }

    /** Everyone's kills since {@code since} (for the connection map later). */
    public synchronized List<Kill> since(long since) {
        List<Kill> list = new ArrayList<>();
        for (Kill k : kills) {
            if (k.time() >= since) {
                list.add(k);
            }
        }
        return list;
    }

    private static String line(Kill k) {
        return k.time() + "," + k.killer() + "," + k.victim() + "," + k.ping();
    }

    /**
     * Hears Hytale's "killer" kill-feed event, which the game raises on the entity that made a kill.
     * Runs on the world thread; only stores a line, so it costs nothing noticeable.
     */
    public final class Tracker extends EntityEventSystem<EntityStore, KillFeedEvent.KillerMessage> {

        public Tracker() {
            super(KillFeedEvent.KillerMessage.class);
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull KillFeedEvent.KillerMessage event) {
            PlayerRef killer = store.getComponent(chunk.getReferenceTo(index), PlayerRef.getComponentType());
            if (killer == null || !event.getTargetRef().isValid()) {
                return;
            }
            PlayerRef victim = store.getComponent(event.getTargetRef(), PlayerRef.getComponentType());
            if (victim == null || victim.getUuid().equals(killer.getUuid())) {
                return; // a mob, or a fall the game credits to the player themself
            }
            int ping = -1;
            try {
                // The one-second average: the connection as it was at the moment of the kill.
                double us = killer.getPacketHandler().getPingInfo(PongType.Raw).getPingMetricSet()
                        .getAverage(PacketHandler.PingInfo.ONE_SECOND_INDEX);
                if (us > 0 && !Double.isInfinite(us)) {
                    ping = (int) Math.round(us / 1000.0);
                }
            } catch (RuntimeException ignored) {
                // no ping known; the kill is still recorded
            }
            record(new Kill(System.currentTimeMillis(), killer.getUuid(), victim.getUuid(), ping));
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }
    }
}
