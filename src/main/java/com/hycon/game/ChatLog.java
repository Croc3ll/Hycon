package com.hycon.game;

import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Every chat line players send, for the server's Chat tab and each player's profile. Kept
 * {@link PingLog#KEEP_DAYS} days in {@code chat.log} ({@code time<TAB>uuid<TAB>name<TAB>text}).
 * Only the newest {@link #MAX_LINES} stay in memory, so a busy server can't grow it without end.
 */
public final class ChatLog {

    public record Line(long time, UUID player, String name, String text) {
    }

    private static final long KEEP_MS = TimeUnit.DAYS.toMillis(PingLog.KEEP_DAYS);
    /** Lines held in memory (and kept in the file across restarts). */
    private static final int MAX_LINES = 50_000;
    /** Longest chat line stored; Hytale's own limit is lower, this only guards the file. */
    private static final int MAX_TEXT = 500;

    private final Path file;
    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private BufferedWriter out;

    public ChatLog(Path dataDir) {
        this.file = dataDir.resolve("chat.log");
    }

    public synchronized void open() throws IOException {
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        boolean dropped = false;
        if (Files.exists(file)) {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = raw.split("\t", 4);
                try {
                    Line l = new Line(Long.parseLong(p[0]), UUID.fromString(p[1]), p[2], p[3]);
                    if (l.time() >= cutoff) {
                        dropped |= add(l);
                    } else {
                        dropped = true;
                    }
                } catch (RuntimeException e) {
                    dropped = true;
                }
            }
        }
        Files.createDirectories(file.getParent());
        if (dropped) {
            Files.write(file, lines.stream().map(ChatLog::line).toList(), StandardCharsets.UTF_8);
        }
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void close() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
            // shutting down
        }
        out = null;
    }

    /** Hears a chat line. Cancelled lines (muted players, filters) were never seen, so they are skipped. */
    public void onChat(PlayerChatEvent e) {
        if (e.isCancelled() || e.getSender() == null) {
            return;
        }
        record(new Line(System.currentTimeMillis(), e.getSender().getUuid(), clean(e.getSender().getUsername()), clean(e.getContent())));
    }

    private synchronized void record(Line l) {
        add(l);
        if (out != null) {
            try {
                out.write(line(l));
                out.newLine();
                out.flush();
            } catch (IOException ignored) {
                // still shown from memory
            }
        }
    }

    /** True when the oldest line had to go to make room. */
    private boolean add(Line l) {
        lines.addLast(l);
        if (lines.size() <= MAX_LINES) {
            return false;
        }
        lines.removeFirst();
        return true;
    }

    /**
     * Up to {@code limit} lines older than {@code before}, newest first. {@code player} (or null for
     * everyone) and {@code query} (a word in the text or name, or null) narrow it down.
     */
    public synchronized List<Line> find(UUID player, String query, long before, int limit) {
        String q = query == null || query.isBlank() ? null : query.toLowerCase(Locale.ROOT);
        List<Line> list = new ArrayList<>();
        for (Iterator<Line> it = lines.descendingIterator(); it.hasNext() && list.size() < limit; ) {
            Line l = it.next();
            if (l.time() >= before || (player != null && !l.player().equals(player))) {
                continue;
            }
            if (q != null && !l.text().toLowerCase(Locale.ROOT).contains(q) && !l.name().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            list.add(l);
        }
        return list;
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        return flat.length() > MAX_TEXT ? flat.substring(0, MAX_TEXT) : flat;
    }

    private static String line(Line l) {
        return l.time() + "\t" + l.player() + "\t" + l.name() + "\t" + l.text();
    }
}
