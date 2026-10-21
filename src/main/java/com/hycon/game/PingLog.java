package com.hycon.game;

import com.hypixel.hytale.protocol.packets.connection.PongType;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

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
 * Each online player's ping, once a minute, kept {@link #KEEP_DAYS} days for the profile's Ping
 * section. The value is Hytale's own measurement: the server pings every connection each second and
 * keeps a one-minute average, which smooths single hiccups the way a player experiences them.
 *
 * <p>One small file per player ({@code ping/<uuid>.log}, lines {@code time,ms}), appended to while
 * they play and trimmed when read, so players who never come back cost nothing.
 */
public final class PingLog {

    public static final int KEEP_DAYS = 14;
    private static final long KEEP_MS = TimeUnit.DAYS.toMillis(KEEP_DAYS);

    public record Sample(long time, int ms) {
    }

    private final Path dir;

    public PingLog(Path dataDir) {
        this.dir = dataDir.resolve("ping");
    }

    /** Called every minute from the scheduler: one sample per online player. */
    public void sample() {
        long now = System.currentTimeMillis();
        for (PlayerRef p : Universe.get().getPlayers()) {
            try {
                PacketHandler h = p.getPacketHandler();
                if (h == null) {
                    continue;
                }
                // Microseconds; the one-minute average period of Hytale's ping history.
                double us = h.getPingInfo(PongType.Raw).getPingMetricSet().getAverage(PacketHandler.PingInfo.ONE_MINUTE_INDEX);
                if (!(us > 0) || Double.isInfinite(us)) {
                    continue; // just joined: no measurement yet
                }
                append(p.getUuid(), now, (int) Math.round(us / 1000.0));
            } catch (RuntimeException | IOException ignored) {
                // a player leaving mid-sample, or a full disk; the next minute tries again
            }
        }
    }

    /** Samples newer than {@code since}, oldest first. Rewrites the file when old lines are dropped. */
    public synchronized List<Sample> read(UUID player, long since) throws IOException {
        Path f = file(player);
        if (!Files.exists(f)) {
            return List.of();
        }
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        List<Sample> out = new ArrayList<>();
        List<String> keep = new ArrayList<>();
        boolean dropped = false;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            int comma = line.indexOf(',');
            try {
                long t = Long.parseLong(line.substring(0, comma));
                if (t < cutoff) {
                    dropped = true;
                    continue;
                }
                keep.add(line);
                if (t >= since) {
                    out.add(new Sample(t, Integer.parseInt(line.substring(comma + 1))));
                }
            } catch (RuntimeException e) {
                dropped = true; // damaged line
            }
        }
        if (dropped) {
            Files.write(f, keep, StandardCharsets.UTF_8);
        }
        return out;
    }

    private synchronized void append(UUID player, long time, int ms) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(file(player), time + "," + ms + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private Path file(UUID player) {
        return dir.resolve(player + ".log");
    }
}
