package com.hycon.auth;

import com.hycon.api.HyconApi;
import com.hycon.audit.AuditLog;
import com.hycon.config.HyconConfig;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@link HyconApi.Accounts} for plugins: read an account's level, set a non-owner's level, lock or unlock it. Owners
 * are never touched, nothing is created or deleted and no password changes. Only plugins the owner listed in
 * AccountPlugins may change anything, so a plugin can't make itself staff or lock the staff out. Every change is in
 * the audit log with the plugin named in "by", and logs the account out, as its open pages were built for the old level.
 */
public final class PluginAccounts implements HyconApi.Accounts {

    private final AdminStore admins;
    private final Sessions sessions;
    private final AuditLog audit;
    private final Supplier<HyconConfig> config;
    private final HytaleLogger log;
    /** Plugins already warned about, so a plugin syncing on every join doesn't fill the log. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public PluginAccounts(AdminStore admins, Sessions sessions, AuditLog audit, Supplier<HyconConfig> config, HytaleLogger log) {
        this.admins = admins;
        this.sessions = sessions;
        this.audit = audit;
        this.config = config;
        this.log = log;
    }

    @Override
    public int level(String name) {
        AdminStore.Admin a = admins.get(name);
        return a == null ? 0 : a.level().rank;
    }

    @Override
    public boolean locked(String name) {
        AdminStore.Admin a = admins.get(name);
        return a != null && a.locked();
    }

    @Override
    public boolean setLevel(String name, int level, String by, String plugin) {
        AdminStore.Admin a = admins.get(name);
        Level to = level == HyconApi.VIEWER ? Level.VIEWER : level == HyconApi.MODERATOR ? Level.MODERATOR
                : level == HyconApi.ADMIN ? Level.ADMIN : null;
        if (a == null || a.level() == Level.OWNER || to == null || !trusted(plugin, "account.level", a.name())) return false;
        if (a.level() == to && !a.locked()) return true;
        try {
            admins.setLevel(a.name(), to);
            admins.setLocked(a.name(), false);
        } catch (IOException | IllegalArgumentException e) {
            audit.record(signed(by, plugin), "plugin", "account.level", a.name(), to.name() + ": " + e.getMessage(), false);
            return false;
        }
        sessions.removeAdmin(a.name());
        audit.record(signed(by, plugin), "plugin", "account.level", a.name(), to.name() + (a.locked() ? ", unlocked" : ""), true);
        return true;
    }

    @Override
    public boolean setLocked(String name, boolean locked, String by, String plugin) {
        AdminStore.Admin a = admins.get(name);
        if (a == null || a.level() == Level.OWNER || !trusted(plugin, "account.lock", a.name())) return false;
        if (a.locked() == locked) return true;
        try {
            admins.setLocked(a.name(), locked);
        } catch (IOException | IllegalArgumentException e) {
            audit.record(signed(by, plugin), "plugin", "account.lock", a.name(), e.getMessage(), false);
            return false;
        }
        if (locked) sessions.removeAdmin(a.name());
        audit.record(signed(by, plugin), "plugin", "account.lock", a.name(), locked ? "locked" : "unlocked", true);
        return true;
    }

    /** False, with an audit entry and one log warning, when the owner hasn't listed this plugin. */
    private boolean trusted(String plugin, String action, String account) {
        if (config.get().accountPlugins().contains(plugin.toLowerCase(Locale.ROOT))) return true;
        audit.record(plugin, "plugin", action, account, "refused: not in AccountPlugins", false);
        if (warned.add(plugin)) {
            log.at(java.util.logging.Level.WARNING).log(
                    "Hycon refused %s changing panel accounts. To allow it, add \"%s\" to AccountPlugins in hycon.json.", plugin, plugin);
        }
        return false;
    }

    /** Names the plugin beside "by", so a plugin can't sign a change as a staff member. */
    private static String signed(String by, String plugin) {
        if (by == null || by.isBlank() || plugin.toLowerCase(Locale.ROOT).contains(by.toLowerCase(Locale.ROOT))) return plugin;
        return by + " (" + plugin + ")";
    }
}
