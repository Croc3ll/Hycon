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
import com.hycon.game.KillLog;
import com.hycon.game.PingLog;
import com.hycon.game.PlayerRecords;
import com.hycon.sync.SharingStore;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
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
    private final PlayerRecords records;
    private final SharingStore sharing;
    private final PingLog pings;
    private final KillLog kills;

    public GameRoutes(WebServer web, Moderation moderation, ConsoleRunner console, LogTap log, AuditLog audit,
                      AdminStore admins, KnownPlayers known, PlayerRecords records, SharingStore sharing, PingLog pings,
                      KillLog kills) {
        this.pings = pings;
        this.kills = kills;
        this.records = records;
        this.sharing = sharing;
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
        web.post("/api/warn", Level.MODERATOR, this::warn);
        web.post("/api/note", Level.MODERATOR, this::note);
        web.get("/api/profile", Level.VIEWER, this::profile);
        web.get("/api/ping", Level.VIEWER, this::ping);
        web.get("/api/log", Level.MODERATOR, this::log);
        web.post("/api/command", Level.ADMIN, this::command);
        web.get("/api/complete", Level.ADMIN, this::complete);
        web.get("/api/audit", Level.ADMIN, this::audit);
        web.get("/api/panels", Level.VIEWER, this::panels);
        web.get("/api/panel", Level.VIEWER, this::panel);
        web.get("/api/profile-sections", Level.VIEWER, this::profileSections);
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
        java.util.Map<UUID, Moderation.BanInfo> banned = new java.util.HashMap<>();
        for (Moderation.BanInfo b : moderation.bans()) {
            banned.put(b.target(), b);
        }
        Json j = new Json().beginObject().field("total", known.size()).name("players").beginArray();
        for (KnownPlayers.Info i : known.search(q, online::contains, 100)) {
            j.beginObject().field("uuid", i.uuid().toString()).field("name", i.name()).field("online", online.contains(i.uuid()))
                    .field("first", i.firstSeen()).field("last", i.lastSeen()).field("world", i.world());
            Moderation.BanInfo b = banned.get(i.uuid());
            if (b != null) {
                j.field("banned", true);
                if (b.expires() != null) {
                    j.field("banEnds", b.expires().toEpochMilli());
                }
            }
            j.endObject();
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
        String source = sharing.sourceOf(by);
        if (source != null) {
            return "Shared: " + source;
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
        if (ok) {
            records.record(t.uuid(), PlayerRecords.Type.KICK, r.who(), reason, null);
        }
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
        if (ok) {
            records.record(t.uuid(), PlayerRecords.Type.BAN, r.who(), reason, len);
        }
        done(r, ok, ok ? "Banned " + t.name() + " (" + len + ")" : t.name() + " is already banned");
    }

    private void unban(Req r) throws IOException {
        Moderation.Target t = moderation.resolve(r.str("player"));
        if (t == null) {
            r.error(404, "Unknown player");
            return;
        }
        Moderation.BanInfo current = moderation.ban(t.uuid());
        String source = current == null ? null : sharing.sourceOf(current.by());
        boolean ok = moderation.unban(t.uuid());
        if (ok && source != null) {
            // A shared ban: remember it was lifted here, or the next sync would bring it back.
            sharing.addException(source, t.uuid(), current.since().toEpochMilli());
        }
        audit.record(r.who(), r.ip, "unban", label(t), null, ok);
        if (ok) {
            records.record(t.uuid(), PlayerRecords.Type.UNBAN, r.who(), reason(r), null);
        }
        done(r, ok, ok ? "Unbanned " + t.name() : t.name() + " wasn't banned");
    }

    /**
     * Warns a player: always saved to their history; shown on screen if they are online, otherwise
     * only recorded (they'll find it in their record, staff see it in the profile).
     */
    private void warn(Req r) throws IOException {
        String reason = reason(r);
        if (reason.isEmpty()) {
            r.error(400, "A warning needs a reason");
            return;
        }
        Moderation.Target t = moderation.resolve(r.str("player"));
        if (t == null) {
            r.error(404, "Unknown player");
            return;
        }
        int number = records.count(t.uuid(), PlayerRecords.Type.WARN) + 1;
        records.record(t.uuid(), PlayerRecords.Type.WARN, r.who(), reason, null);
        boolean shown = moderation.warn(t, reason, number);
        audit.record(r.who(), r.ip, "warn", label(t), reason, true);
        r.ok(new Json().beginObject().field("ok", true).field("message", "Warning #" + number + " for " + t.name()
                + (shown ? " shown in game" : " saved (player is offline)")).endObject());
    }

    /** A staff-only note on the player's profile; never shown to the player. */
    private void note(Req r) throws IOException {
        String text = r.str("text");
        text = text == null ? "" : text.strip().replaceAll("[\r\n\t]+", " ");
        if (text.isEmpty() || text.length() > 500) {
            r.error(400, "Notes are 1-500 characters");
            return;
        }
        Moderation.Target t = moderation.resolve(r.str("player"));
        if (t == null) {
            r.error(404, "Unknown player");
            return;
        }
        records.record(t.uuid(), PlayerRecords.Type.NOTE, r.who(), text, null);
        audit.record(r.who(), r.ip, "note", label(t), text, true);
        r.ok(new Json().beginObject().field("ok", true).field("message", "Note saved").endObject());
    }

    /** Everything known about one player: identity, status, current ban and full history. */
    private void profile(Req r) throws IOException {
        Moderation.Target t = moderation.resolve(r.query("player"));
        if (t == null) {
            r.error(404, "Unknown player");
            return;
        }
        KnownPlayers.Info info = known.get(t.uuid());
        Json j = new Json().beginObject().field("uuid", t.uuid().toString()).field("name", t.name()).field("online", t.online() != null);
        if (info != null) {
            j.field("first", info.firstSeen()).field("last", info.lastSeen()).field("world", info.world());
        }
        j.field("warnings", records.count(t.uuid(), PlayerRecords.Type.WARN))
                .field("kicks", records.count(t.uuid(), PlayerRecords.Type.KICK))
                .field("bans", records.count(t.uuid(), PlayerRecords.Type.BAN));
        Moderation.BanInfo ban = moderation.ban(t.uuid());
        if (ban != null) {
            j.name("ban").beginObject().field("by", byName(ban.by())).field("since", ban.since().toEpochMilli()).field("reason", ban.reason());
            if (ban.expires() != null) {
                j.field("expires", ban.expires().toEpochMilli());
            }
            j.endObject();
        }
        j.name("history").beginArray();
        for (PlayerRecords.Entry e : records.history(t.uuid())) {
            j.beginObject().field("time", e.time()).field("type", e.type().name()).field("by", e.by())
                    .field("reason", e.reason()).field("detail", e.detail()).endObject();
        }
        r.ok(j.endArray().endObject());
    }

    /**
     * A player's ping over the last days: the samples for the chart plus the summary numbers.
     * A spike is a short burst: at least 250 ms with both neighbouring minutes under 150 ms.
     * "Steady" means 95% of minutes under 150 ms and jitter (average change minute to minute)
     * under 30 ms.
     */
    private void ping(Req r) throws IOException {
        Moderation.Target t = moderation.resolve(r.query("player"));
        if (t == null) {
            r.error(404, "Unknown player");
            return;
        }
        long since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(PingLog.KEEP_DAYS);
        List<PingLog.Sample> list = pings.read(t.uuid(), since);
        Json j = new Json().beginObject().field("days", PingLog.KEEP_DAYS).field("samples", list.size());
        // Kills (with the killer's ping at that second) and deaths, for the dots and the lag verdict.
        int killCount = 0, highPingKills = 0, spikeKills = 0;
        j.name("kills").beginArray();
        for (KillLog.Kill k : kills.involving(t.uuid(), since)) {
            boolean mine = k.killer().equals(t.uuid());
            UUID other = mine ? k.victim() : k.killer();
            String otherName = known.name(other);
            j.beginObject().field("time", k.time()).field("kill", mine).field("ping", k.ping())
                    .field("other", otherName == null ? other.toString() : otherName).endObject();
            if (mine) {
                killCount++;
                highPingKills += k.ping() >= 150 ? 1 : 0;
                spikeKills += k.ping() >= 250 ? 1 : 0;
            }
        }
        j.endArray().field("killCount", killCount).field("highPingKills", highPingKills).field("spikeKills", spikeKills)
                // Worth a look: at least 5 kills and over half of them at high ping.
                .field("lagSuspect", killCount >= 5 && highPingKills * 2 > killCount);
        if (!list.isEmpty()) {
            int n = list.size();
            int[] sorted = new int[n];
            long sum = 0, jitterSum = 0;
            int over150 = 0, over250 = 0, spikes = 0, worstAt = 0, jitterN = 0;
            for (int i = 0; i < n; i++) {
                int ms = list.get(i).ms();
                sorted[i] = ms;
                sum += ms;
                over150 += ms >= 150 ? 1 : 0;
                over250 += ms >= 250 ? 1 : 0;
                if (ms > list.get(worstAt).ms()) {
                    worstAt = i;
                }
                // Jitter only between minutes of the same session (no gap over 3 minutes).
                if (i > 0 && list.get(i).time() - list.get(i - 1).time() < 180_000) {
                    jitterSum += Math.abs(ms - list.get(i - 1).ms());
                    jitterN++;
                }
                boolean calmBefore = i == 0 || list.get(i - 1).ms() < 150;
                boolean calmAfter = i == n - 1 || list.get(i + 1).ms() < 150;
                if (ms >= 250 && calmBefore && calmAfter) {
                    spikes++;
                }
            }
            java.util.Arrays.sort(sorted);
            int p95 = sorted[Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1)];
            double jitter = jitterN == 0 ? 0 : jitterSum / (double) jitterN;
            j.field("median", sorted[n / 2]).field("average", Math.round(sum / (double) n)).field("p95", p95)
                    .field("worst", sorted[n - 1]).field("worstAt", list.get(worstAt).time()).field("best", sorted[0])
                    .field("jitter", Math.round(jitter)).field("over150", Math.round(over150 * 100.0 / n))
                    .field("over250", Math.round(over250 * 100.0 / n)).field("spikes", spikes)
                    .field("steady", p95 < 150 && jitter < 30).name("points").beginArray();
            for (PingLog.Sample s : list) {
                j.beginArray().value(s.time()).value(s.ms()).endArray();
            }
            j.endArray();
        }
        r.ok(j.endObject());
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

    private void complete(Req r) throws IOException {
        String line = r.query("line");
        if (line == null || line.length() > 300 || line.indexOf('\n') >= 0) {
            r.error(400, "Bad input");
            return;
        }
        List<String> online = new java.util.ArrayList<>();
        for (Moderation.OnlinePlayer p : moderation.online()) {
            online.add(p.name());
        }
        List<String> knownNames = new java.util.ArrayList<>();
        for (KnownPlayers.Info i : known.all()) {
            if (!i.name().equals(i.uuid().toString())) {
                knownNames.add(i.name());
            }
        }
        knownNames.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> options = console.complete(r.admin.name(), line, online, knownNames);
        java.util.Set<String> onlineLower = new java.util.HashSet<>();
        online.forEach(n -> onlineLower.add(n.toLowerCase(java.util.Locale.ROOT)));
        java.util.Set<String> knownLower = new java.util.HashSet<>();
        knownNames.forEach(n -> knownLower.add(n.toLowerCase(java.util.Locale.ROOT)));
        // Each option says whether it's an online player, an offline player or anything else,
        // so the panel can colour names green or red.
        Json j = new Json().beginObject().name("options").beginArray();
        for (String o : options) {
            String key = o.toLowerCase(java.util.Locale.ROOT);
            j.beginObject().field("text", o).field("kind", onlineLower.contains(key) ? "online" : knownLower.contains(key) ? "offline" : "other").endObject();
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
        PanelData data = new PanelData(range(r.query("range")), "all".equals(r.query("range")));
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

    /**
     * Every plugin's section for one player's profile, each drawn like a dashboard tab. A section
     * that throws only shows its error; the others still appear.
     */
    private void profileSections(Req r) throws IOException {
        Moderation.Target t = moderation.resolve(r.query("player"));
        if (t == null) {
            r.error(404, "Unknown player");
            return;
        }
        String range = r.query("range");
        Json j = new Json().beginObject().name("sections").beginArray();
        for (com.hycon.api.HyconProfileSection s : HyconApi.profileSections()) {
            if (r.admin.level().rank < s.minLevel()) {
                continue;
            }
            PanelData data = new PanelData(range(range), "all".equals(range));
            j.beginObject().field("id", s.id()).field("title", s.title());
            try {
                s.render(t.uuid(), t.name(), data);
                j.name("blocks");
                data.write(j);
            } catch (RuntimeException | LinkageError e) {
                j.field("error", s.title() + " failed: " + e);
            }
            j.endObject();
        }
        r.ok(j.endArray().endObject());
    }

    /** "1h", "6h", "24h", "7d", "30d" to ms; defaults to 6 hours, at most a year. */
    private static long range(String s) {
        long def = TimeUnit.DAYS.toMillis(1);
        if ("all".equals(s)) {
            // "All time": a range reaching back to 1970, so "now - range" includes every record.
            return System.currentTimeMillis();
        }
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
