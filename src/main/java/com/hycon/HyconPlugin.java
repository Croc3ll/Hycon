package com.hycon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hycon.api.HyconApi;
import com.hycon.audit.AuditLog;
import com.hycon.auth.AdminStore;
import com.hycon.auth.LoginLimiter;
import com.hycon.auth.Sessions;
import com.hycon.commands.HyconCommand;
import com.hycon.config.HyconConfig;
import com.hycon.game.ConsoleRunner;
import com.hycon.game.KnownPlayers;
import com.hycon.game.LogTap;
import com.hycon.game.Moderation;
import com.hycon.game.KillLog;
import com.hycon.game.PingLog;
import com.hycon.game.PlayerRecords;
import com.hycon.analytics.AnalyticsPanel;
import com.hycon.analytics.Samples;
import com.hycon.web.AuthRoutes;
import com.hycon.web.GameRoutes;
import com.hycon.web.SyncRoutes;
import com.hycon.sync.BanSync;
import com.hycon.sync.ShareServer;
import com.hycon.sync.SharingStore;
import com.hycon.web.WebServer;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Entry point. {@link #setup()} reads the config and accounts and registers the command;
 * {@link #start()} opens the web panel; {@link #shutdown()} closes it. If the panel can't start
 * (port taken, bad config) the server keeps running without it.
 */
public final class HyconPlugin extends JavaPlugin {

    private static HyconPlugin instance;

    private HyconConfig config;
    private AdminStore admins;
    private AuditLog audit;
    private KnownPlayers known;
    private PlayerRecords records;
    private Samples samples;
    private SharingStore sharing;
    private BanSync banSync;
    private ShareServer shareServer;
    private ScheduledFuture<?> sampler;
    private PingLog pingLog;
    private KillLog killLog;
    private ScheduledFuture<?> pingSampler;
    private LogTap logTap;
    private Sessions sessions;
    private WebServer web;
    private ScheduledFuture<?> housekeeping;

    public HyconPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static HyconPlugin get() {
        return instance;
    }

    @Override
    protected void setup() {
        Path dir = getDataDirectory();
        try {
            config = HyconConfig.load(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Hycon could not read hycon.json: " + e.getMessage(), e);
        }
        admins = new AdminStore(dir);
        audit = new AuditLog(dir);
        known = new KnownPlayers(dir);
        known.load();
        records = new PlayerRecords(dir);
        samples = new Samples(dir);
        pingLog = new PingLog(dir);
        killLog = new KillLog(dir);
        getEntityStoreRegistry().registerSystem(killLog.new Tracker());
        sharing = new SharingStore(dir);
        getCommandRegistry().registerCommand(new HyconCommand());
        // Joins and leaves keep the player directory's "last seen" current.
        getEventRegistry().registerAsync(PlayerConnectEvent.class, f -> f.thenApply(e -> {
            known.joined(e.getPlayerRef().getUuid(), e.getPlayerRef().getUsername(), e.getWorld() == null ? null : e.getWorld().getName());
            return e;
        }));
        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, e -> known.left(e.getPlayerRef().getUuid(), e.getPlayerRef().getUsername()));
    }

    @Override
    protected void start() {
        HytaleLogger log = getLogger();
        if (!config.enabled()) {
            log.at(Level.INFO).log("Hycon panel is turned off in hycon.json");
            return;
        }
        try {
            boolean created = admins.load();
            audit.open();
            records.open();
            samples.open();
            sharing.load();
            killLog.open();
            if (created) {
                log.at(Level.WARNING).log("Hycon first start: log in at %s as '%s' with password '%s' (from this machine) and choose a new password.",
                        address(), AdminStore.DEFAULT_NAME, AdminStore.DEFAULT_PASSWORD);
            }
        } catch (IOException | IllegalArgumentException e) {
            ((HytaleLogger.Api) log.at(Level.SEVERE).withCause(e)).log("Hycon could not read admins.json; panel not started");
            return;
        }

        logTap = new LogTap(config.logLines());
        logTap.install();
        sessions = new Sessions(config.sessionIdleMs(), config.sessionMaxMs());
        Moderation moderation = new Moderation(known);
        try {
            web = new WebServer(config, sessions, admins);
            new AuthRoutes(web, admins, sessions, new LoginLimiter(config.loginAttempts(), config.loginWindowMs()), audit).register();
            new GameRoutes(web, moderation, new ConsoleRunner(config.blockedCommands()), logTap, audit, admins, known, records, sharing, pingLog, killLog).register();
            banSync = new BanSync(sharing, known, audit);
            shareServer = new ShareServer(sharing, banSync, audit, new LoginLimiter(config.loginAttempts(), config.loginWindowMs()),
                    config.shareBindAddress(), config.sharePort());
            new SyncRoutes(web, sharing, banSync, shareServer, audit).register();
            web.start();
        } catch (IOException e) {
            ((HytaleLogger.Api) log.at(Level.SEVERE).withCause(e)).log("Hycon could not start the panel on %s:%d (see hycon.json)",
                    config.bindAddress(), config.port());
            web = null;
            return;
        }
        HyconApi.register(new AnalyticsPanel(samples, known));
        banSync.start();
        shareServer.update(); // opens the share port only if share keys exist
        if (shareServer.error() != null) {
            log.at(Level.WARNING).log("Hycon: %s", shareServer.error());
        }
        sampler = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            Runtime rt = Runtime.getRuntime();
            samples.add(Universe.get().getPlayers().size(), (int) ((rt.totalMemory() - rt.freeMemory()) >> 20));
        }, 0, Samples.INTERVAL_MS, TimeUnit.MILLISECONDS);
        pingSampler = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(pingLog::sample, 1, 1, TimeUnit.MINUTES);
        // Players saved before Hycon was installed, so they can be searched too.
        Path playersDir = Universe.get().getPath().resolve("players");
        Thread.ofVirtual().name("Hycon-PlayerImport").start(() -> known.importSaved(playersDir));
        housekeeping = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            sessions.sweep();
            known.saveIfChanged();
        }, 1, 1, TimeUnit.MINUTES);

        log.at(Level.INFO).log("Hycon panel: %s", address());
        if (!config.loopbackOnly()) {
            log.at(Level.WARNING).log("Hycon panel listens on %s, not only this machine. Use HTTPS in front of it and a firewall.",
                    config.bindAddress());
        }
    }

    @Override
    protected void shutdown() {
        if (banSync != null) {
            banSync.stop();
        }
        if (shareServer != null) {
            shareServer.stop();
        }
        if (sampler != null) {
            sampler.cancel(false);
        }
        if (pingSampler != null) {
            pingSampler.cancel(false);
        }
        if (samples != null) {
            samples.close();
        }
        if (housekeeping != null) {
            housekeeping.cancel(false);
        }
        if (web != null) {
            web.stop();
        }
        if (logTap != null) {
            logTap.uninstall();
        }
        HyconApi.clear();
        if (known != null) {
            known.leaveAll();
            known.saveIfChanged();
        }
        if (audit != null) {
            audit.close();
        }
        if (records != null) {
            records.close();
        }
        if (killLog != null) {
            killLog.close();
        }
    }

    public String status() {
        return web == null ? "Hycon panel is not running (see hycon.json and the server log)." : "Hycon panel: " + address();
    }

    public Path resetPasswordToFile(String name) throws IOException {
        if (web == null) {
            // Accounts may not have been read; saving now could overwrite admins.json with an empty list.
            throw new IOException("the panel is not running");
        }
        Path file = admins.resetToFile(name);
        if (sessions != null) {
            sessions.removeAdmin(name);
        }
        audit.record("Console", "-", "account.reset", name, "via /hycon resetpassword", true);
        return file;
    }

    private String address() {
        String host = config.bindAddress().equals("0.0.0.0") ? "127.0.0.1" : config.bindAddress();
        return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + config.port() + "/";
    }
}
