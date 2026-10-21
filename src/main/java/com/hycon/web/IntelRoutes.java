package com.hycon.web;

import com.hycon.audit.AuditLog;
import com.hycon.auth.Level;
import com.hycon.game.KillLog;
import com.hycon.game.KnownPlayers;
import com.hycon.game.Moderation;
import com.hycon.game.PingLog;
import com.hycon.game.PlayerRecords;
import com.hycon.game.Reports;
import com.hycon.game.Ties;
import com.hycon.sync.SharingStore;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** The Reports tab and the connection map behind each profile. */
public final class IntelRoutes {

    /** Most players one map request returns, so a busy server's web stays readable and quick. */
    private static final int MAX_NODES = 150;

    private record Edge(UUID a, UUID b, String type, int weight) {
    }

    private final WebServer web;
    private final Reports reports;
    private final Ties ties;
    private final KillLog kills;
    private final KnownPlayers known;
    private final Moderation moderation;
    private final SharingStore sharing;
    private final PlayerRecords records;
    private final AuditLog audit;

    public IntelRoutes(WebServer web, Reports reports, Ties ties, KillLog kills, KnownPlayers known, Moderation moderation,
                       SharingStore sharing, PlayerRecords records, AuditLog audit) {
        this.web = web;
        this.reports = reports;
        this.ties = ties;
        this.kills = kills;
        this.known = known;
        this.moderation = moderation;
        this.sharing = sharing;
        this.records = records;
        this.audit = audit;
    }

    public void register() {
        web.get("/api/reports", Level.MODERATOR, this::list);
        web.post("/api/reports/close", Level.MODERATOR, this::close);
        web.get("/api/graph", Level.MODERATOR, this::graph);
    }

    private void list(Req r) throws IOException {
        String s = r.query("status");
        Reports.Status status = s == null || s.equals("all") ? null : Reports.Status.valueOf(s.toUpperCase());
        Json j = new Json().beginObject().field("open", reports.list(Reports.Status.OPEN, 10_000).size()).name("reports").beginArray();
        for (Reports.Report rep : reports.list(status, 500)) {
            Reports.write(j.beginObject(), rep).field("againstTotal", reports.against(rep.target())).endObject();
        }
        r.ok(j.endArray().endObject());
    }

    /** Marks a report handled or dismissed; handled ones also go into the player's history. */
    private void close(Req r) throws IOException {
        Reports.Status status = "dismissed".equalsIgnoreCase(r.str("status")) ? Reports.Status.DISMISSED : Reports.Status.HANDLED;
        Object id = r.body.get("id");
        if (!(id instanceof Double d)) {
            r.error(400, "Which report?");
            return;
        }
        String note = r.str("note") == null ? "" : r.str("note").strip();
        if (note.length() > 300) {
            note = note.substring(0, 300);
        }
        try {
            Reports.Report rep = reports.close(d.longValue(), status, r.who(), note);
            if (status == Reports.Status.HANDLED) {
                records.record(rep.target(), PlayerRecords.Type.NOTE, r.who(),
                        "Report by " + rep.reporterName() + " handled: " + rep.reason() + (note.isEmpty() ? "" : " (" + note + ")"), null);
            }
            audit.record(r.who(), r.ip, "report." + status.name().toLowerCase(), rep.targetName(), rep.reason(), true);
            r.ok(new Json().beginObject().field("ok", true).field("message", "Report " + status.name().toLowerCase()).endObject());
        } catch (IllegalArgumentException e) {
            r.error(404, e.getMessage());
        }
    }

    /**
     * The web around one player: everyone tied to them, and (depth 2) everyone tied to those.
     * Ties: same connection, co-joins, reports (either way) and player kills (last 14 days).
     */
    private void graph(Req r) throws IOException {
        Moderation.Target center = moderation.resolve(r.query("player"));
        if (center == null) {
            r.error(404, "Unknown player");
            return;
        }
        int depth = "1".equals(r.query("depth")) ? 1 : 2;

        // Report and kill ties for everyone, built once per request.
        Map<UUID, List<Edge>> extra = new HashMap<>();
        Map<String, int[]> reportPairs = new HashMap<>();
        for (Reports.Report rep : reports.all()) {
            reportPairs.computeIfAbsent(rep.reporter() + ">" + rep.target(), k -> new int[1])[0]++;
        }
        reportPairs.forEach((k, n) -> {
            int gt = k.indexOf('>');
            UUID a = UUID.fromString(k.substring(0, gt)), b = UUID.fromString(k.substring(gt + 1));
            Edge e = new Edge(a, b, "report", n[0]);
            extra.computeIfAbsent(a, x -> new ArrayList<>()).add(e);
            extra.computeIfAbsent(b, x -> new ArrayList<>()).add(e);
        });
        Map<String, int[]> killPairs = new HashMap<>();
        for (KillLog.Kill k : kills.since(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(PingLog.KEEP_DAYS))) {
            killPairs.computeIfAbsent(k.killer() + ">" + k.victim(), x -> new int[1])[0]++;
        }
        killPairs.forEach((k, n) -> {
            int gt = k.indexOf('>');
            UUID a = UUID.fromString(k.substring(0, gt)), b = UUID.fromString(k.substring(gt + 1));
            Edge e = new Edge(a, b, "kill", n[0]);
            extra.computeIfAbsent(a, x -> new ArrayList<>()).add(e);
            extra.computeIfAbsent(b, x -> new ArrayList<>()).add(e);
        });

        // Breadth-first from the centre, up to the depth and the node cap.
        Map<UUID, Integer> level = new LinkedHashMap<>();
        Map<String, Edge> edges = new LinkedHashMap<>();
        Deque<UUID> queue = new ArrayDeque<>();
        level.put(center.uuid(), 0);
        queue.add(center.uuid());
        while (!queue.isEmpty()) {
            UUID u = queue.poll();
            int lv = level.get(u);
            if (lv >= depth) {
                continue;
            }
            List<Edge> out = new ArrayList<>(extra.getOrDefault(u, List.of()));
            for (UUID o : ties.sameConnection(u)) {
                out.add(new Edge(u, o, "same", 1));
            }
            ties.coJoins(u).forEach((o, n) -> out.add(new Edge(u, o, "cojoin", n)));
            for (Edge e : out) {
                UUID o = e.a().equals(u) ? e.b() : e.a();
                if (!level.containsKey(o)) {
                    if (level.size() >= MAX_NODES) {
                        continue;
                    }
                    level.put(o, lv + 1);
                    queue.add(o);
                }
                edges.putIfAbsent(key(e), e);
            }
        }

        Set<UUID> online = moderation.onlineIds();
        Map<UUID, Moderation.BanInfo> bans = new HashMap<>();
        for (Moderation.BanInfo b : moderation.bans()) {
            bans.put(b.target(), b);
        }
        int banned = 0, reported = 0;
        Json j = new Json().beginObject().field("center", center.uuid().toString()).name("nodes").beginArray();
        for (Map.Entry<UUID, Integer> n : level.entrySet()) {
            UUID u = n.getKey();
            String name = known.name(u);
            Moderation.BanInfo ban = bans.get(u);
            int reps = reports.against(u);
            if (n.getValue() > 0) {
                banned += ban != null ? 1 : 0;
                reported += reps > 0 ? 1 : 0;
            }
            j.beginObject().field("id", u.toString()).field("name", name == null ? u.toString().substring(0, 8) : name)
                    .field("level", n.getValue()).field("online", online.contains(u)).field("reports", reps)
                    .field("banned", ban != null)
                    .field("sharedBan", ban != null && sharing.sourceOf(ban.by()) != null).endObject();
        }
        j.endArray().name("edges").beginArray();
        for (Edge e : edges.values()) {
            // Only ties between players that are both on the map.
            if (level.containsKey(e.a()) && level.containsKey(e.b())) {
                j.beginObject().field("a", e.a().toString()).field("b", e.b().toString()).field("type", e.type())
                        .field("weight", e.weight()).endObject();
            }
        }
        long direct = level.values().stream().filter(v -> v == 1).count();
        j.endArray().field("direct", direct).field("further", level.size() - 1 - direct).field("banned", banned)
                .field("reported", reported).field("capped", level.size() >= MAX_NODES);
        r.ok(j.endObject());
    }

    private static String key(Edge e) {
        String a = e.a().toString(), b = e.b().toString();
        // Reports and kills have a direction; same-connection and co-join ties don't.
        boolean directed = e.type().equals("report") || e.type().equals("kill");
        return e.type() + ":" + (directed || a.compareTo(b) < 0 ? a + ">" + b : b + ">" + a);
    }
}
