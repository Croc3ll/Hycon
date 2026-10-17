package com.hycon.web;

import com.hycon.audit.AuditLog;
import com.hycon.auth.Level;
import com.hycon.sync.BanSync;
import com.hycon.sync.ShareServer;
import com.hycon.sync.SharingStore;

import java.io.IOException;

/** OWNER-only settings behind the Ban sharing tab. The feed itself is served by {@link ShareServer}. */
public final class SyncRoutes {

    private final WebServer web;
    private final SharingStore store;
    private final BanSync sync;
    private final ShareServer share;
    private final AuditLog audit;

    public SyncRoutes(WebServer web, SharingStore store, BanSync sync, ShareServer share, AuditLog audit) {
        this.web = web;
        this.store = store;
        this.sync = sync;
        this.share = share;
        this.audit = audit;
    }

    public void register() {
        web.get("/api/sharing", Level.OWNER, this::list);
        web.post("/api/sharing/address", Level.OWNER, this::address);
        web.post("/api/sharing/key/create", Level.OWNER, this::createKey);
        web.post("/api/sharing/key/code", Level.OWNER, this::showCode);
        web.post("/api/sharing/key/revoke", Level.OWNER, this::revokeKey);
        web.post("/api/sharing/sub/add", Level.OWNER, this::addSub);
        web.post("/api/sharing/sub/remove", Level.OWNER, this::removeSub);
        web.post("/api/sharing/sub/enable", Level.OWNER, this::enableSub);
        web.post("/api/sharing/sync", Level.OWNER, this::syncNow);
    }

    private void list(Req r) throws IOException {
        Json j = new Json().beginObject().field("intervalMinutes", BanSync.INTERVAL_MIN).field("publicAddress", store.publicAddress())
                .field("sharePort", share.port()).field("shareRunning", share.running()).field("shareError", share.error())
                .name("keys").beginArray();
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

    private void address(Req r) throws IOException {
        try {
            store.setPublicAddress(r.str("address"));
            audit.record(r.who(), r.ip, "share.address", store.publicAddress(), null, true);
            r.ok(new Json().beginObject().field("ok", true).field("message", "Address saved").endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void createKey(Req r) throws IOException {
        if (store.publicAddress().isEmpty()) {
            r.error(400, "First enter this server's address (how other servers reach it)");
            return;
        }
        try {
            SharingStore.Key k = store.createKey(r.str("name"));
            share.update(); // the first key opens the share port
            audit.record(r.who(), r.ip, "share.key.create", k.name(), null, true);
            r.ok(new Json().beginObject().field("ok", true).field("code", code(k)).field("error", share.error()).endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    /** Shows an existing key's code again (e.g. the other owner lost it). */
    private void showCode(Req r) throws IOException {
        try {
            SharingStore.Key k = store.key(r.str("name"));
            audit.record(r.who(), r.ip, "share.key.view", k.name(), null, true);
            r.ok(new Json().beginObject().field("ok", true).field("code", code(k)).endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private String code(SharingStore.Key k) {
        String host = store.publicAddress();
        String url = "http://" + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + share.port() + ShareServer.PATH;
        return SharingStore.encode(new SharingStore.Code(url, k.id(), k.secret()));
    }

    private void revokeKey(Req r) throws IOException {
        try {
            store.revokeKey(r.str("name"));
            share.update(); // the last key closes the share port
            audit.record(r.who(), r.ip, "share.key.revoke", r.str("name"), null, true);
            r.ok(new Json().beginObject().field("ok", true).endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void addSub(Req r) throws IOException {
        try {
            SharingStore.Code code = SharingStore.decode(r.str("code"));
            store.addSubscription(r.str("name"), code);
            audit.record(r.who(), r.ip, "share.follow", r.str("name"), code.url(), true);
            sync.syncSoon();
            r.ok(new Json().beginObject().field("ok", true).field("message", "Following; first check running").endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void removeSub(Req r) throws IOException {
        try {
            SharingStore.Subscription s = store.removeSubscription(r.str("name"));
            int lifted = sync.liftAll(s);
            audit.record(r.who(), r.ip, "share.unfollow", s.name(), lifted + " shared bans lifted", true);
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
        r.ok(new Json().beginObject().field("ok", true).field("message", "Checking now").endObject());
    }
}
