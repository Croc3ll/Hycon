package com.hycon.game;

import com.hycon.web.JsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.connection.Connect;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PacketWatcher;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;

/**
 * Test probe: logs the entitlements and scope in each joining player's identity token. Hytale
 * reads that list at login and then drops it, so the first {@link Connect} packet is the only
 * place a plugin can see it. We want to learn whether staff accounts carry a marker there.
 *
 * <p>The token is only decoded, not verified. Hytale verifies it itself a moment later and
 * kicks a forged one. So this is fine for logging but not for granting anything.
 */
public final class EntitlementProbe {

    private final HytaleLogger log;
    private PacketFilter filter;

    public EntitlementProbe(HytaleLogger log) {
        this.log = log;
    }

    public void start() {
        filter = PacketAdapters.registerInbound((PacketWatcher) (handler, packet) -> {
            if (packet instanceof Connect connect && connect.identityToken != null) {
                logToken(connect.identityToken);
            }
        });
    }

    public void stop() {
        if (filter != null) {
            PacketAdapters.deregisterInbound(filter);
            filter = null;
        }
    }

    private void logToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return;
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            log.at(Level.INFO).log("Hycon entitlement probe: %s", summary(payload));
        } catch (RuntimeException e) {
            // A malformed token: Hytale rejects it anyway, and the probe must never block a login.
            log.at(Level.FINE).log("Hycon entitlement probe could not read a token: %s", e);
        }
    }

    /** The token payload without its timestamps and keys: just who, scope and entitlements. */
    private static String summary(String payload) {
        Object parsed = JsonReader.parse(payload);
        if (!(parsed instanceof java.util.Map<?, ?> claims)) {
            return payload;
        }
        Object profile = claims.get("profile");
        Object username = profile instanceof java.util.Map<?, ?> p ? p.get("username") : null;
        Object entitlements = profile instanceof java.util.Map<?, ?> p ? p.get("entitlements") : null;
        return "user=" + username + " uuid=" + claims.get("sub") + " scope=" + claims.get("scope")
                + " entitlements=" + entitlements + " profileKeys=" + (profile instanceof java.util.Map<?, ?> p ? p.keySet() : null);
    }
}
