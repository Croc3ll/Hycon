package com.hycon.web;

import com.hycon.audit.AuditLog;
import com.hycon.auth.Level;
import com.hycon.game.LogTap;
import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** The console page's extras: the status bar, log downloads, broadcasts and stopping the server. */
public final class ConsoleRoutes {

    private static final int MAX_BROADCAST = 300;

    private final WebServer web;
    private final LogTap log;
    private final AuditLog audit;

    public ConsoleRoutes(WebServer web, LogTap log, AuditLog audit) {
        this.web = web;
        this.log = log;
        this.audit = audit;
    }

    public void register() {
        web.get("/api/status", Level.MODERATOR, this::status);
        web.get("/api/log/download", Level.MODERATOR, this::download);
        web.post("/api/broadcast", Level.MODERATOR, this::broadcast);
        web.post("/api/server/stop", Level.ADMIN, this::stop);
    }

    /** TPS of the slowest world, players online, heap and uptime. */
    private void status(Req r) throws IOException {
        double tps = -1;
        int target = 0;
        for (World w : Universe.get().getWorlds().values()) {
            if (!w.isAlive()) {
                continue;
            }
            HistoricMetric ticks = w.getBufferedTickLengthMetricSet();
            double avg = ticks.getAverage(1);
            double t = avg > 0 ? Math.min(w.getTps(), 1e9 / avg) : w.getTps();
            if (tps < 0 || t < tps) {
                tps = t;
                target = w.getTps();
            }
        }
        Runtime rt = Runtime.getRuntime();
        r.ok(new Json().beginObject()
                .field("tps", Math.round(tps * 10) / 10.0).field("targetTps", target)
                .field("online", Universe.get().getPlayers().size())
                .field("memoryMb", (rt.totalMemory() - rt.freeMemory()) >> 20).field("memoryMaxMb", rt.maxMemory() >> 20)
                .field("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime())
                .endObject());
    }

    /**
     * range=hour: the last hour from the panel's buffer. range=today: the server's newest log file
     * (the whole session), or the whole buffer if the file can't be read.
     */
    private void download(Req r) throws IOException {
        boolean hour = !"today".equals(r.query("range"));
        String text = null;
        if (!hour) {
            try (Stream<Path> files = Files.list(Path.of("logs"))) {
                Path newest = files.filter(f -> f.getFileName().toString().endsWith(".log"))
                        .max(Comparator.comparingLong(f -> f.toFile().lastModified())).orElse(null);
                if (newest != null) {
                    text = Files.readString(newest, StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException e) {
                text = null; // locked or missing: fall back to the buffer
            }
        }
        if (text == null) {
            long from = hour ? System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1) : 0;
            text = String.join("\n", log.sinceTime(from)) + "\n";
        }
        audit.record(r.who(), r.ip, "log.download", hour ? "last hour" : "today", "", true);
        String name = "server-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) + (hour ? "-last-hour" : "") + ".log";
        r.ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        r.send(200, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends an announcement to every player's chat. */
    private void broadcast(Req r) throws IOException {
        String text = r.str("message") == null ? "" : r.str("message").strip().replaceAll("[\r\n\t]+", " ");
        if (text.isEmpty() || text.length() > MAX_BROADCAST) {
            r.error(400, "Write an announcement of up to " + MAX_BROADCAST + " characters");
            return;
        }
        String color = r.str("color") != null && r.str("color").matches("#[0-9A-Fa-f]{6}") ? r.str("color") : "#FFAA00";
        Universe.get().sendMessage(Message.raw("[Announcement] ").color(color).bold(true).insert(Message.raw(text).color("#FFFFFF")));
        log.addText("[Hycon] " + r.who() + " announced: " + text);
        audit.record(r.who(), r.ip, "broadcast", "", text, true);
        r.ok(new Json().beginObject().field("ok", true).field("message", "Announcement sent").endObject());
    }

    /** Saves and stops the server (a host like AMP can start it again if set to). */
    private void stop(Req r) throws IOException {
        audit.record(r.who(), r.ip, "server.stop", "", "", true);
        log.addText("[Hycon] " + r.who() + " stopped the server from the panel");
        r.ok(new Json().beginObject().field("ok", true).field("message", "Server is stopping").endObject());
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> HytaleServer.get().shutdownServer(), 1, TimeUnit.SECONDS);
    }
}
