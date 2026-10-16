package com.hycon.audit;

import com.hycon.web.Json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Who did what in the panel: {@code audit.log}, one JSON object per line, only ever appended to
 * (never rewritten, so entries can't be lost to a crash mid-write). Logins, failed logins, kicks,
 * bans, commands and account changes all land here. The newest entries are also kept in memory
 * for the panel's Audit tab.
 */
public final class AuditLog {

    public record Entry(long time, String admin, String ip, String action, String target, String detail, boolean ok) {
    }

    private static final int KEEP = 500;

    private final Path file;
    private final Deque<Entry> recent = new ArrayDeque<>(KEEP);
    private BufferedWriter out;

    public AuditLog(Path dataDir) {
        this.file = dataDir.resolve("audit.log");
    }

    public synchronized void open() throws IOException {
        Files.createDirectories(file.getParent());
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void close() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
            // shutting down; nothing more to do
        }
        out = null;
    }

    public synchronized void record(String admin, String ip, String action, String target, String detail, boolean ok) {
        Entry e = new Entry(System.currentTimeMillis(), admin, ip, action, target, detail, ok);
        if (recent.size() == KEEP) {
            recent.pollFirst();
        }
        recent.addLast(e);
        if (out == null) {
            return;
        }
        Json j = new Json().beginObject().field("time", Instant.ofEpochMilli(e.time).toString());
        write(j, e).endObject();
        try {
            out.write(j.toString());
            out.newLine();
            out.flush(); // an audit entry that only lives in a buffer is no use after a crash
        } catch (IOException ignored) {
            // a full disk must not stop the admin action itself; the entry is still in memory
        }
    }

    /** Newest first. */
    public synchronized List<Entry> recent(int max) {
        List<Entry> list = new ArrayList<>(Math.min(max, recent.size()));
        var it = recent.descendingIterator();
        while (it.hasNext() && list.size() < max) {
            list.add(it.next());
        }
        return list;
    }

    public static Json write(Json j, Entry e) {
        return j.field("admin", e.admin).field("ip", e.ip).field("action", e.action).field("target", e.target)
                .field("detail", e.detail).field("ok", e.ok);
    }
}
