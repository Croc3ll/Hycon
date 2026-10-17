package com.hycon.web;

import com.hycon.api.HyconApi;
import com.hycon.api.HyconPanel;
import com.hycon.api.PanelData;
import com.hycon.audit.AuditLog;
import com.hycon.auth.AdminStore;
import com.hycon.auth.Level;
import com.hycon.game.ConsoleRunner;
import com.hycon.game.KnownPlayers;
import com.hycon.game.LogTap;
import com.hycon.game.Moderation;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Players, kick, ban, live log, console commands, audit log and plugin dashboards. */
public final class GameRoutes {

    private static final int MAX_REASON = 200;

    private final WebServer web;
    private final Moderation moderation;
    private final ConsoleRunner console;
    private final LogTap log;
    private final AuditLog audit;
    private final AdminStore admins;
    private final KnownPlayers known;

    public GameRoutes(WebServer web, Moderation moderation, ConsoleRunner console, LogTap log, AuditLog audit,
                      AdminStore admins, KnownPlayers known) {
        this.web = web;
        this.moderation = moderation;
        this.console = console;
        this.log = log;
        this.audit = audit;
        this.admins = admins;
        this.known = known;
    }

    public void register() {
        web.get("/api/players", Level.VIEWER, this::players);
        web.get("/api/bans", Level.VIEWER, this::bans);
        web.get("/api/directory", Level.VIEWER, this::directory);
        web.post("/api/kick", Level.MODERATOR, this::kick);
        web.post("/api/ban", Level.MODERATOR, this::ban);
        web.post("/api/unban", Level.MODERATOR, this::unban);
        web.get("/api/log", Level.MODERATOR, this::log);
        web.post("/api/command", Level.ADMIN, this::command);
        web.get("/api/audit", Level.ADMIN, this::audit);
        web.get("/api/panels", Level.VIEWER, this::panels);
        web.get("/api/panel", Level.VIEWER, this::panel);
    }

    private void players(Req r) throws IOException {
        Json j = new Json().beginObject().name("players").beginArray();
        for (Moderation.OnlinePlayer p : moderation.online()) {
            j.beginObject().field("uuid", p.uuid().toString()).field("name", p.name()).field("world", p.world()).endObject();
        }
        r.ok(j.endArray().endObject());
    }

    /** Search every player who has been on the server, online or not. */
    private void directory(Req r) throws IOException {
        String q = r.query("q");
        if (q != null && q.length() > 64) {
            q = q.substring(0, 64);
        }
        java.util.Set<UUID> online = moderation.onlineIds();
        Json j = new Json().beginObject().field("total", known.size()).name("players").beginArray();
        for (KnownPlayers.Info i : known.search(q, online::contains, 100)) {
            j.beginObject().field("uuid", i.uuid().toString()).field("name", i.name()).field("online", online.contains(i.uuid()))
                    .field("first", i.firstSeen()).field("last", i.lastSeen()).field("world", i.world()).endObject();
        }
        r.ok(j.endArray().endObject());
    }

    private void bans(Req r) throws IOException {
        Json j = new Json().beginObject().name("bans").beginArray();
        for (Moderation.BanInfo b : moderation.bans()) {
            j.beginObject().field("uuid", b.target().toString()).field("name", b.name()).field("by", byName(b.by()))
                    .field("since", b.since().toEpochMilli()).field("reason", b.reason());
            if (b.expires() != null) {
                j.field("expires", b.expires().toEpochMilli()); // missing = permanent
            }
            j.endObject();
        }
        r.ok(j.endArray().endObject());
    }

    /** Panel account, in-game player or "Console" behind a "banned by" UUID. */
    private String byName(UUID by) {
        if (by.getMostSignificantBits() == 0 && by.getLeastSignificantBits() == 0) {
            return "Console";
        }
        for (AdminStore.Admin a : admins.all()) {
            if (a.uuid().equals(by)) {
                return "Hycon:" + a.name();
            }
        }
        String n = known.name(by);
        return n != null ? n : by.toString();
    }

    private void kick(Req r) throws IOException {
        String reason = reason(r);
        Moderation.Target t = moderation.resolve(r.str("player"));
        if (t == null || t.online() == null) {
            audit.record(r.who(), r.ip, "kick", r.str("player"), "not online", false);
            r.error(404, "That player isn't online");
            return;
        }
        boolean ok = moderation.kick(t, reason);
        audit.record(r.who(), r.ip, "kick", label(t), reason, ok);
        done(r, ok, ok ? "Kicked " + t.name() : t.name() + " wasn't online");
    }

    private void ban(Req r) throws IOException {
        String reason = reason(r);
        Duration duration;
        try {
            duration = Moderation.parseDuration(r.str("duration"));
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
            return;
        }
        Moderation.Target t = moderation.resolve(r.str("player"));
        if (t == null) {
            r.error(404, "Unknown player. Use the exact name of someone who has been online, or their UUID.");
            return;
        }
        boolean ok = moderation.ban(t, r.admin.uuid(), reason, duration);
        String len = duration == null ? "permanent" : r.str("duration");
        audit.record(r.who(), r.ip, "ban", label(t), len + ": " + reason, ok);
        done(r, ok, ok ? "Banned " + t.name() + " (" + len + ")" : t.name() + " is already banned");
    }

    private void unban(Req r) throws IOException {
        Moderation.Target t = moderation.resolve(r.str("player"));
        if (t == null) {
            r.error(404, "Unknown player");
            return;
        }
        boolean ok = moderation.unban(t.uuid());
        audit.record(r.who(), r.ip, "unban", label(t), null, ok);
        done(r, ok, ok ? "Unbanned " + t.name() : t.name() + " wasn't banned");
    }

    private void log(Req r) throws IOException {
        long after = -1;
        try {
            String a = r.query("after");
            after = a == null ? -1 : Long.parseLong(a);
        } catch (NumberFormatException ignored) {
            // start from the newest lines
        }
        Json j = new Json().beginObject().name("lines").beginArray();
        for (LogTap.Line l : log.since(after, 500)) {
            j.beginArray().value(l.seq()).value(l.text()).endArray();
        }
        r.ok(j.endArray().field("latest", log.latest()).endObject());
    }

    private void command(Req r) throws IOException {
        String cmd = r.str("command");
        if (cmd == null || cmd.isBlank() || cmd.length() > 1000 || cmd.indexOf('\n') >= 0) {
            r.error(400, "One command per line, up to 1000 characters");
            return;
        }
        if (console.isBlocked(cmd)) {
            audit.record(r.who(), r.ip, "command", cmd, "blocked by BlockedCommands", false);
            r.error(403, "That command is blocked in hycon.json");
            return;
        }
        // Audited before running: if the command takes the server down, the entry is already on disk.
        audit.record(r.who(), r.ip, "command", cmd, null, true);
        log.addText("[Hycon] " + r.who() + " ran: /" + cmd.strip().replaceFirst("^/", ""));
        ConsoleRunner.Result res = console.run(r.admin.name(), cmd);
        Json j = new Json().beginObject().field("finished", res.finished()).field("error", res.error()).name("output").beginArray();
        for (String line : res.output()) {
            j.value(line);
            log.addText("[Hycon] " + line);
        }
        r.ok(j.endArray().endObject());
    }

    private void audit(Req r) throws IOException {
        Json j = new Json().beginObject().name("entries").beginArray();
        for (AuditLog.Entry e : audit.recent(200)) {
            AuditLog.write(j.beginObject().field("time", e.time()), e).endObject();
        }
        r.ok(j.endArray().endObject());
    }

    private void panels(Req r) throws IOException {
        Json j = new Json().beginObject().name("panels").beginArray();
        for (HyconPanel p : HyconApi.panels()) {
            if (r.admin.level().rank >= p.minLevel()) {
                j.beginObject().field("id", p.id()).field("title", p.title()).endObject();
            }
        }
        r.ok(j.endArray().endObject());
    }

    private void panel(Req r) throws IOException {
        HyconPanel p = HyconApi.get(r.query("id"));
        if (p == null || r.admin.level().rank < p.minLevel()) {
            r.error(404, "No such dashboard");
            return;
        }
        PanelData data = new PanelData(range(r.query("range")));
        try {
            p.render(data);
        } catch (RuntimeException | LinkageError e) {
            r.error(500, p.title() + " failed: " + e);
            return;
        }
        Json j = new Json().beginObject().field("title", p.title()).name("blocks");
        data.write(j);
        r.ok(j.endObject());
    }

    /** "1h", "6h", "24h", "7d", "30d" to ms; defaults to 6 hours, at most a year. */
    private static long range(String s) {
        long def = TimeUnit.HOURS.toMillis(6);
        if (s == null || s.length() < 2) {
            return def;
        }
        try {
            long n = Long.parseLong(s.substring(0, s.length() - 1));
            long ms = switch (s.charAt(s.length() - 1)) {
                case 'h' -> TimeUnit.HOURS.toMillis(n);
                case 'd' -> TimeUnit.DAYS.toMillis(n);
                default -> def;
            };
            return Math.max(TimeUnit.MINUTES.toMillis(10), Math.min(ms, TimeUnit.DAYS.toMillis(366)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** "Name uuid" for the audit log, or just the UUID when the name isn't known. */
    private static String label(Moderation.Target t) {
        String u = t.uuid().toString();
        return t.name().equals(u) ? u : t.name() + " " + u;
    }

    private static String reason(Req r) {
        String s = r.str("reason");
        if (s == null) {
            return "";
        }
        s = s.strip().replaceAll("[\\r\\n\\t]+", " ");
        return s.length() > MAX_REASON ? s.substring(0, MAX_REASON) : s;
    }

    private static void done(Req r, boolean ok, String message) throws IOException {
        r.json(ok ? 200 : 409, new Json().beginObject().field("ok", ok).field(ok ? "message" : "error", message).endObject());
    }
}
