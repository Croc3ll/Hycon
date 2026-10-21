package com.hycon.game;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Player reports from {@code /report}, kept in {@code reports.json} until staff close them
 * (closed ones stay too, as history and as ties on the connection map).
 *
 * <p>Limits so the command can't be used to flood staff: one report a minute per reporter, and
 * one open report per reporter and target at a time.
 */
public final class Reports {

    public enum Status { OPEN, HANDLED, DISMISSED }

    public record Report(long id, long time, UUID reporter, String reporterName, UUID target, String targetName,
                         String reason, Status status, String closedBy, String note) {
        Report close(Status s, String by, String note) {
            return new Report(id, time, reporter, reporterName, target, targetName, reason, s, by, note);
        }
    }

    private static final long COOLDOWN_MS = 60_000;
    private static final int MAX_REPORTS = 20_000;

    private final Path file;
    private final List<Report> reports = new ArrayList<>();
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    public Reports(Path dataDir) {
        this.file = dataDir.resolve("reports.json");
    }

    public synchronized void load() throws IOException {
        reports.clear();
        if (!Files.exists(file)) {
            return;
        }
        if (JsonReader.object(Files.readString(file, StandardCharsets.UTF_8)).get("reports") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    try {
                        Report r = new Report(((Double) m.get("id")).longValue(), ((Double) m.get("time")).longValue(),
                                UUID.fromString((String) m.get("reporter")), str(m, "reporterName"),
                                UUID.fromString((String) m.get("target")), str(m, "targetName"), str(m, "reason"),
                                Status.valueOf((String) m.get("status")), str(m, "closedBy"), str(m, "note"));
                        reports.add(r);
                        nextId.set(Math.max(nextId.get(), r.id() + 1));
                    } catch (RuntimeException ignored) {
                        // skip a damaged entry
                    }
                }
            }
        }
    }

    /**
     * Files a report. Returns null when accepted, else the reason it was refused (shown to the player).
     */
    public synchronized String file(UUID reporter, String reporterName, UUID target, String targetName, String reason) throws IOException {
        if (reporter.equals(target)) {
            return "You can't report yourself.";
        }
        long now = System.currentTimeMillis();
        Long last = lastReport.get(reporter);
        if (last != null && now - last < COOLDOWN_MS) {
            return "Please wait a minute between reports.";
        }
        for (Report r : reports) {
            if (r.status() == Status.OPEN && r.reporter().equals(reporter) && r.target().equals(target)) {
                return "You already reported " + targetName + "; staff will look at it.";
            }
        }
        if (reports.size() >= MAX_REPORTS) {
            // Full: forget the oldest closed report (open ones are never dropped).
            for (int i = 0; i < reports.size(); i++) {
                if (reports.get(i).status() != Status.OPEN) {
                    reports.remove(i);
                    break;
                }
            }
        }
        reports.add(new Report(nextId.getAndIncrement(), now, reporter, reporterName, target, targetName, reason,
                Status.OPEN, "", ""));
        lastReport.put(reporter, now);
        save();
        return null;
    }

    public synchronized Report close(long id, Status status, String by, String note) throws IOException {
        for (int i = 0; i < reports.size(); i++) {
            Report r = reports.get(i);
            if (r.id() == id) {
                Report c = r.close(status, by, note == null ? "" : note);
                reports.set(i, c);
                save();
                return c;
            }
        }
        throw new IllegalArgumentException("No such report");
    }

    /** Newest first; {@code status == null} for all. */
    public synchronized List<Report> list(Status status, int max) {
        List<Report> out = new ArrayList<>();
        for (int i = reports.size() - 1; i >= 0 && out.size() < max; i--) {
            if (status == null || reports.get(i).status() == status) {
                out.add(reports.get(i));
            }
        }
        return out;
    }

    public synchronized List<Report> all() {
        return List.copyOf(reports);
    }

    /** Open reports against this player. */
    public synchronized int openAgainst(UUID target) {
        int n = 0;
        for (Report r : reports) {
            if (r.status() == Status.OPEN && r.target().equals(target)) {
                n++;
            }
        }
        return n;
    }

    /** All reports against this player, open or closed. */
    public synchronized int against(UUID target) {
        int n = 0;
        for (Report r : reports) {
            if (r.target().equals(target)) {
                n++;
            }
        }
        return n;
    }

    public static Json write(Json j, Report r) {
        return j.field("id", r.id()).field("time", r.time()).field("reporter", r.reporter().toString())
                .field("reporterName", r.reporterName()).field("target", r.target().toString()).field("targetName", r.targetName())
                .field("reason", r.reason()).field("status", r.status().name()).field("closedBy", r.closedBy()).field("note", r.note());
    }

    private void save() throws IOException {
        Json j = new Json().beginObject().name("reports").beginArray();
        for (Report r : reports) {
            write(j.beginObject(), r).endObject();
        }
        j.endArray().endObject();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("reports.json.tmp");
        Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String str(Map<?, ?> m, String k) {
        return m.get(k) instanceof String s ? s : "";
    }
}
