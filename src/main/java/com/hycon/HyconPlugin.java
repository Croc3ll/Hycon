package com.hycon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
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
import com.hycon.game.ServerPanel;
import com.hycon.web.AuthRoutes;
import com.hycon.web.GameRoutes;
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
        getCommandRegistry().registerCommand(new HyconCommand());
        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, e -> known.seen(e.getPlayerRef().getUuid(), e.getPlayerRef().getUsername()));
    }

    @Override
    protected void start() {
        HytaleLogger log = getLogger();
        if (!config.enabled()) {
            log.at(Level.INFO).log("Hycon panel is turned off in hycon.json");
            return;
        }
        try {
            Path firstLogin = admins.load();
            audit.open();
            if (firstLogin != null) {
                // Only the file's location goes in the log; the log is visible in the panel itself.
                log.at(Level.WARNING).log("Hycon: created panel account 'owner'. Its password is in %s", firstLogin.toAbsolutePath());
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
            new GameRoutes(web, moderation, new ConsoleRunner(config.blockedCommands()), logTap, audit, admins, known).register();
            web.start();
        } catch (IOException e) {
            ((HytaleLogger.Api) log.at(Level.SEVERE).withCause(e)).log("Hycon could not start the panel on %s:%d (see hycon.json)",
                    config.bindAddress(), config.port());
            web = null;
            return;
        }
        HyconApi.register(new ServerPanel());
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
            known.saveIfChanged();
        }
        if (audit != null) {
            audit.close();
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
