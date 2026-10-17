package com.hycon.sync;

import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.modules.accesscontrol.ban.Ban;
import com.hycon.audit.AuditLog;
import com.hycon.game.KnownPlayers;
import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ban sharing between servers running Hycon.
 *
 * <p><b>Feed</b> ({@link #feed}): this server's own bans, for servers holding one of its share keys.
 * Bans imported from elsewhere are left out, so servers subscribed to each other never bounce the
 * same ban back and forth.
 *
 * <p><b>Subscriptions</b> ({@link #syncAll}): every {@link #INTERVAL_MIN} minutes each enabled
 * subscription's feed is fetched. New bans are added to Hytale's ban list as "banned by" the
 * subscription; bans that disappeared from the feed (lifted or expired there) are lifted here.
 * Only bans this subscription added are ever lifted: bans made here, in game or in the panel,
 * are never touched. A failed fetch changes nothing.
 */
public final class BanSync {

    public static final int INTERVAL_MIN = 2;
    /** A feed bigger than this is refused rather than read into memory. */
    private static final int MAX_FEED_BYTES = 8 * 1024 * 1024;
    private static final int MAX_REASON = 200;

    public record Status(long lastSync, String error, int feedBans, int imported) {
    }

    private final SharingStore store;
    private final KnownPlayers known;
    private final AuditLog audit;
    private final Map<String, Status> status = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build(); // a redirect could send the key elsewhere
    private ScheduledExecutorService executor;

    public BanSync(SharingStore store, KnownPlayers known, AuditLog audit) {
        this.store = store;
        this.known = known;
        this.audit = audit;
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Hycon-BanSync");
            t.setDaemon(true); // network waits must never hold up the game's own scheduler
            return t;
        });
        executor.scheduleWithFixedDelay(this::syncAll, 20, INTERVAL_MIN * 60L, TimeUnit.SECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Runs a sync soon, off the web thread (the "Sync now" button). */
    public void syncSoon() {
        if (executor != null) {
            executor.execute(this::syncAll);
        }
    }

    public Status status(String subscription) {
        return status.get(subscription.toLowerCase());
    }

    /** JSON of this server's own bans in effect. */
    public String feed() {
        Set<UUID> imported = new HashSet<>();
        for (SharingStore.Subscription s : store.subscriptions()) {
            imported.add(s.uuid());
        }
        Json j = new Json().beginObject().field("hycon", 1).name("bans").beginArray();
        for (Ban b : AccessControlModule.get().getBanProvider().getBans()) {
            if (!b.isInEffect() || imported.contains(b.getBy())) {
                continue;
            }
            j.beginObject().field("uuid", b.getTarget().toString()).field("name", known.name(b.getTarget()))
                    .field("reason", b.getReason()).field("since", b.getTimestamp().toEpochMilli());
            if (b.getExpiresOn() != null) {
                j.field("expires", b.getExpiresOn().toEpochMilli());
            }
            j.endObject();
        }
        return j.endArray().endObject().toString();
    }

    void syncAll() {
        for (SharingStore.Subscription s : store.subscriptions()) {
            if (s.enabled()) {
                try {
                    sync(s);
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    Status old = status.get(key(s));
                    status.put(key(s), new Status(System.currentTimeMillis(), msg, old == null ? 0 : old.feedBans(), countImported(s)));
                }
            }
        }
    }

    private void sync(SharingStore.Subscription s) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(s.url())).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + s.token()).header("Accept", "application/json").GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream in = res.body()) {
            body = in.readNBytes(MAX_FEED_BYTES + 1);
        }
        if (res.statusCode() == 401) {
            throw new IllegalStateException("The other server refused the key (revoked or mistyped)");
        }
        if (res.statusCode() != 200) {
            throw new IllegalStateException("The other server answered HTTP " + res.statusCode());
        }
        if (body.length > MAX_FEED_BYTES) {
            throw new IllegalStateException("Feed too large");
        }
        if (!(JsonReader.object(new String(body, StandardCharsets.UTF_8)).get("bans") instanceof List<?> list)) {
            throw new IllegalStateException("Not a Hycon ban feed (check the address)");
        }

        Map<UUID, Ban> wanted = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("uuid") instanceof String u && m.get("since") instanceof Double since) {
                UUID uuid = UUID.fromString(u);
                Instant expires = m.get("expires") instanceof Double e ? Instant.ofEpochMilli(e.longValue()) : null;
                if (expires != null && expires.toEpochMilli() <= now) {
                    continue;
                }
                String reason = m.get("reason") instanceof String r ? r : "";
                if (store.isException(s.name(), uuid, since.longValue())) {
                    continue; // lifted here by a moderator
                }
                if (reason.length() > MAX_REASON) {
                    reason = reason.substring(0, MAX_REASON);
                }
                wanted.put(uuid, new Ban(uuid, s.uuid(), Instant.ofEpochMilli(since.longValue()), expires,
                        "[Shared: " + s.name() + "] " + reason));
            }
        }

        AccessControlModule acm = AccessControlModule.get();
        int added = 0, lifted = 0;
        // Lift what this subscription added but the feed no longer lists.
        for (Ban b : acm.getBanProvider().getBans()) {
            if (b.getBy().equals(s.uuid()) && b.isInEffect()) {
                Ban w = wanted.get(b.getTarget());
                if (w == null || !Objects.equals(w.getExpiresOn(), b.getExpiresOn()) || !Objects.equals(w.getReason(), b.getReason())) {
                    acm.unban(b.getTarget()); // gone, or changed there: re-added below if still wanted
                    if (w == null) {
                        lifted++;
                    }
                }
            }
        }
        // Add new ones, unless the player already has a ban from somewhere else (that one wins).
        for (Ban w : wanted.values()) {
            if (!acm.getBanProvider().hasBan(w.getTarget()) && acm.ban(w)) {
                added++;
            }
        }
        Set<UUID> listed = new HashSet<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("uuid") instanceof String u) {
                listed.add(UUID.fromString(u));
            }
        }
        store.pruneExceptions(s.name(), listed);
        if (added > 0 || lifted > 0) {
            audit.record("Sync:" + s.name(), "-", "sync", s.name(), added + " bans added, " + lifted + " lifted", true);
        }
        status.put(key(s), new Status(now, null, wanted.size(), countImported(s)));
    }

    /** Lifts every ban this subscription added (when it is removed). Returns how many. */
    public int liftAll(SharingStore.Subscription s) {
        int n = 0;
        AccessControlModule acm = AccessControlModule.get();
        for (Ban b : acm.getBanProvider().getBans()) {
            if (b.getBy().equals(s.uuid()) && acm.unban(b.getTarget())) {
                n++;
            }
        }
        status.remove(key(s));
        return n;
    }

    private int countImported(SharingStore.Subscription s) {
        int n = 0;
        for (Ban b : AccessControlModule.get().getBanProvider().getBans()) {
            if (b.getBy().equals(s.uuid()) && b.isInEffect()) {
                n++;
            }
        }
        return n;
    }

    private static String key(SharingStore.Subscription s) {
        return s.name().toLowerCase();
    }
}
