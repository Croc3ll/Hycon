package com.hycon.game;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.Universe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Daily restarts with countdown warnings in chat, a one-off "restart in N minutes", and announcements
 * that repeat on their own timer. Kept in {@code schedule.json}. Runs on its own thread, never the
 * game's scheduler, and checks once a second (a few comparisons, nothing more).
 */
public final class Schedule {

    public record Announcement(String text, int everyMinutes) {
    }

    /** {@code restartTimes} as "HH:mm" in the server's time zone. */
    public record Settings(List<String> restartTimes, List<Integer> warnMinutes, List<Announcement> announcements) {
    }

    private static final Pattern TIME = Pattern.compile("([01]?\\d|2[0-3]):[0-5]\\d");
    private static final int MAX_TIMES = 12;
    private static final int MAX_WARNINGS = 10;
    private static final int MAX_ANNOUNCEMENTS = 20;
    private static final int MAX_TEXT = 300;
    /** Last warnings before a restart, in seconds, on top of the minute warnings. */
    private static final int[] FINAL_SECONDS = {30, 10};
    /** After a restart is started, wait this long before looking for the next one (the server is going down). */
    private static final long AFTER_RESTART_MS = TimeUnit.MINUTES.toMillis(5);
    private static final Settings DEFAULTS = new Settings(List.of(), List.of(15, 5, 1), List.of());

    private final Path file;
    private final HytaleLogger log;
    private final Runnable restart;
    private final Consumer<String> note;
    private volatile Settings settings = DEFAULTS;
    private volatile long oneOffAt;
    private long target = -1;
    private long pausedUntil;
    private final Set<Integer> warned = new HashSet<>();
    private final Map<Integer, Long> lastAnnounced = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;

    /**
     * @param restart restarts the server (through AMP, or a stop the host turns back on)
     * @param note    a line for the panel's console and audit trail
     */
    public Schedule(Path dataDir, HytaleLogger log, Runnable restart, Consumer<String> note) {
        this.file = dataDir.resolve("schedule.json");
        this.log = log;
        this.restart = restart;
        this.note = note;
    }

    public synchronized void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        Map<String, Object> m = JsonReader.object(Files.readString(file, StandardCharsets.UTF_8));
        List<String> times = new ArrayList<>();
        List<Integer> warns = new ArrayList<>();
        List<Announcement> ann = new ArrayList<>();
        if (m.get("restartTimes") instanceof List<?> l) {
            l.forEach(o -> { if (o instanceof String s) times.add(s); });
        }
        if (m.get("warnMinutes") instanceof List<?> l) {
            l.forEach(o -> { if (o instanceof Double d) warns.add(d.intValue()); });
        }
        if (m.get("announcements") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> a && a.get("text") instanceof String t && a.get("everyMinutes") instanceof Double d) {
                    ann.add(new Announcement(t, d.intValue()));
                }
            }
        }
        settings = check(new Settings(times, warns, ann));
    }

    public void start() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < MAX_ANNOUNCEMENTS; i++) {
            lastAnnounced.put(i, now); // the first of each comes one interval after start, not all at once
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Hycon-Schedule");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tickSafely, 5, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public Settings settings() {
        return settings;
    }

    /** Replaces the settings after checking every value. */
    public synchronized void save(Settings next) throws IOException {
        Settings s = check(next);
        Json j = new Json().beginObject().name("restartTimes").beginArray();
        s.restartTimes().forEach(j::value);
        j.endArray().name("warnMinutes").beginArray();
        s.warnMinutes().forEach(j::value);
        j.endArray().name("announcements").beginArray();
        for (Announcement a : s.announcements()) {
            j.beginObject().field("text", a.text()).field("everyMinutes", a.everyMinutes()).endObject();
        }
        j.endArray().endObject();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("schedule.json.tmp");
        Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        settings = s;
        long now = System.currentTimeMillis();
        for (int i = 0; i < MAX_ANNOUNCEMENTS; i++) {
            lastAnnounced.put(i, now); // edited announcements start their timers over, so saving never sends a burst
        }
    }

    /** A restart {@code minutes} from now, on top of the daily ones. */
    public void restartIn(int minutes) {
        oneOffAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes);
    }

    public void cancelOneOff() {
        oneOffAt = 0;
    }

    public long oneOffAt() {
        return oneOffAt;
    }

    /** When the next restart happens (one-off or daily), or -1 when none is planned. */
    public long nextRestart() {
        long now = System.currentTimeMillis();
        long daily = nextDaily(now);
        long one = oneOffAt > now ? oneOffAt : -1;
        if (daily < 0) {
            return one;
        }
        return one < 0 ? daily : Math.min(one, daily);
    }

    private long nextDaily(long now) {
        ZoneId zone = ZoneId.systemDefault();
        long best = -1;
        for (String t : settings.restartTimes()) {
            LocalTime time = LocalTime.parse(t.length() == 4 ? "0" + t : t);
            ZonedDateTime at = ZonedDateTime.of(LocalDate.now(zone), time, zone);
            long ms = at.toInstant().toEpochMilli();
            if (ms <= now) {
                ms = at.plusDays(1).toInstant().toEpochMilli();
            }
            if (best < 0 || ms < best) {
                best = ms;
            }
        }
        return best;
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            // one bad tick must not stop restarts and announcements for good
            ((HytaleLogger.Api) log.at(Level.WARNING).withCause(e)).log("Hycon schedule tick failed");
        }
    }

    private synchronized void tick() {
        long now = System.currentTimeMillis();
        announce(now);
        if (now < pausedUntil) {
            return;
        }
        long next = nextRestart();
        if (next != target) {
            target = next;
            warned.clear();
            // Warnings already long past (Hycon started 3 minutes before a restart) are skipped, not sent late in a burst.
            for (int mark : marks()) {
                if (target > 0 && mark * 1000L > target - now + 1500) {
                    warned.add(mark);
                }
            }
        }
        if (target < 0) {
            return;
        }
        long left = target - now;
        for (int mark : marks()) {
            if (left <= mark * 1000L && warned.add(mark)) {
                say("Server restarts in " + words(mark) + ".");
            }
        }
        if (left <= 0) {
            say("Server is restarting now.");
            note.accept("Scheduled restart");
            oneOffAt = 0;
            target = -1;
            pausedUntil = now + AFTER_RESTART_MS;
            restart.run();
        }
    }

    /** Warning points in seconds before a restart, biggest first. */
    private List<Integer> marks() {
        List<Integer> out = new ArrayList<>();
        settings.warnMinutes().forEach(m -> out.add(m * 60));
        for (int s : FINAL_SECONDS) {
            out.add(s);
        }
        out.sort((a, b) -> b - a);
        return out;
    }

    private void announce(long now) {
        List<Announcement> list = settings.announcements();
        for (int i = 0; i < list.size(); i++) {
            Announcement a = list.get(i);
            long last = lastAnnounced.getOrDefault(i, now);
            if (now - last >= TimeUnit.MINUTES.toMillis(a.everyMinutes())) {
                lastAnnounced.put(i, now);
                Universe.get().sendMessage(Message.raw("[Announcement] ").color("#FFAA00").bold(true).insert(Message.raw(a.text()).color("#FFFFFF")));
            }
        }
    }

    private static void say(String text) {
        Universe.get().sendMessage(Message.raw("[Restart] ").color("#FF5555").bold(true).insert(Message.raw(text).color("#FFFFFF")));
    }

    private static String words(int seconds) {
        if (seconds < 60) {
            return seconds + " seconds";
        }
        int min = seconds / 60;
        return min + (min == 1 ? " minute" : " minutes");
    }

    private static Settings check(Settings s) {
        List<String> times = new ArrayList<>();
        for (String t : s.restartTimes()) {
            String v = t.strip();
            if (!TIME.matcher(v).matches()) {
                throw new IllegalArgumentException("Restart times look like 04:00 or 16:30 (" + v + ")");
            }
            if (!times.contains(v)) {
                times.add(v);
            }
        }
        if (times.size() > MAX_TIMES) {
            throw new IllegalArgumentException("At most " + MAX_TIMES + " restart times");
        }
        List<Integer> warns = new ArrayList<>(new HashSet<>(s.warnMinutes()));
        if (warns.size() > MAX_WARNINGS || warns.stream().anyMatch(w -> w < 1 || w > 120)) {
            throw new IllegalArgumentException("Warnings are 1-120 minutes before, at most " + MAX_WARNINGS);
        }
        warns.sort((a, b) -> b - a);
        if (s.announcements().size() > MAX_ANNOUNCEMENTS) {
            throw new IllegalArgumentException("At most " + MAX_ANNOUNCEMENTS + " announcements");
        }
        List<Announcement> ann = new ArrayList<>();
        for (Announcement a : s.announcements()) {
            String text = a.text().strip().replaceAll("[\\r\\n\\t]+", " ");
            if (text.isEmpty() || text.length() > MAX_TEXT) {
                throw new IllegalArgumentException("Announcements are 1-" + MAX_TEXT + " characters");
            }
            if (a.everyMinutes() < 1 || a.everyMinutes() > 1440) {
                throw new IllegalArgumentException("Announcements repeat every 1-1440 minutes");
            }
            ann.add(new Announcement(text, a.everyMinutes()));
        }
        return new Settings(List.copyOf(times), List.copyOf(warns), List.copyOf(ann));
    }
}
