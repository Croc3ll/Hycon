package com.hycon.game;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Bug reports from {@code /bug}, kept in {@code bugs.json} until staff close them. Each one carries
 * what the server can see at that moment: where the player stood, and the server's warnings and
 * errors from the few minutes before (a player's own client log stays on their computer).
 *
 * <p>One bug report a minute per player, so it can't flood staff.
 */
public final class BugReports {

    public enum Status { OPEN, FIXED, DISMISSED }

    public record Bug(long id, long time, UUID reporter, String reporterName, String text, String where,
                      String serverLog, Status status, String closedBy, String note) {
        Bug close(Status s, String by, String note) {
            return new Bug(id, time, reporter, reporterName, text, where, serverLog, s, by, note);
        }
    }

    private static final long COOLDOWN_MS = 60_000;
    private static final int MAX_BUGS = 5_000;
    /** How far back the attached server log reaches, and how many lines of it. */
    private static final long LOG_WINDOW_MS = 5 * 60_000;
    private static final int LOG_LINES = 40;

    private final Path file;
    private final List<Bug> bugs = new ArrayList<>();
    private final Map<UUID, Long> lastBug = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    /** The server's recent warnings and errors: {millis, line}. */
    private final Deque<Object[]> recent = new ArrayDeque<>();
    private Handler handler;

    public BugReports(Path dataDir) {
        this.file = dataDir.resolve("bugs.json");
    }

    /** Starts keeping the server's recent warnings and errors, for the log attached to each bug. */
    public void startLogCapture() {
        handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r == null || r.getLevel().intValue() < Level.WARNING.intValue()) {
                    return;
                }
                String msg = String.valueOf(r.getMessage());
                try {
                    if (r.getParameters() != null && r.getParameters().length > 0) {
                        msg = String.format(msg, r.getParameters());
                    }
                } catch (RuntimeException ignored) {
                    // keep the raw message
                }
                StringBuilder line = new StringBuilder(r.getLevel().getName()).append(" [").append(r.getLoggerName()).append("] ").append(msg);
                Throwable t = r.getThrown();
                if (t != null) {
                    line.append(" | ").append(t);
                    StackTraceElement[] st = t.getStackTrace();
                    for (int i = 0; i < Math.min(3, st.length); i++) {
                        line.append(" at ").append(st[i]);
                    }
                }
                String text = line.length() > 600 ? line.substring(0, 600) : line.toString();
                synchronized (recent) {
                    recent.addLast(new Object[] {r.getMillis(), text});
                    while (recent.size() > 300) {
                        recent.removeFirst();
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger("").addHandler(handler);
    }

    public void stopLogCapture() {
        if (handler != null) {
            Logger.getLogger("").removeHandler(handler);
        }
    }

    private String recentLog(long now) {
        List<String> lines = new ArrayList<>();
        java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("HH:mm:ss");
        synchronized (recent) {
            for (Object[] e : recent) {
                if (now - (Long) e[0] <= LOG_WINDOW_MS) {
                    lines.add(clock.format(new java.util.Date((Long) e[0])) + " " + e[1]);
                }
            }
        }
        if (lines.size() > LOG_LINES) {
            lines = lines.subList(lines.size() - LOG_LINES, lines.size());
        }
        return String.join("\n", lines);
    }

    public synchronized void load() throws IOException {
        bugs.clear();
        if (!Files.exists(file)) {
            return;
        }
        if (JsonReader.object(Files.readString(file, StandardCharsets.UTF_8)).get("bugs") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    try {
                        Bug b = new Bug(((Double) m.get("id")).longValue(), ((Double) m.get("time")).longValue(),
                                UUID.fromString((String) m.get("reporter")), str(m, "reporterName"), str(m, "text"), str(m, "where"),
                                str(m, "serverLog"), Status.valueOf((String) m.get("status")), str(m, "closedBy"), str(m, "note"));
                        bugs.add(b);
                        nextId.set(Math.max(nextId.get(), b.id() + 1));
                    } catch (RuntimeException ignored) {
                        // skip a damaged entry
                    }
                }
            }
        }
    }

    /** Files a bug report. Returns null when accepted, else why it was refused (shown to the player). */
    public synchronized String file(UUID reporter, String reporterName, String text, String where) throws IOException {
        long now = System.currentTimeMillis();
        Long last = lastBug.get(reporter);
        if (last != null && now - last < COOLDOWN_MS) {
            return "Please wait a minute between bug reports.";
        }
        if (bugs.size() >= MAX_BUGS) {
            // Full: forget the oldest closed one (open ones are never dropped).
            for (int i = 0; i < bugs.size(); i++) {
                if (bugs.get(i).status() != Status.OPEN) {
                    bugs.remove(i);
                    break;
                }
            }
        }
        bugs.add(new Bug(nextId.getAndIncrement(), now, reporter, reporterName, text, where, recentLog(now), Status.OPEN, "", ""));
        lastBug.put(reporter, now);
        save();
        return null;
    }

    public synchronized Bug close(long id, Status status, String by, String note) throws IOException {
        for (int i = 0; i < bugs.size(); i++) {
            if (bugs.get(i).id() == id) {
                Bug c = bugs.get(i).close(status, by, note == null ? "" : note);
                bugs.set(i, c);
                save();
                return c;
            }
        }
        throw new IllegalArgumentException("No such bug report");
    }

    /** Newest first; {@code status == null} for all. */
    public synchronized List<Bug> list(Status status, int max) {
        List<Bug> out = new ArrayList<>();
        for (int i = bugs.size() - 1; i >= 0 && out.size() < max; i--) {
            if (status == null || bugs.get(i).status() == status) {
                out.add(bugs.get(i));
            }
        }
        return out;
    }

    public static Json write(Json j, Bug b) {
        return j.field("id", b.id()).field("time", b.time()).field("reporter", b.reporter().toString())
                .field("reporterName", b.reporterName()).field("text", b.text()).field("where", b.where())
                .field("serverLog", b.serverLog()).field("status", b.status().name()).field("closedBy", b.closedBy()).field("note", b.note());
    }

    private void save() throws IOException {
        Json j = new Json().beginObject().name("bugs").beginArray();
        for (Bug b : bugs) {
            write(j.beginObject(), b).endObject();
        }
        j.endArray().endObject();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("bugs.json.tmp");
        Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String str(Map<?, ?> m, String k) {
        return m.get(k) instanceof String s ? s : "";
    }
}
