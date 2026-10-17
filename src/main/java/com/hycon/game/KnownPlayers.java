package com.hycon.game;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every player who has been on the server: UUID (Hytale's permanent id for the account), last known
 * name, first and last time seen and last world. Lets the panel find and ban players who are offline.
 * Saved to {@code players.json} only when something changed.
 *
 * <p>Joins and leaves are recorded live. On start, the server's own saved players
 * ({@code universe/players/<uuid>.json}) are imported once, so players from before Hycon was
 * installed can be found too; for those, "first seen" is unknown and "last seen" is the file's date.
 */
public final class KnownPlayers {

    /**
     * {@code playtimeMs}, {@code sessions} and {@code days} count only time Hycon was installed.
     * {@code days}: the last {@link #KEEP_DAYS} days (epoch day numbers, oldest first) the player was on,
     * which is what "who comes back" in Analytics is built from.
     */
    public record Info(UUID uuid, String name, long firstSeen, long lastSeen, String world, long playtimeMs, int sessions, int[] days) {
        /** Days played on or after {@code fromDay}. */
        public int daysSince(long fromDay) {
            int n = 0;
            for (int d : days) {
                if (d >= fromDay) {
                    n++;
                }
            }
            return n;
        }
    }

    public static final int KEEP_DAYS = 60;
    private static final int[] NO_DAYS = new int[0];

    /** A player file holds the whole inventory; the fields we need are near the start or in small blocks. */
    private static final int MAX_IMPORT_BYTES = 4 * 1024 * 1024;
    private static final Pattern NAMEPLATE = Pattern.compile("\"Nameplate\"\\s*:\\s*\\{\\s*\"Text\"\\s*:\\s*\"([^\"]{1,64})\"");
    private static final Pattern WORLD = Pattern.compile("\"PlayerData\"\\s*:\\s*\\{[^{}]*?\"World\"\\s*:\\s*\"([^\"]{1,64})\"");

    private final Path file;
    private final Map<UUID, Info> players = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    /** Join time of everyone online, for playtime. Not saved: a crash loses only the current sessions. */
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public KnownPlayers(Path dataDir) {
        this.file = dataDir.resolve("players.json");
    }

    public void load() {
        try {
            if (Files.exists(file)) {
                for (Map.Entry<String, Object> e : JsonReader.object(Files.readString(file, StandardCharsets.UTF_8)).entrySet()) {
                    UUID uuid = UUID.fromString(e.getKey());
                    if (e.getValue() instanceof String n) {
                        put(new Info(uuid, n, 0, 0, "", 0, 0, NO_DAYS)); // file from Hycon 0.1.0: names only
                    } else if (e.getValue() instanceof Map<?, ?> m && m.get("name") instanceof String n) {
                        put(new Info(uuid, n, num(m.get("first")), num(m.get("last")), m.get("world") instanceof String w ? w : "",
                                num(m.get("play")), (int) num(m.get("sessions")), days(m.get("days"))));
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // only a directory of names; starting empty is fine
        }
        dirty = false;
    }

    /**
     * Adds players the server saved but Hycon hasn't seen yet. Runs off the game threads; reading
     * hundreds of player files takes a moment and nothing waits for it.
     */
    public void importSaved(Path playersDir) {
        if (!Files.isDirectory(playersDir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(playersDir, "*.json")) {
            for (Path f : files) {
                String base = f.getFileName().toString();
                UUID uuid;
                try {
                    uuid = UUID.fromString(base.substring(0, base.length() - ".json".length()));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (players.containsKey(uuid)) {
                    continue;
                }
                String text = head(f);
                Matcher name = NAMEPLATE.matcher(text);
                Matcher world = WORLD.matcher(text);
                long last = Files.getLastModifiedTime(f).toMillis();
                put(new Info(uuid, name.find() ? name.group(1) : uuid.toString(), 0, last, world.find() ? world.group(1) : "", 0, 0, NO_DAYS));
                dirty = true;
            }
        } catch (IOException ignored) {
            // partial import is fine; the rest are added when they next join
        }
        saveIfChanged();
    }

    /** Seen online: updates name, last seen, world; sets first seen if new. */
    public void seen(UUID uuid, String name, String world) {
        update(uuid, name, world, 0, 0);
    }

    /** A new session starts. */
    public void joined(UUID uuid, String name, String world) {
        joinedAt.put(uuid, System.currentTimeMillis());
        update(uuid, name, world, 0, 1);
    }

    /** A session ends; its length is added to the player's playtime. */
    public void left(UUID uuid, String name) {
        Long start = joinedAt.remove(uuid);
        update(uuid, name, null, start == null ? 0 : System.currentTimeMillis() - start, 0);
    }

    /** Server stopping: close every open session so its playtime is kept. */
    public void leaveAll() {
        for (UUID u : List.copyOf(joinedAt.keySet())) {
            Info i = players.get(u);
            if (i != null) {
                left(u, i.name());
            }
        }
    }

    /** Playtime including the current session, for live tables. */
    public long playtimeNow(Info i) {
        Long start = joinedAt.get(i.uuid());
        return i.playtimeMs() + (start == null ? 0 : System.currentTimeMillis() - start);
    }

    /** Everyone, unsorted (callers sort for their table). */
    public List<Info> all() {
        return new ArrayList<>(players.values());
    }

    private synchronized void update(UUID uuid, String name, String world, long addPlay, int addSessions) {
        long now = System.currentTimeMillis();
        Info old = players.get(uuid);
        long first = old == null || old.firstSeen() == 0 ? now : old.firstSeen();
        String w = world == null || world.isEmpty() ? (old == null ? "" : old.world()) : world;
        long play = (old == null ? 0 : old.playtimeMs()) + addPlay;
        int sessions = (old == null ? 0 : old.sessions()) + addSessions;
        put(new Info(uuid, name, first, now, w, play, sessions, addDay(old == null ? NO_DAYS : old.days(), (int) (now / 86_400_000L))));
        dirty = true;
    }

    public Info get(UUID uuid) {
        return players.get(uuid);
    }

    public String name(UUID uuid) {
        Info i = players.get(uuid);
        return i == null || i.name().equals(uuid.toString()) ? null : i.name();
    }

    public UUID uuid(String name) {
        return byName.get(name.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return players.size();
    }

    /**
     * Name contains the text, or the UUID starts with it (any case). Players for whom
     * {@code online} is true come first, then the most recently seen.
     */
    public List<Info> search(String query, Predicate<UUID> online, int max) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Info> out = new ArrayList<>();
        for (Info i : players.values()) {
            if (q.isEmpty() || i.name().toLowerCase(Locale.ROOT).contains(q) || i.uuid().toString().startsWith(q)) {
                out.add(i);
            }
        }
        out.sort(Comparator.<Info, Boolean>comparing(i -> !online.test(i.uuid())).thenComparing(Info::lastSeen, Comparator.reverseOrder()));
        return out.size() > max ? out.subList(0, max) : out;
    }

    public synchronized void saveIfChanged() {
        if (!dirty) {
            return;
        }
        dirty = false;
        Json j = new Json().beginObject();
        players.forEach((u, i) -> {
            j.name(u.toString()).beginObject().field("name", i.name()).field("first", i.firstSeen())
                    .field("last", i.lastSeen()).field("world", i.world()).field("play", i.playtimeMs()).field("sessions", i.sessions())
                    .name("days").beginArray();
            for (int d : i.days()) {
                j.value(d);
            }
            j.endArray().endObject();
        });
        j.endObject();
        try {
            Path tmp = file.resolveSibling("players.json.tmp");
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            dirty = true; // try again next time
        }
    }

    private void put(Info i) {
        Info old = players.put(i.uuid(), i);
        if (old != null) {
            byName.remove(old.name().toLowerCase(Locale.ROOT), i.uuid());
        }
        byName.put(i.name().toLowerCase(Locale.ROOT), i.uuid());
    }

    private static String head(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            return new String(in.readNBytes(MAX_IMPORT_BYTES), StandardCharsets.UTF_8);
        }
    }

    /** Adds {@code today} (if new) and keeps only the last {@link #KEEP_DAYS} days. */
    private static int[] addDay(int[] days, int today) {
        if (days.length > 0 && days[days.length - 1] == today) {
            return days;
        }
        int start = 0;
        while (start < days.length && days[start] <= today - KEEP_DAYS) {
            start++;
        }
        int[] out = java.util.Arrays.copyOfRange(days, start, days.length + 1);
        out[out.length - 1] = today;
        return out;
    }

    private static int[] days(Object o) {
        if (!(o instanceof List<?> l)) {
            return NO_DAYS;
        }
        int[] out = new int[l.size()];
        int n = 0;
        for (Object x : l) {
            if (x instanceof Double d) {
                out[n++] = d.intValue();
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static long num(Object o) {
        return o instanceof Double d ? d.longValue() : 0;
    }
}
