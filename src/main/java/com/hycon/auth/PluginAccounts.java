package com.hycon.auth;

import com.hycon.api.HyconApi;
import com.hycon.audit.AuditLog;

import java.io.IOException;

/**
 * {@link HyconApi.Accounts} for plugins: read an account's level, set a non-owner's level, lock or unlock it. Owners
 * are never touched, nothing is created or deleted and no password changes. Every change is in the audit log with the
 * plugin as "by", and logs the account out, as its open pages were built for the old level.
 */
public final class PluginAccounts implements HyconApi.Accounts {

    private final AdminStore admins;
    private final Sessions sessions;
    private final AuditLog audit;

    public PluginAccounts(AdminStore admins, Sessions sessions, AuditLog audit) {
        this.admins = admins;
        this.sessions = sessions;
        this.audit = audit;
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
    public boolean setLevel(String name, int level, String by) {
        AdminStore.Admin a = admins.get(name);
        Level to = level == HyconApi.VIEWER ? Level.VIEWER : level == HyconApi.MODERATOR ? Level.MODERATOR
                : level == HyconApi.ADMIN ? Level.ADMIN : null;
        if (a == null || a.level() == Level.OWNER || to == null) return false;
        if (a.level() == to && !a.locked()) return true;
        try {
            admins.setLevel(a.name(), to);
            admins.setLocked(a.name(), false);
        } catch (IOException | IllegalArgumentException e) {
            audit.record(by, "plugin", "account.level", a.name(), to.name() + ": " + e.getMessage(), false);
            return false;
        }
        sessions.removeAdmin(a.name());
        audit.record(by, "plugin", "account.level", a.name(), to.name() + (a.locked() ? ", unlocked" : ""), true);
        return true;
    }

    @Override
    public boolean setLocked(String name, boolean locked, String by) {
        AdminStore.Admin a = admins.get(name);
        if (a == null || a.level() == Level.OWNER) return false;
        if (a.locked() == locked) return true;
        try {
            admins.setLocked(a.name(), locked);
        } catch (IOException | IllegalArgumentException e) {
            audit.record(by, "plugin", "account.lock", a.name(), e.getMessage(), false);
            return false;
        }
        if (locked) sessions.removeAdmin(a.name());
        audit.record(by, "plugin", "account.lock", a.name(), locked ? "locked" : "unlocked", true);
        return true;
    }
}
