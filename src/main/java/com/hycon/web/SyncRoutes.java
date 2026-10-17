package com.hycon.web;

import com.hycon.audit.AuditLog;
import com.hycon.auth.Level;
import com.hycon.auth.LoginLimiter;
import com.hycon.sync.BanSync;
import com.hycon.sync.SharingStore;

import java.io.IOException;

/** Ban sharing: the feed other servers read, and the OWNER-only settings behind the Ban sharing tab. */
public final class SyncRoutes {

    /** Path of the feed; the full address is shown to owners when they make a key. */
    public static final String FEED = "/share/bans";

    private final WebServer web;
    private final SharingStore store;
    private final BanSync sync;
    private final AuditLog audit;
    /** Wrong keys count like failed logins, so keys can't be guessed by hammering the feed. */
    private final LoginLimiter limiter;

    public SyncRoutes(WebServer web, SharingStore store, BanSync sync, AuditLog audit, LoginLimiter limiter) {
        this.web = web;
        this.store = store;
        this.sync = sync;
        this.audit = audit;
        this.limiter = limiter;
    }

    public void register() {
        web.machineGet(FEED, this::feed);
        web.get("/api/sharing", Level.OWNER, this::list);
        web.post("/api/sharing/key/create", Level.OWNER, this::createKey);
        web.post("/api/sharing/key/revoke", Level.OWNER, this::revokeKey);
        web.post("/api/sharing/sub/add", Level.OWNER, this::addSub);
        web.post("/api/sharing/sub/remove", Level.OWNER, this::removeSub);
        web.post("/api/sharing/sub/enable", Level.OWNER, this::enableSub);
        web.post("/api/sharing/sync", Level.OWNER, this::syncNow);
    }

    private void feed(Req r) throws IOException {
        if (limiter.retryAfter(r.ip, "share-feed") > 0) {
            r.error(429, "Too many wrong keys");
            return;
        }
        String auth = r.header("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        if (!store.validKey(token)) {
            limiter.fail(r.ip, "share-feed");
            audit.record("-", r.ip, "share.feed", null, "wrong or missing key", false);
            r.error(401, "Wrong or missing share key");
            return;
        }
        r.okRaw(sync.feed());
    }

    private void list(Req r) throws IOException {
        Json j = new Json().beginObject().field("feedPath", FEED).field("intervalMinutes", BanSync.INTERVAL_MIN).name("keys").beginArray();
        for (SharingStore.Key k : store.keys()) {
            j.beginObject().field("name", k.name()).field("created", k.created()).endObject();
        }
        j.endArray().name("subscriptions").beginArray();
        for (SharingStore.Subscription s : store.subscriptions()) {
            j.beginObject().field("name", s.name()).field("url", s.url()).field("enabled", s.enabled());
            BanSync.Status st = sync.status(s.name());
            if (st != null) {
                j.field("lastSync", st.lastSync()).field("error", st.error()).field("feedBans", st.feedBans()).field("imported", st.imported());
            }
            j.endObject();
        }
        r.ok(j.endArray().endObject());
    }

    private void createKey(Req r) throws IOException {
        try {
            String token = store.createKey(r.str("name"));
            audit.record(r.who(), r.ip, "share.key.create", r.str("name"), null, true);
            r.ok(new Json().beginObject().field("ok", true).field("token", token).endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void revokeKey(Req r) throws IOException {
        try {
            store.revokeKey(r.str("name"));
            audit.record(r.who(), r.ip, "share.key.revoke", r.str("name"), null, true);
            r.ok(new Json().beginObject().field("ok", true).endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void addSub(Req r) throws IOException {
        try {
            store.addSubscription(r.str("name"), r.str("url"), r.str("token"));
            audit.record(r.who(), r.ip, "share.subscribe", r.str("name"), r.str("url"), true);
            sync.syncSoon();
            r.ok(new Json().beginObject().field("ok", true).endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void removeSub(Req r) throws IOException {
        try {
            SharingStore.Subscription s = store.removeSubscription(r.str("name"));
            int lifted = sync.liftAll(s);
            audit.record(r.who(), r.ip, "share.unsubscribe", s.name(), lifted + " imported bans lifted", true);
            r.ok(new Json().beginObject().field("ok", true).field("message", "Removed; " + lifted + " bans from it lifted").endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void enableSub(Req r) throws IOException {
        try {
            boolean on = Boolean.TRUE.equals(r.body.get("enabled"));
            store.setEnabled(r.str("name"), on);
            audit.record(r.who(), r.ip, on ? "share.resume" : "share.pause", r.str("name"), null, true);
            if (on) {
                sync.syncSoon();
            }
            r.ok(new Json().beginObject().field("ok", true).endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void syncNow(Req r) throws IOException {
        sync.syncSoon();
        r.ok(new Json().beginObject().field("ok", true).field("message", "Syncing now").endObject());
    }
}
