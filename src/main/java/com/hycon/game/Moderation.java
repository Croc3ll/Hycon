package com.hycon.game;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.io.ConnectionRegistry;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.modules.accesscontrol.ban.Ban;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Players, kicks and bans, all through Hytale's own systems: bans go into the server's normal ban
 * list ({@code bans.json} / {@code /ban}, {@code /unban}), so they work the same whether made in the
 * panel or in game, and the server itself enforces them and lets temporary bans expire.
 */
public final class Moderation {

    public record OnlinePlayer(UUID uuid, String name, String world) {
    }

    public record BanInfo(UUID target, String name, UUID by, Instant since, Instant expires, String reason) {
    }

    /** A player resolved from panel input (name or UUID); {@code online} is null when offline. */
    public record Target(UUID uuid, String name, PlayerRef online) {
    }

    private final KnownPlayers known;

    public Moderation(KnownPlayers known) {
        this.known = known;
    }

    public List<OnlinePlayer> online() {
        Collection<PlayerRef> refs = Universe.get().getPlayers();
        List<OnlinePlayer> list = new ArrayList<>(refs.size());
        for (PlayerRef p : refs) {
            UUID worldId = p.getWorldUuid();
            World w = worldId == null ? null : Universe.get().getWorld(worldId);
            String world = w == null ? "" : w.getName();
            known.seen(p.getUuid(), p.getUsername(), world);
            list.add(new OnlinePlayer(p.getUuid(), p.getUsername(), world));
        }
        list.sort(Comparator.comparing(o -> o.name().toLowerCase()));
        return list;
    }

    /**
     * Exact name (any case) or UUID. Deliberately no prefix matching like Hytale's own commands do:
     * a typo in a ban must not hit a different player.
     */
    public Target resolve(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) {
            return null;
        }
        UUID uuid = null;
        try {
            uuid = UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            // not a UUID; treat as a name
        }
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (uuid != null ? p.getUuid().equals(uuid) : p.getUsername().equalsIgnoreCase(s)) {
                return new Target(p.getUuid(), p.getUsername(), p);
            }
        }
        if (uuid == null) {
            uuid = known.uuid(s);
        }
        if (uuid == null) {
            return null;
        }
        String name = known.name(uuid);
        return new Target(uuid, name == null ? uuid.toString() : name, null);
    }

    /** UUIDs of everyone online right now. */
    public java.util.Set<UUID> onlineIds() {
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (PlayerRef p : Universe.get().getPlayers()) {
            ids.add(p.getUuid());
        }
        return ids;
    }

    /** Disconnects the player; returns false if they weren't online. */
    public boolean kick(Target t, String reason) {
        Message msg = reason == null || reason.isBlank()
                ? Message.translation("server.general.disconnect.kick.reason")
                : Message.raw("Kicked: " + reason);
        return ConnectionRegistry.get().disconnect(t.uuid(), msg) > 0;
    }

    /**
     * Adds a ban (and Hytale disconnects the player if online). {@code duration == null} is
     * permanent. Returns false if the player is already banned.
     */
    public boolean ban(Target t, UUID by, String reason, Duration duration) {
        Instant now = Instant.now();
        Ban ban = new Ban(t.uuid(), by, now, duration == null ? null : now.plus(duration),
                reason == null || reason.isBlank() ? "No reason." : reason);
        return AccessControlModule.get().ban(ban);
    }

    public boolean unban(UUID uuid) {
        return AccessControlModule.get().unban(uuid);
    }

    public List<BanInfo> bans() {
        List<BanInfo> list = new ArrayList<>();
        for (Ban b : AccessControlModule.get().getBanProvider().getBans()) {
            if (b.isInEffect()) {
                String name = known.name(b.getTarget());
                list.add(new BanInfo(b.getTarget(), name, b.getBy(), b.getTimestamp(), b.getExpiresOn(), b.getReason()));
            }
        }
        list.sort(Comparator.comparing(BanInfo::since).reversed());
        return list;
    }

    /**
     * "30m", "12h", "7d", "2w"; empty or "permanent" means permanent (null).
     *
     * @throws IllegalArgumentException for anything else
     */
    public static Duration parseDuration(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("permanent")) {
            return null;
        }
        String t = s.trim().toLowerCase();
        try {
            long n = Long.parseLong(t.substring(0, t.length() - 1));
            if (n <= 0 || n > 100_000) {
                throw new IllegalArgumentException("Duration out of range");
            }
            return switch (t.charAt(t.length() - 1)) {
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                case 'd' -> Duration.ofDays(n);
                case 'w' -> Duration.ofDays(n * 7);
                default -> throw new IllegalArgumentException("Use e.g. 30m, 12h, 7d, 2w or permanent");
            };
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Use e.g. 30m, 12h, 7d, 2w or permanent");
        }
    }
}
