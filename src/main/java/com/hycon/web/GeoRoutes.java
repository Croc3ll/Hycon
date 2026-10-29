package com.hycon.web;

import com.hycon.auth.Level;
import com.hycon.game.GeoIp;
import com.hycon.game.KnownPlayers;
import com.hycon.game.Moderation;
import com.hycon.game.PingLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The player globe: where players roughly connect from (see {@link GeoIp}) with their usual ping.
 * Locations are personal data, so only moderators and up see them.
 */
public final class GeoRoutes {

    /** Ping is the median of the last {@value #PING_DAYS} days; recomputed at most once a minute per player. */
    private static final int PING_DAYS = 3;
    private static final long CACHE_MS = TimeUnit.MINUTES.toMillis(1);

    private record Cached(int ping, long at) {
    }

    private final WebServer web;
    private final GeoIp geo;
    private final KnownPlayers known;
    private final Moderation moderation;
    private final PingLog pings;
    private final double[] server;
    private final Map<UUID, Cached> pingCache = new ConcurrentHashMap<>();

    public GeoRoutes(WebServer web, GeoIp geo, KnownPlayers known, Moderation moderation, PingLog pings, String serverLocation) {
        this.web = web;
        this.geo = geo;
        this.known = known;
        this.moderation = moderation;
        this.pings = pings;
        this.server = parse(serverLocation);
    }

    public void register() {
        web.get("/api/geo", Level.MODERATOR, this::all);
        web.get("/api/geo/player", Level.MODERATOR, this::one);
    }

    private void all(Req r) throws IOException {
        Set<UUID> online = new HashSet<>();
        for (Moderation.OnlinePlayer p : moderation.online()) {
            online.add(p.uuid());
        }
        Json j = head();
        j.name("players").beginArray();
        geo.all().forEach((u, p) -> {
            j.beginObject().field("uuid", u.toString()).field("name", nameOf(u)).field("online", online.contains(u));
            place(j, p).field("ping", ping(u)).endObject();
        });
        r.ok(j.endArray().endObject());
    }

    private void one(Req r) throws IOException {
        Moderation.Target t = moderation.resolve(r.query("player"));
        if (t == null) {
            r.error(404, "Unknown player");
            return;
        }
        Json j = head();
        GeoIp.Place p = geo.place(t.uuid());
        if (p != null) {
            j.name("player").beginObject().field("uuid", t.uuid().toString()).field("name", t.name()).field("online", t.online() != null);
            place(j, p).field("ping", ping(t.uuid())).endObject();
        }
        r.ok(j.endObject());
    }

    private Json head() {
        Json j = new Json().beginObject().field("ready", geo.ready());
        if (server != null) {
            j.name("server").beginObject().field("lat", server[0]).field("lon", server[1]).endObject();
        }
        return j;
    }

    private static Json place(Json j, GeoIp.Place p) {
        return j.field("lat", p.lat()).field("lon", p.lon()).field("city", p.city()).field("region", p.region())
                .field("country", p.country()).field("cc", p.cc()).field("seen", p.time());
    }

    private String nameOf(UUID u) {
        String n = known.name(u);
        return n == null ? u.toString() : n;
    }

    /** Median ping over the last few days, or -1 when none was recorded. */
    private int ping(UUID u) {
        long now = System.currentTimeMillis();
        Cached c = pingCache.get(u);
        if (c != null && now - c.at() < CACHE_MS) {
            return c.ping();
        }
        int ms = -1;
        try {
            List<Integer> v = new ArrayList<>();
            for (PingLog.Sample s : pings.read(u, now - TimeUnit.DAYS.toMillis(PING_DAYS))) {
                v.add(s.ms());
            }
            if (!v.isEmpty()) {
                v.sort(null);
                ms = v.get(v.size() / 2);
            }
        } catch (IOException ignored) {
            // unreadable ping file: shown as unknown
        }
        pingCache.put(u, new Cached(ms, now));
        return ms;
    }

    private static double[] parse(String s) {
        String[] parts = s == null ? new String[0] : s.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            double lat = Double.parseDouble(parts[0].trim()), lon = Double.parseDouble(parts[1].trim());
            return Math.abs(lat) <= 90 && Math.abs(lon) <= 180 ? new double[] {lat, lon} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
