package com.hycon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * {@code /hycon debug}: one text file with what a coding agent needs to diagnose a live problem without
 * asking what happened in game. Version, memory and GC, worlds and who is in them, every thread that is
 * running or blocked (with its stack), and the warnings and errors from the end of the server log.
 *
 * <p>It costs nothing until an admin runs it. The work happens on its own short-lived thread, never on a
 * world thread or the shared scheduler, and runs are spaced {@value #COOLDOWN_MS} ms apart.
 */
public final class DebugDump {

    /** Stops repeated runs from stacking up thread dumps. */
    private static final long COOLDOWN_MS = 30_000;
    /** How much of the end of the server log is scanned for warnings and errors. */
    private static final int LOG_TAIL_BYTES = 2 * 1024 * 1024;
    /** Warning and error lines kept from that tail. */
    private static final int LOG_LINES = 300;
    private static final int STACK_DEPTH = 30, DUMPS_KEPT = 20;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);
    private static final AtomicLong lastRun = new AtomicLong();

    private DebugDump() {
    }

    /** Writes the dump off-thread and passes a one-line result (file path or reason) to {@code done}. */
    public static void run(Path dataFolder, String version, java.util.function.Supplier<String> state, HytaleLogger logger, Consumer<String> done) {
        long now = System.currentTimeMillis();
        long last = lastRun.get();
        if (now - last < COOLDOWN_MS || !lastRun.compareAndSet(last, now)) {
            done.accept("A debug dump was just written. Wait 30 seconds.");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Path file = write(dataFolder.resolve("debug"), version, state);
                done.accept("Debug dump written: " + file.toAbsolutePath());
            } catch (Exception e) {
                // A failed dump must never hurt the server; log it and tell the admin.
                logger.at(Level.WARNING).log("[DebugDump] failed: " + e);
                done.accept("Debug dump failed: " + e.getMessage());
            }
        }, "Hycon-DebugDump");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static Path write(Path folder, String version, java.util.function.Supplier<String> state) throws IOException {
        Files.createDirectories(folder);
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("Hycon debug dump ").append(Instant.now()).append(" (UTC)\n");
        out.append("Version: ").append(version).append('\n');
        appendJvm(out);
        try {
            appendWorlds(out);
        } catch (RuntimeException e) {
            out.append("(could not read worlds: ").append(e).append(")\n");
        }
        out.append("\n== Hycon ==\n");
        try {
            out.append(state.get());
        } catch (RuntimeException e) {
            out.append("(could not read Hycon state: ").append(e).append(")\n");
        }
        appendThreads(out);
        appendLogTail(out);
        Path file = folder.resolve("dump-" + FILE_TIME.format(Instant.now()) + ".txt");
        Files.writeString(file, out, StandardCharsets.UTF_8);
        prune(folder);
        return file;
    }

    private static void appendJvm(StringBuilder out) {
        Runtime rt = Runtime.getRuntime();
        long mb = 1024 * 1024;
        out.append("\n== JVM ==\n");
        out.append("Uptime: ").append(ManagementFactory.getRuntimeMXBean().getUptime() / 60_000).append(" min\n");
        out.append("Heap: ").append((rt.totalMemory() - rt.freeMemory()) / mb).append(" / ")
                .append(rt.maxMemory() / mb).append(" MB\n");
        out.append("Threads: ").append(ManagementFactory.getThreadMXBean().getThreadCount()).append('\n');
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            out.append("GC ").append(gc.getName()).append(": ").append(gc.getCollectionCount())
                    .append(" runs, ").append(gc.getCollectionTime()).append(" ms\n");
        }
    }

    /** Reads only snapshots the server already keeps; touches no entity or world state. */
    private static void appendWorlds(StringBuilder out) {
        out.append("\n== Worlds ==\n");
        Map<String, World> worlds = Universe.get().getWorlds();
        Map<UUID, String> names = new java.util.HashMap<>();
        for (World w : worlds.values()) {
            names.put(w.getWorldConfig().getUuid(), w.getName());
        }
        Map<String, Integer> counts = new java.util.TreeMap<>();
        StringBuilder players = new StringBuilder();
        for (PlayerRef p : Universe.get().getPlayers()) {
            String world = names.getOrDefault(p.getWorldUuid(), "(loading)");
            counts.merge(world, 1, Integer::sum);
            players.append("  ").append(p.getUsername()).append(" -> ").append(world).append('\n');
        }
        for (String name : new java.util.TreeSet<>(worlds.keySet())) {
            out.append(name).append(": ").append(counts.getOrDefault(name, 0)).append(" players\n");
        }
        out.append("\n== Players (").append(Universe.get().getPlayers().size()).append(") ==\n").append(players);
    }

    /** Running and blocked threads only: parked pool threads are noise. */
    private static void appendThreads(StringBuilder out) {
        out.append("\n== Busy threads ==\n");
        ThreadInfo[] infos = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false, STACK_DEPTH);
        for (ThreadInfo info : infos) {
            Thread.State state = info.getThreadState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING || info.getStackTrace().length == 0) {
                continue;
            }
            out.append('"').append(info.getThreadName()).append("\" ").append(state);
            if (info.getLockOwnerName() != null) {
                out.append(" waiting on ").append(info.getLockOwnerName());
            }
            out.append('\n');
            for (StackTraceElement e : info.getStackTrace()) {
                out.append("    at ").append(e).append('\n');
            }
        }
    }

    private static void appendLogTail(StringBuilder out) {
        out.append("\n== Recent warnings and errors ==\n");
        Path log = newestLog();
        if (log == null) {
            out.append("(no server log found)\n");
            return;
        }
        out.append(log.toAbsolutePath()).append('\n');
        Deque<String> kept = new ArrayDeque<>();
        boolean inError = false;
        for (String line : readTail(log).split("\n")) {
            boolean header = line.contains("WARN") || line.contains("SEVERE") || line.contains("ERROR");
            // Keep the stack trace lines that follow an error header.
            boolean trace = inError && (line.startsWith("\t") || line.startsWith("    at ") || line.startsWith("Caused by"));
            inError = header || trace;
            if (!header && !trace) {
                continue;
            }
            kept.add(line);
            if (kept.size() > LOG_LINES) {
                kept.removeFirst();
            }
        }
        kept.forEach(l -> out.append(l).append('\n'));
    }

    private static Path newestLog() {
        Path dir = Path.of("logs");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Opened read-only and shared, so the server keeps writing its log. */
    private static String readTail(Path log) {
        try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "r")) {
            long start = Math.max(0, f.length() - LOG_TAIL_BYTES);
            byte[] bytes = new byte[(int) (f.length() - start)];
            f.seek(start);
            f.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(could not read log: " + e.getMessage() + ")";
        }
    }

    private static void prune(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(p -> p.getFileName().toString().startsWith("dump-"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .skip(DUMPS_KEPT)
                    .forEach(p -> p.toFile().delete());
        }
    }
}
