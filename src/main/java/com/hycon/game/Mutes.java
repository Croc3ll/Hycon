package com.hycon.game;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat mutes from the panel, kept in {@code mutes.json}. A muted player's chat lines are cancelled
 * before any other plugin formats or sends them, and they're told why and for how long.
 * Hytale has no mute of its own, so this is the only one.
 */
public final class Mutes {

    /** {@code until == 0}: permanent. */
    public record Mute(UUID player, String name, String by, String reason, long since, long until) {
        public boolean expired(long now) {
            return until != 0 && until <= now;
        }
    }

    private final Path file;
    private final Map<UUID, Mute> mutes = new ConcurrentHashMap<>();

    public Mutes(Path dataDir) {
        this.file = dataDir.resolve("mutes.json");
    }

    public synchronized void load() throws IOException {
        mutes.clear();
        if (!Files.exists(file)) {
            return;
        }
        if (JsonReader.object(Files.readString(file, StandardCharsets.UTF_8)).get("mutes") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    try {
                        UUID u = UUID.fromString((String) m.get("player"));
                        mutes.put(u, new Mute(u, str(m, "name"), str(m, "by"), str(m, "reason"),
                                ((Double) m.get("since")).longValue(), ((Double) m.get("until")).longValue()));
                    } catch (RuntimeException ignored) {
                        // skip a damaged entry
                    }
                }
            }
        }
    }

    /** The player's mute in effect, or null. An ended mute is dropped here. */
    public Mute get(UUID player) {
        Mute m = mutes.get(player);
        if (m != null && m.expired(System.currentTimeMillis())) {
            mutes.remove(player, m);
            saveQuietly();
            return null;
        }
        return m;
    }

    /** Mutes in effect, newest first. */
    public List<Mute> all() {
        long now = System.currentTimeMillis();
        List<Mute> list = new ArrayList<>(mutes.values().stream().filter(m -> !m.expired(now)).toList());
        list.sort((a, b) -> Long.compare(b.since(), a.since()));
        return list;
    }

    /** Mutes (or re-mutes) a player; {@code duration == null} is permanent. */
    public synchronized Mute mute(UUID player, String name, String by, String reason, Duration duration) throws IOException {
        long now = System.currentTimeMillis();
        Mute m = new Mute(player, name, by, reason == null ? "" : reason, now, duration == null ? 0 : now + duration.toMillis());
        mutes.put(player, m);
        save();
        return m;
    }

    /** True when the player was muted. */
    public synchronized boolean unmute(UUID player) throws IOException {
        boolean was = get(player) != null;
        if (mutes.remove(player) != null) {
            save();
        }
        return was;
    }

    /** Cancels a muted player's chat line and tells them why. Registered first, before any plugin formats chat. */
    public void onChat(PlayerChatEvent e) {
        if (e.isCancelled() || e.getSender() == null) {
            return;
        }
        Mute m = get(e.getSender().getUuid());
        if (m == null) {
            return;
        }
        e.setCancelled(true);
        e.getSender().sendMessage(Message.raw(notice(m)).color("#FF5555"));
    }

    /** What a muted player reads: "You are muted for 2h 5m: spam". */
    public static String notice(Mute m) {
        String left = m.until() == 0 ? "permanently" : "for " + left(m.until() - System.currentTimeMillis());
        return "You are muted " + left + (m.reason().isBlank() ? "." : ": " + m.reason());
    }

    private static String left(long ms) {
        long min = Math.max(1, (ms + 59_999) / 60_000);
        if (min < 60) {
            return min + " min";
        }
        long h = min / 60;
        return h < 48 ? h + "h " + (min % 60) + "m" : (h / 24) + " days";
    }

    public static Json write(Json j, Mute m) {
        return j.field("player", m.player().toString()).field("name", m.name()).field("by", m.by()).field("reason", m.reason())
                .field("since", m.since()).field("until", m.until());
    }

    private void saveQuietly() {
        try {
            save();
        } catch (IOException ignored) {
            // the ended mute is dropped again on the next look
        }
    }

    private synchronized void save() throws IOException {
        Json j = new Json().beginObject().name("mutes").beginArray();
        for (Mute m : mutes.values()) {
            write(j.beginObject(), m).endObject();
        }
        j.endArray().endObject();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("mutes.json.tmp");
        Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String str(Map<?, ?> m, String k) {
        return m.get(k) instanceof String s ? s : "";
    }
}
