package com.hycon.game;

import com.hycon.api.HyconProfileSection;
import com.hycon.api.PanelData;
import com.hycon.audit.AuditLog;
import com.hycon.commands.ReportCommand;
import com.hycon.web.Json;
import com.hycon.web.JsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.connection.Connect;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PacketWatcher;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Reads the entitlements in each joining player's identity token: the game editions they own, and
 * anything else Hytale puts there. Hytale reads that list at login and then drops it, so the first
 * {@link Connect} packet is the only place a plugin can see it.
 *
 * <p>Each player's edition shows on their profile. An entitlement that isn't in
 * {@code staff-watch.json}'s {@code Editions}, or a player listed under {@code WatchPlayers}, alerts
 * staff: a chat line, the audit log and a log warning. We hope a Hytale staff account carries an
 * entitlement normal players don't.
 *
 * <p>The token is only decoded, not verified. Hytale verifies it a moment later and kicks a forged
 * one, but a forged token is still read first. So this only informs staff and never grants anything.
 */
public final class StaffWatch implements HyconProfileSection {

    /** Known entitlements and the edition name each one stands for, lowest edition first. */
    private static final Map<String, String> DEFAULT_EDITIONS = defaultEditions();

    private final HytaleLogger log;
    private final AuditLog audit;
    private final Path configFile;
    private final Path seenFile;
    private final Map<UUID, List<String>> seen = new ConcurrentHashMap<>();
    private volatile Map<String, String> editions = DEFAULT_EDITIONS;
    private volatile Map<String, String> watchPlayers = Map.of();
    private PacketFilter filter;

    public StaffWatch(Path dataDir, HytaleLogger log, AuditLog audit) {
        this.log = log;
        this.audit = audit;
        this.configFile = dataDir.resolve("staff-watch.json");
        this.seenFile = dataDir.resolve("editions.json");
    }

    public void start() {
        loadConfig();
        loadSeen();
        filter = PacketAdapters.registerInbound((PacketWatcher) (handler, packet) -> {
            if (packet instanceof Connect connect && connect.identityToken != null) {
                onConnect(connect.identityToken);
            }
        });
    }

    public void stop() {
        if (filter != null) {
            PacketAdapters.deregisterInbound(filter);
            filter = null;
        }
    }

    private void onConnect(String token) {
        try {
            Map<String, Object> claims = payload(token);
            if (claims == null || !(claims.get("profile") instanceof Map<?, ?> profile)) {
                return;
            }
            UUID uuid = UUID.fromString(String.valueOf(claims.get("sub")));
            String name = String.valueOf(profile.get("username"));
            List<String> owned = new ArrayList<>();
            if (profile.get("entitlements") instanceof List<?> list) {
                list.stream().filter(String.class::isInstance).map(String.class::cast).forEach(owned::add);
            }
            remember(uuid, owned);
            List<String> unusual = owned.stream().filter(e -> !editions.containsKey(e)).toList();
            String watchNote = watchPlayers.get(uuid.toString());
            if (!unusual.isEmpty() || watchNote != null) {
                alert(name, uuid, unusual, watchNote);
            }
        } catch (RuntimeException e) {
            // A malformed token: Hytale rejects it anyway, and the watch must never block a login.
            log.at(Level.FINE).log("Hycon staff watch could not read a token: %s", e);
        }
    }

    private void alert(String name, UUID uuid, List<String> unusual, String watchNote) {
        String why = watchNote != null ? "watched player (" + watchNote + ")" : "unusual entitlement " + String.join(", ", unusual);
        log.at(Level.WARNING).log("Hycon staff watch: %s (%s) is joining: %s", name, uuid, why);
        audit.record("Hycon", "", "staff-watch", name, why, true);
        Message note = Message.raw("[Staff watch] " + name + " is joining: " + why).color("#55FFFF");
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (p.hasPermission(ReportCommand.NOTIFY_PERMISSION)) {
                p.sendMessage(note);
            }
        }
    }

    @Override
    public String id() {
        return "edition";
    }

    @Override
    public String title() {
        return "Game edition";
    }

    @Override
    public void render(UUID player, String name, PanelData out) {
        List<String> owned = seen.get(player);
        if (owned == null) {
            out.stat("Edition", "Unknown", "seen at their next join");
            return;
        }
        out.stat("Edition", edition(owned)).stat("Entitlements", owned.isEmpty() ? "-" : String.join(", ", owned));
    }

    /** The highest edition the player owns, by the order in {@code Editions}. */
    private String edition(List<String> owned) {
        String best = "Unknown";
        for (Map.Entry<String, String> e : editions.entrySet()) {
            if (owned.contains(e.getKey())) {
                best = e.getValue();
            }
        }
        return best;
    }

    private static Map<String, Object> payload(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        return JsonReader.object(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    private void remember(UUID uuid, List<String> owned) {
        if (owned.equals(seen.put(uuid, List.copyOf(owned)))) {
            return;
        }
        Thread.ofVirtual().name("Hycon-StaffWatch").start(this::saveSeen);
    }

    private synchronized void saveSeen() {
        Json j = new Json().beginObject();
        seen.forEach((uuid, owned) -> {
            j.name(uuid.toString()).beginArray();
            owned.forEach(j::value);
            j.endArray();
        });
        try {
            Files.writeString(seenFile, j.endObject().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.at(Level.WARNING).log("Hycon could not save %s: %s", seenFile.getFileName(), e);
        }
    }

    private void loadSeen() {
        if (!Files.exists(seenFile)) {
            return;
        }
        try {
            JsonReader.object(Files.readString(seenFile, StandardCharsets.UTF_8)).forEach((k, v) -> {
                if (v instanceof List<?> list) {
                    seen.put(UUID.fromString(k), list.stream().filter(String.class::isInstance).map(String.class::cast).toList());
                }
            });
        } catch (IOException | RuntimeException e) {
            log.at(Level.WARNING).log("Hycon could not read %s: %s", seenFile.getFileName(), e);
        }
    }

    private void loadConfig() {
        try {
            if (!Files.exists(configFile)) {
                Files.writeString(configFile, defaultConfig(), StandardCharsets.UTF_8);
            }
            Map<String, Object> m = JsonReader.object(Files.readString(configFile, StandardCharsets.UTF_8));
            editions = stringMap(m.get("Editions"));
            watchPlayers = stringMap(m.get("WatchPlayers"));
        } catch (IOException | RuntimeException e) {
            log.at(Level.WARNING).log("Hycon could not read %s, using defaults: %s", configFile.getFileName(), e);
        }
    }

    private static Map<String, String> stringMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        }
        return out;
    }

    private static String defaultConfig() {
        Json j = new Json().beginObject().name("Editions").beginObject();
        DEFAULT_EDITIONS.forEach((k, v) -> j.name(k).value(v));
        return j.endObject().name("WatchPlayers").beginObject().endObject().endObject().toString();
    }

    private static Map<String, String> defaultEditions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("game.base", "Standard");
        m.put("game.deluxe", "Supporter");
        return m;
    }
}
