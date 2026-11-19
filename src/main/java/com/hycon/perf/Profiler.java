package com.hycon.perf;

import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.protocol.io.PacketStatsRecorder;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hycon.perf.PerfData.Late;
import com.hycon.perf.PerfData.Minute;
import com.hycon.perf.PerfData.Spike;
import com.hycon.perf.PerfData.SystemTime;
import com.hycon.perf.PerfData.TaskRun;
import com.hycon.perf.PerfData.Traffic;
import com.hycon.perf.PerfData.WorldMinute;

import javax.annotation.Nonnull;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.logging.Level;

/**
 * The always-on profiler. It costs well under 1% because it mostly reads numbers Hytale already
 * keeps: each world's tick length and each ECS system's time per tick. On top of that one daemon
 * thread looks at what each world thread and the shared scheduler thread are running about 20
 * times a second, and tells which plugin it is from the class loader name on each stack frame.
 * <p>
 * When a tick runs past {@code LagSpikeMs} it looks every 5 ms instead until the tick ends, and
 * saves that as a spike. It slows its own sampling down if sampling ever costs over 0.5%.
 * <p>
 * Threads: the sampler thread owns the current minute's counts and the rollup. World threads only
 * write their own {@link Lane}'s tick numbers; readers get finished minutes under a lock.
 */
public final class Profiler {

    /** How often stacks are read normally, and the slowest the self-limiter goes. */
    private static final int BASE_SAMPLE_MS = 50, SLOWEST_SAMPLE_MS = 400;
    /** How often stacks are read while a tick is running long. */
    private static final int SPIKE_SAMPLE_MS = 5;
    /** A spike stops being sampled fast after this long, so a frozen world can't keep us busy. */
    private static final long SPIKE_FAST_LIMIT_NS = TimeUnit.SECONDS.toNanos(10);
    /** Sampling may cost at most this share of the time; it backs off when it does. */
    private static final double MAX_COST = 0.005, LOW_COST = 0.001;
    /** Minutes kept in memory (a day) and spikes kept. */
    private static final int KEEP_MINUTES = 24 * 60, KEEP_SPIKES = 100;
    /** Methods kept per minute, so a busy minute can't grow without bound. */
    private static final int KEEP_WHERE = 40;
    /** Frames checked for file, network or database calls. */
    private static final int IO_DEPTH = 14;
    private static final String[] IO_CLASSES = {"java.io.File", "java.io.RandomAccess", "sun.nio.ch.", "sun.nio.fs.",
            "java.nio.file.Files", "java.net.", "java.sql.", "org.sqlite.", "com.mysql.", "org.mariadb.", "org.h2.",
            "java.net.http."};
    /** A scheduler task this late is logged with the task that held it up. */
    private static final double LATE_LOG_MS = 20;
    /** Late tasks and scheduler tasks kept per minute. */
    private static final int KEEP_LATES = 20, KEEP_TASKS = 15;
    /** A phase seen in this share of a spike's samples is listed with it. */
    private static final double PHASE_SHARE = 0.2;

    private final HytaleLogger log;
    private final IntSupplier spikeMs;
    private final Consumer<Minute> onMinute;
    private final Consumer<Spike> onSpike;
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
    private final Lane scheduler = new Lane("Scheduler", false, TimeUnit.SECONDS.toNanos(1));
    private final ArrayDeque<Minute> minutes = new ArrayDeque<>();
    private final ArrayDeque<Spike> spikes = new ArrayDeque<>();

    private volatile boolean running;
    private Thread thread;
    private ScheduledFuture<?> probe;

    // Sampler thread only.
    private int sampleMs = BASE_SAMPLE_MS;
    private long minuteStart = System.currentTimeMillis();
    private Counts cur = new Counts();
    private long costNs, costWindowStart = System.nanoTime();
    private long lastGcMs = gcMillis();
    private final Map<String, double[]> tasks = new HashMap<>(); // longest, busy, runs; this minute
    private final Map<UUID, long[]> lastTraffic = new HashMap<>();
    /** The scheduler task the sampler last saw running, for blaming late tasks. Written by the sampler. */
    private volatile String lastTask;
    private volatile long lastTaskNs;
    // Written by the probe on the scheduler thread (cheap, no IO), drained by the sampler.
    private final ConcurrentLinkedQueue<Late> lates = new ConcurrentLinkedQueue<>();
    // Written by the JMX notification thread.
    private final AtomicLong gcMaxPause = new AtomicLong();

    public Profiler(HytaleLogger log, IntSupplier spikeMs, Consumer<Minute> onMinute, Consumer<Spike> onSpike) {
        this.log = log;
        this.spikeMs = spikeMs;
        this.onMinute = onMinute;
        this.onSpike = onSpike;
    }

    /** The ECS system that marks each world tick. Register it once with the entity store registry. */
    public TickingSystem<EntityStore> heartbeat() {
        return new Heartbeat();
    }

    public void start() {
        running = true;
        // The probe both finds the scheduler thread and measures how late its tasks run.
        long start = System.nanoTime();
        long period = scheduler.intervalNs;
        long[] n = {0};
        probe = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            n[0]++;
            scheduler.thread = Thread.currentThread();
            long late = Math.max(0, now - (start + n[0] * period));
            scheduler.late(late);
            if (late / 1e6 >= LATE_LOG_MS && lates.size() < 200) {
                String blame = lastTask;
                boolean recent = blame != null && now - lastTaskNs < late + TimeUnit.MILLISECONDS.toNanos(SLOWEST_SAMPLE_MS * 2L);
                lates.add(new Late(System.currentTimeMillis(), late / 1e6, recent ? blame : "unknown (between samples)"));
            }
            scheduler.lastBeat = now;
        }, period, period, TimeUnit.NANOSECONDS);
        listenForGcPauses();
        thread = Thread.ofPlatform().name("Hycon-Profiler").daemon().priority(Thread.NORM_PRIORITY + 1).start(this::loop);
    }

    public void stop() {
        running = false;
        if (probe != null) {
            probe.cancel(false);
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    public boolean running() {
        return running && thread != null && thread.isAlive();
    }

    /** Finished minutes since {@code sinceMillis}, oldest first. */
    public List<Minute> minutes(long sinceMillis) {
        synchronized (minutes) {
            return minutes.stream().filter(m -> m.time() >= sinceMillis).toList();
        }
    }

    /** Recent spikes, newest first. */
    public List<Spike> spikes() {
        synchronized (spikes) {
            List<Spike> out = new ArrayList<>(spikes);
            java.util.Collections.reverse(out);
            return out;
        }
    }

    /** How often stacks are read right now, in ms (higher when the self-limiter backed off). */
    public int sampleEveryMs() {
        return sampleMs;
    }

    private void loop() {
        while (running) {
            boolean fast = false;
            try {
                long t0 = System.nanoTime();
                fast = sampleAll(t0);
                costNs += System.nanoTime() - t0;
                if (System.currentTimeMillis() - minuteStart >= 60_000) {
                    rollup();
                }
                governor();
            } catch (RuntimeException e) {
                // One bad sample must never stop the profiler.
                log.at(Level.FINE).log("Hycon profiler sample failed: %s", e);
            }
            try {
                Thread.sleep(fast ? SPIKE_SAMPLE_MS : sampleMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Samples every lane once. Returns true when a lane is in a long tick, to sample faster. */
    private boolean sampleAll(long now) {
        boolean fast = false;
        for (Lane lane : lanes.values()) {
            fast |= sample(lane, now);
        }
        fast |= sample(scheduler, now);
        return fast;
    }

    private boolean sample(Lane lane, long now) {
        Thread t = lane.thread;
        if (t == null || !t.isAlive()) {
            return false;
        }
        double ms = lane.lastSample == 0 ? sampleMs : Math.min(SLOWEST_SAMPLE_MS, (now - lane.lastSample) / 1e6);
        lane.lastSample = now;
        long beat = lane.lastBeat;
        long limit = lane.intervalNs + TimeUnit.MILLISECONDS.toNanos(spikeMs.getAsInt());
        boolean stalled = beat != 0 && now - beat > limit;
        if (lane.spike != null && (!stalled || beat != lane.spike.fromBeat)) {
            finishSpike(lane);
        }
        if (stalled && lane.spike == null && beat != lane.quietBeat) {
            lane.spike = new SpikeCounts(beat, gcMillis());
        }

        StackTraceElement[] stack = t.getStackTrace();
        boolean busy = lane.world ? busyWorld(stack) : busyScheduler(stack);
        if (lane.world) {
            cur.worldMs += ms;
        } else {
            cur.schedMs += ms;
        }
        if (!lane.world) {
            trackTask(lane, busy ? taskOf(stack) : null, now, ms);
        }
        if (!busy) {
            if (lane.spike != null) {
                // No beat but not working either (a paused world): not a long tick, so drop it.
                lane.quietBeat = lane.spike.fromBeat;
                lane.spike = null;
            }
            return false;
        }
        String owner = Owners.of(stack);
        String where = owner + "\t" + Owners.where(stack, owner);
        if (lane.world) {
            cur.worldBusyMs += ms;
            cur.owners.merge(owner, ms, Double::sum);
        } else {
            cur.schedBusyMs += ms;
            cur.schedOwners.merge(owner, ms, Double::sum);
        }
        cur.where.merge(where, ms, Double::sum);
        if (lane.world) {
            cur.worldOwners.merge(lane.name + "\t" + owner, ms, Double::sum);
        }
        String ioCaller = ioCaller(stack);
        if (ioCaller != null) {
            cur.io.merge(owner, ms, Double::sum);
            cur.ioWhere.merge(owner + "\t" + ioCaller, ms, Double::sum);
        }
        SpikeCounts s = lane.spike;
        if (s != null) {
            s.owners.merge(owner, 1.0, Double::sum);
            s.where.merge(where, 1.0, Double::sum);
            String sys = lane.world ? systemOf(stack) : taskOf(stack);
            if (sys != null) {
                s.systems.merge(sys, 1.0, Double::sum);
            }
            for (String phase : phasesOf(stack)) {
                s.phases.merge(phase, 1.0, Double::sum);
            }
            s.samples++;
            return now - s.startNs < SPIKE_FAST_LIMIT_NS;
        }
        return false;
    }

    /** A world thread between ticks sleeps or spins inside TickingThread.run; it is busy only inside World.tick. */
    private static boolean busyWorld(StackTraceElement[] stack) {
        for (StackTraceElement f : stack) {
            if (f.getMethodName().equals("tick") && f.getClassName().equals(World.class.getName())) {
                return true;
            }
        }
        return false;
    }

    /** The scheduler is busy only while running a task, not while waiting on its queue. */
    private static boolean busyScheduler(StackTraceElement[] stack) {
        for (StackTraceElement f : stack) {
            if (f.getClassName().endsWith("ScheduledFutureTask") && f.getMethodName().equals("run")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Blocking file, network or database work holds up the whole thread it runs on. Returns the
     * method that started it (the first frame below the IO that isn't the JDK or a database
     * driver), or null when the thread isn't doing IO. The file name itself isn't on the stack.
     */
    private static String ioCaller(StackTraceElement[] stack) {
        int io = -1;
        for (int i = 0; i < Math.min(IO_DEPTH, stack.length) && io < 0; i++) {
            if (isIo(stack[i].getClassName())) {
                io = i;
            }
        }
        if (io < 0) {
            return null;
        }
        for (int i = io; i < stack.length; i++) {
            String c = stack[i].getClassName();
            if (!isIo(c) && !c.startsWith("java.") && !c.startsWith("javax.") && !c.startsWith("sun.") && !c.startsWith("jdk.")
                    && !c.startsWith("com.zaxxer.") && !c.startsWith("com.google.gson.")) {
                return shortName(c) + "." + stack[i].getMethodName();
            }
        }
        return shortName(stack[io].getClassName()) + "." + stack[io].getMethodName();
    }

    private static boolean isIo(String c) {
        for (String io : IO_CLASSES) {
            if (c.startsWith(io)) {
                return true;
            }
        }
        return false;
    }

    /** The ECS system a world thread is inside: the frame nearest the top whose class is a system. */
    private static String systemOf(StackTraceElement[] stack) {
        for (StackTraceElement f : stack) {
            String c = f.getClassName();
            if (c.endsWith("System") && !c.startsWith("java.")) {
                return shortName(c);
            }
        }
        return null;
    }

    /**
     * The task the scheduler is running, told apart by its run method: the first frame above
     * ScheduledFutureTask.run that isn't the JDK, as {@code Class.method \t loader}.
     */
    private static String taskOf(StackTraceElement[] stack) {
        for (int i = stack.length - 1; i >= 0; i--) {
            if (stack[i].getClassName().endsWith("ScheduledFutureTask") && stack[i].getMethodName().equals("run")) {
                for (int j = i - 1; j >= 0; j--) {
                    String c = stack[j].getClassName();
                    if (!c.startsWith("java.") && !c.startsWith("jdk.")) {
                        String loader = stack[j].getClassLoaderName();
                        return shortName(c) + "." + stack[j].getMethodName() + (loader == null ? "" : "\t" + loader);
                    }
                }
                return null;
            }
        }
        return null;
    }

    /** Chunk loading, world generation and saving show up by name; a spike lists the ones it caught. */
    private static List<String> phasesOf(StackTraceElement[] stack) {
        boolean load = false, gen = false, save = false;
        for (StackTraceElement f : stack) {
            String lc = f.getClassName().toLowerCase(Locale.ROOT), lm = f.getMethodName().toLowerCase(Locale.ROOT);
            if (lc.contains("chunk") && (lm.contains("load") || lc.contains("loader"))) {
                load = true;
            }
            if (lc.contains("worldgen") || lc.contains("generator") || lm.contains("generate")) {
                gen = true;
            }
            if (lm.contains("save") || lc.contains("saver") || lc.contains("saving")) {
                save = true;
            }
        }
        List<String> out = new ArrayList<>(3);
        if (load) {
            out.add("chunk load");
        }
        if (gen) {
            out.add("world gen");
        }
        if (save) {
            out.add("save");
        }
        return out;
    }

    /**
     * Follows scheduler tasks across samples: the same task seen in samples in a row is one run,
     * and its length is the sampled time it was seen. Sampler thread only.
     */
    private void trackTask(Lane lane, String task, long now, double ms) {
        if (task != null && !task.startsWith("Profiler.")) {
            lastTask = label(task);
            lastTaskNs = now;
        }
        if (task != null && task.equals(lane.runTask)) {
            lane.runMs += ms;
            return;
        }
        if (lane.runTask != null) {
            double[] t = tasks.computeIfAbsent(lane.runTask, k -> new double[3]);
            t[0] = Math.max(t[0], lane.runMs);
            t[1] += lane.runMs;
            t[2]++;
        }
        lane.runTask = task;
        lane.runMs = task == null ? 0 : ms;
    }

    /** A task key ({@code Class.method \t loader}) as {@code Class.method}. */
    private static String label(String task) {
        int tab = task.indexOf('\t');
        return tab < 0 ? task : task.substring(0, tab);
    }

    private static String shortName(String c) {
        return c.substring(c.lastIndexOf('.') + 1);
    }

    /** Records the longest single stop-the-world pause, from the JVM's own GC notifications. */
    private void listenForGcPauses() {
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getName().contains("Cycles") || !(gc instanceof NotificationEmitter emitter)) {
                continue;
            }
            try {
                emitter.addNotificationListener((n, hb) -> {
                    if ("com.sun.management.gc.notification".equals(n.getType()) && n.getUserData() instanceof CompositeData cd
                            && cd.get("gcInfo") instanceof CompositeData gi && gi.get("duration") instanceof Long d) {
                        gcMaxPause.accumulateAndGet(d, Math::max);
                    }
                }, null, null);
            } catch (RuntimeException e) {
                // No notifications on this JVM: the panel shows GC time without the longest pause.
            }
        }
    }

    private void finishSpike(Lane lane) {
        SpikeCounts s = lane.spike;
        lane.spike = null;
        long end = lane.lastBeat != s.fromBeat ? lane.lastBeat : System.nanoTime();
        long ms = TimeUnit.NANOSECONDS.toMillis(end - s.fromBeat - (lane.world ? 0 : lane.intervalNs));
        String system = s.systems.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> label(e.getKey())).orElse("");
        List<String> phases = s.phases.entrySet().stream().filter(e -> e.getValue() >= Math.max(1, s.samples * PHASE_SHARE))
                .map(Map.Entry::getKey).sorted().toList();
        Spike spike = new Spike(System.currentTimeMillis(), lane.name, ms, shares(s.owners, s.samples, 5),
                shares(s.where, s.samples, 5), Math.max(0, gcMillis() - s.gcAtStart), system, phases);
        cur.spikes++;
        synchronized (spikes) {
            spikes.addLast(spike);
            while (spikes.size() > KEEP_SPIKES) {
                spikes.removeFirst();
            }
        }
        onSpike.accept(spike);
    }

    /** The biggest {@code keep} entries as shares of {@code total}, biggest first. */
    private static Map<String, Double> shares(Map<String, Double> counts, double total, int keep) {
        Map<String, Double> out = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(keep)
                .forEach(e -> out.put(e.getKey(), total <= 0 ? 0 : e.getValue() / total));
        return out;
    }

    /** Backs the sample rate off when reading stacks costs too much, and returns to normal when it doesn't. */
    private void governor() {
        long now = System.nanoTime();
        long window = now - costWindowStart;
        if (window < TimeUnit.SECONDS.toNanos(10)) {
            return;
        }
        double cost = costNs / (double) window;
        if (cost > MAX_COST && sampleMs < SLOWEST_SAMPLE_MS) {
            sampleMs = Math.min(SLOWEST_SAMPLE_MS, sampleMs * 2);
            log.at(Level.INFO).log("Hycon profiler: sampling cost %.2f%%, now every %d ms", cost * 100, sampleMs);
        } else if (cost < LOW_COST && sampleMs > BASE_SAMPLE_MS) {
            sampleMs = Math.max(BASE_SAMPLE_MS, sampleMs / 2);
        }
        costNs = 0;
        costWindowStart = now;
    }

    /** Closes the minute: ticks, systems, memory and the sampled counts become one {@link Minute}. */
    private void rollup() {
        Counts c = cur;
        cur = new Counts();
        long time = minuteStart;
        minuteStart = System.currentTimeMillis();

        List<WorldMinute> worlds = new ArrayList<>();
        Map<String, Double> systemMs = new HashMap<>();
        List<SystemTime> systems = new ArrayList<>();
        for (Lane lane : lanes.values()) {
            World w = Universe.get().getWorld(lane.name);
            if (w == null) {
                lanes.remove(lane.name);
                continue;
            }
            worlds.add(lane.takeTicks(lane.npcs));
            lane.systems.forEach(st -> {
                systemMs.merge(st.owner(), st.msPerTick(), Double::sum);
                systems.add(st);
            });
            // Hytale's system timings may only be read on the world's own thread; they arrive next minute.
            try {
                w.execute(() -> {
                    lane.systems = readSystems(w);
                    lane.npcs = countNpcs(w);
                });
            } catch (RuntimeException e) {
                // The world is shutting down and takes no tasks; it drops out next minute.
            }
        }
        systems.sort(Comparator.comparingDouble(SystemTime::msPerTick).reversed());
        double[] late = scheduler.takeLate();
        long gc = gcMillis();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String, Double> where = new LinkedHashMap<>();
        c.where.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(KEEP_WHERE)
                .forEach(e -> where.put(e.getKey(), e.getValue()));

        trackTask(scheduler, null, 0, 0); // closes the run in progress; a task spanning minutes counts in both
        List<TaskRun> taskRuns = new ArrayList<>();
        tasks.forEach((k, v) -> {
            int tab = k.indexOf('\t');
            String owner = tab < 0 ? Owners.HYTALE : Owners.ofLoader(k.substring(tab + 1));
            taskRuns.add(new TaskRun(owner, label(k), v[0], v[1], (int) v[2]));
        });
        tasks.clear();
        taskRuns.sort(Comparator.comparingDouble(TaskRun::longestMs).reversed());
        List<Late> lateList = new ArrayList<>();
        for (Late l; (l = lates.poll()) != null; ) {
            lateList.add(l);
        }
        lateList.sort(Comparator.comparingDouble(Late::lateMs).reversed());
        Map<String, Double> ioWhere = new LinkedHashMap<>();
        c.ioWhere.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(KEEP_WHERE)
                .forEach(e -> ioWhere.put(e.getKey(), e.getValue()));
        List<Traffic> traffic = new ArrayList<>();
        int players = readTraffic(traffic);

        Minute m = new Minute(time, c.worldMs, c.worldBusyMs, Map.copyOf(c.owners), c.schedMs, c.schedBusyMs,
                Map.copyOf(c.schedOwners), where, Map.copyOf(c.io), List.copyOf(worlds), Map.copyOf(systemMs),
                List.copyOf(systems.subList(0, Math.min(20, systems.size()))), late[0], late[1],
                Math.max(0, gc - lastGcMs), heap.getUsed() >> 20, heap.getMax() >> 20, c.spikes, sampleMs,
                ioWhere, Map.copyOf(c.worldOwners), List.copyOf(taskRuns.subList(0, Math.min(KEEP_TASKS, taskRuns.size()))),
                List.copyOf(lateList.subList(0, Math.min(KEEP_LATES, lateList.size()))), List.copyOf(traffic), players,
                gcMaxPause.getAndSet(0));
        lastGcMs = gc;
        synchronized (minutes) {
            minutes.addLast(m);
            while (minutes.size() > KEEP_MINUTES) {
                minutes.removeFirst();
            }
        }
        onMinute.accept(m);
    }

    /** NPCs in a world, on the world's thread; -1 when the NPC module isn't there. */
    private static int countNpcs(World w) {
        try {
            return w.getEntityStore().getStore().getEntityCountFor(NPCEntity.getComponentType());
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /**
     * Bytes each player sent and received since the last minute, from Hytale's own per-connection
     * packet counters. Returns the player count. Players who left are forgotten.
     */
    private int readTraffic(List<Traffic> out) {
        Map<UUID, long[]> seen = new HashMap<>();
        int players = 0;
        for (PlayerRef p : Universe.get().getPlayers()) {
            players++;
            try {
                PacketStatsRecorder rec = p.getPacketHandler().getPacketStatsRecorder();
                if (rec == null) {
                    continue;
                }
                long sent = 0, recv = 0;
                for (int id = 0; id <= PacketHandler.MAX_PACKET_ID; id++) {
                    PacketStatsRecorder.PacketStatsEntry e = rec.getEntry(id);
                    if (e != null) {
                        sent += e.getSentCompressedTotal();
                        recv += e.getReceivedCompressedTotal();
                    }
                }
                long[] now = {sent, recv};
                long[] before = lastTraffic.get(p.getUuid());
                seen.put(p.getUuid(), now);
                if (before != null && sent >= before[0] && recv >= before[1]) {
                    out.add(new Traffic(p.getUsername(), sent - before[0], recv - before[1]));
                }
            } catch (RuntimeException | LinkageError e) {
                // Counters missing on this build or the player is leaving: skip them this minute.
            }
        }
        lastTraffic.clear();
        lastTraffic.putAll(seen);
        out.sort(Comparator.comparingLong(t -> -(t.sent() + t.received())));
        if (out.size() > 50) {
            out.subList(50, out.size()).clear();
        }
        return players;
    }

    /** Each ticking system's average time per tick over the last minute, from Hytale's own timer. */
    private static List<SystemTime> readSystems(World w) {
        List<SystemTime> out = new ArrayList<>();
        addSystems(out, w.getName(), w.getEntityStore().getStore());
        addSystems(out, w.getName(), w.getChunkStore().getStore());
        out.sort(Comparator.comparingDouble(SystemTime::msPerTick).reversed());
        return List.copyOf(out.subList(0, Math.min(30, out.size())));
    }

    private static <T> void addSystems(List<SystemTime> out, String world, Store<T> store) {
        ComponentRegistry.Data<T> data = store.getRegistry().getData();
        HistoricMetric[] metrics = store.getSystemMetrics();
        for (int i = 0; i < Math.min(metrics.length, data.getSystemSize()); i++) {
            ISystem<T> system = data.getSystem(i);
            if (metrics[i] == null || system == null || system instanceof Heartbeat) {
                continue;
            }
            double ms = metrics[i].getAverage(1) / 1e6; // period 1 is the last minute
            if (ms >= 0.01) {
                Class<?> type = system.getClass();
                String name = type.getName().substring(type.getName().lastIndexOf('.') + 1);
                out.add(new SystemTime(name, Owners.of(type), world, ms));
            }
        }
    }

    /** Time spent in stop-the-world collections. ZGC/Shenandoah "Cycles" beans count concurrent work, not pauses. */
    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!gc.getName().contains("Cycles") && gc.getCollectionTime() > 0) {
                total += gc.getCollectionTime();
            }
        }
        return total;
    }

    /** Marks each world's tick, so the sampler knows which thread is the world's and when a tick runs long. */
    private final class Heartbeat extends TickingSystem<EntityStore> {
        @Override
        public void tick(float dt, int index, @Nonnull Store<EntityStore> store) {
            World w = store.getExternalData().getWorld();
            Lane lane = lanes.computeIfAbsent(w.getName(), n -> new Lane(n, true, 0));
            lane.thread = Thread.currentThread();
            lane.tick(w.getBufferedTickLengthMetricSet().getLastValue(), store.getEntityCount(),
                    w.getChunkStore().getLoadedChunksCount());
            lane.lastBeat = System.nanoTime();
        }
    }

    /** One thread being watched: a world thread, or the shared scheduler. */
    private static final class Lane {
        final String name;
        final boolean world;
        /** How often it should check in; a world beats every tick, the scheduler probe every second. */
        final long intervalNs;
        volatile Thread thread;
        volatile long lastBeat;
        volatile List<SystemTime> systems = List.of();
        volatile int npcs = -1;
        /** The scheduler task seen in the last sample and how long it has been seen (sampler thread only). */
        String runTask;
        double runMs;
        // Sampler thread only.
        long lastSample;
        SpikeCounts spike;
        /** A beat after which the thread was found idle, so no spike is started from it again. */
        long quietBeat;
        // Written by the lane's own thread, read by the sampler.
        private double tickSumMs, tickMaxMs, lateSumMs, lateMaxMs;
        private int ticks, lates, entities, chunks;

        Lane(String name, boolean world, long intervalNs) {
            this.name = name;
            this.world = world;
            this.intervalNs = intervalNs;
        }

        synchronized void tick(long lengthNs, int entities, int chunks) {
            double ms = lengthNs / 1e6;
            if (ms > 0) {
                tickSumMs += ms;
                tickMaxMs = Math.max(tickMaxMs, ms);
                ticks++;
            }
            this.entities = entities;
            this.chunks = chunks;
        }

        synchronized WorldMinute takeTicks(int npcs) {
            WorldMinute w = new WorldMinute(name, ticks == 0 ? 0 : tickSumMs / ticks, tickMaxMs, ticks, entities, chunks, npcs);
            tickSumMs = tickMaxMs = 0;
            ticks = 0;
            return w;
        }

        synchronized void late(long ns) {
            double ms = ns / 1e6;
            lateSumMs += ms;
            lateMaxMs = Math.max(lateMaxMs, ms);
            lates++;
        }

        synchronized double[] takeLate() {
            double[] out = {lates == 0 ? 0 : lateSumMs / lates, lateMaxMs};
            lateSumMs = lateMaxMs = 0;
            lates = 0;
            return out;
        }
    }

    /** The current minute's sampled time, in ms. */
    private static final class Counts {
        double worldMs, worldBusyMs, schedMs, schedBusyMs;
        int spikes;
        final Map<String, Double> owners = new HashMap<>(), schedOwners = new HashMap<>(), where = new HashMap<>(),
                io = new HashMap<>(), ioWhere = new HashMap<>(), worldOwners = new HashMap<>();
    }

    /** Samples taken during one spike. */
    private static final class SpikeCounts {
        final long fromBeat, gcAtStart, startNs = System.nanoTime();
        final Map<String, Double> owners = new HashMap<>(), where = new HashMap<>(), systems = new HashMap<>(),
                phases = new HashMap<>();
        int samples;

        SpikeCounts(long fromBeat, long gcAtStart) {
            this.fromBeat = fromBeat;
            this.gcAtStart = gcAtStart;
        }
    }
}
