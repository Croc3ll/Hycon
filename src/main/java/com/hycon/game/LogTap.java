package com.hycon.game;

import com.hypixel.hytale.logger.backend.HytaleLoggerBackend;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;

/**
 * Copies every server log record into a ring buffer for the panel's live console.
 *
 * <p>Hytale's logger hands records to "subscriber" lists by calling {@code add} on them. A plain list
 * would grow forever (and a copy-on-write list copies itself on every add), so this one overrides
 * {@code add} to drop the record into the ring and stores nothing itself. Formatting is left until a
 * browser asks, so logging stays cheap on the threads that log.
 */
public final class LogTap extends CopyOnWriteArrayList<LogRecord> {

    public record Line(long seq, String text) {
    }

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final SimpleFormatter FORMAT = new SimpleFormatter();

    private final transient LogRecord[] ring;
    private final transient String[] extra;
    /** Sequence number of the next record; record {@code n} sits at {@code ring[n % length]}. */
    private long next;

    public LogTap(int size) {
        this.ring = new LogRecord[size];
        this.extra = new String[size];
    }

    public void install() {
        HytaleLoggerBackend.subscribe(this);
    }

    public void uninstall() {
        HytaleLoggerBackend.unsubscribe(this);
    }

    @Override
    public boolean add(LogRecord r) {
        synchronized (ring) {
            int i = (int) (next++ % ring.length);
            ring[i] = r;
            extra[i] = null;
        }
        return true;
    }

    /** Adds a line that didn't come from the logger (e.g. a panel command's own output). */
    public void addText(String text) {
        synchronized (ring) {
            int i = (int) (next++ % ring.length);
            ring[i] = null;
            extra[i] = text;
        }
    }

    /** Lines with a sequence number above {@code after}, oldest first, at most {@code max}. */
    public List<Line> since(long after, int max) {
        List<LogRecord> recs = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        long first;
        synchronized (ring) {
            first = Math.max(after + 1, Math.max(0, next - ring.length));
            first = Math.max(first, next - max);
            for (long s = first; s < next; s++) {
                int i = (int) (s % ring.length);
                recs.add(ring[i]);
                texts.add(extra[i]);
            }
        }
        List<Line> out = new ArrayList<>(recs.size());
        for (int k = 0; k < recs.size(); k++) {
            LogRecord r = recs.get(k);
            out.add(new Line(first + k, r == null ? texts.get(k) : format(r)));
        }
        return out;
    }

    /** Every buffered line logged at or after {@code millis} (lines Hycon added itself are kept), oldest first. */
    public List<String> sinceTime(long millis) {
        List<LogRecord> recs = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        synchronized (ring) {
            for (long s = Math.max(0, next - ring.length); s < next; s++) {
                int i = (int) (s % ring.length);
                if (ring[i] == null || ring[i].getMillis() >= millis) {
                    recs.add(ring[i]);
                    texts.add(extra[i]);
                }
            }
        }
        List<String> out = new ArrayList<>(recs.size());
        for (int k = 0; k < recs.size(); k++) {
            out.add(recs.get(k) == null ? texts.get(k) : format(recs.get(k)));
        }
        return out;
    }

    public long latest() {
        synchronized (ring) {
            return next - 1;
        }
    }

    private static String format(LogRecord r) {
        String msg = FORMAT.formatMessage(r);
        if (r.getThrown() != null) {
            msg += "  (" + r.getThrown() + ")";
        }
        String name = r.getLoggerName();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String src = name == null ? "" : name.substring(dot + 1);
        return TIME.format(Instant.ofEpochMilli(r.getMillis())) + " [" + r.getLevel() + "] [" + src + "] "
                + ANSI.matcher(msg).replaceAll("");
    }

    // The logger checks "contains" before subscribing, which uses equals(); every empty list equals
    // every other one, so identity is required or another plugin's empty subscriber would block ours.
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
