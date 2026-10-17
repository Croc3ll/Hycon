package com.hycon.game;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's moderation history (their "dossier"): warnings, kicks, bans, unbans and staff notes,
 * with who did it and why. Kept in {@code moderation.log}, one JSON object per line, only ever
 * appended to, so a crash can't lose earlier entries. Read fully into memory on start; even a busy
 * server collects only a few thousand entries.
 */
public final class PlayerRecords {

    public enum Type { WARN, KICK, BAN, UNBAN, NOTE }

    /** {@code detail}: ban length ("7d", "permanent") or empty. */
    public record Entry(long time, UUID player, Type type, String by, String reason, String detail) {
    }

    private final Path file;
    private final Map<UUID, List<Entry>> byPlayer = new ConcurrentHashMap<>();
    private BufferedWriter out;

    public PlayerRecords(Path dataDir) {
        this.file = dataDir.resolve("moderation.log");
    }

    public synchronized void open() throws IOException {
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try {
                    Map<String, Object> m = JsonReader.object(line);
                    Type type = Type.valueOf((String) m.get("type"));
                    UUID uuid = UUID.fromString((String) m.get("player"));
                    add(new Entry(((Double) m.get("time")).longValue(), uuid, type, str(m, "by"), str(m, "reason"), str(m, "detail")));
                } catch (RuntimeException ignored) {
                    // skip a damaged line (e.g. cut off by a crash) and keep the rest
                }
            }
        }
        Files.createDirectories(file.getParent());
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

    public synchronized void record(UUID player, Type type, String by, String reason, String detail) {
        Entry e = new Entry(System.currentTimeMillis(), player, type, by, reason == null ? "" : reason, detail == null ? "" : detail);
        add(e);
        if (out == null) {
            return;
        }
        Json j = new Json().beginObject().field("time", e.time()).field("player", player.toString()).field("type", type.name())
                .field("by", by).field("reason", e.reason()).field("detail", e.detail()).endObject();
        try {
            out.write(j.toString());
            out.newLine();
            out.flush();
        } catch (IOException ignored) {
            // still in memory; a full disk must not block the action itself
        }
    }

    /** Newest first. */
    public List<Entry> history(UUID player) {
        List<Entry> list = byPlayer.get(player);
        if (list == null) {
            return List.of();
        }
        List<Entry> copy;
        synchronized (list) {
            copy = new ArrayList<>(list);
        }
        java.util.Collections.reverse(copy);
        return copy;
    }

    public int count(UUID player, Type type) {
        List<Entry> list = byPlayer.get(player);
        if (list == null) {
            return 0;
        }
        synchronized (list) {
            int n = 0;
            for (Entry e : list) {
                if (e.type() == type) {
                    n++;
                }
            }
            return n;
        }
    }

    private void add(Entry e) {
        List<Entry> list = byPlayer.computeIfAbsent(e.player(), k -> new ArrayList<>());
        synchronized (list) {
            list.add(e);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        return m.get(k) instanceof String s ? s : "";
    }
}
