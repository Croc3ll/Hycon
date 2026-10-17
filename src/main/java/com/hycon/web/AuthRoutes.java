package com.hycon.web;

import com.hycon.audit.AuditLog;
import com.hycon.auth.AdminStore;
import com.hycon.auth.Level;
import com.hycon.auth.LoginLimiter;
import com.hycon.auth.Passwords;
import com.hycon.auth.Sessions;

import java.io.IOException;

/** Login, logout, own password, and the OWNER-only account management. */
public final class AuthRoutes {

    private final WebServer web;
    private final AdminStore admins;
    private final Sessions sessions;
    private final LoginLimiter limiter;
    private final AuditLog audit;

    public AuthRoutes(WebServer web, AdminStore admins, Sessions sessions, LoginLimiter limiter, AuditLog audit) {
        this.web = web;
        this.admins = admins;
        this.sessions = sessions;
        this.limiter = limiter;
        this.audit = audit;
    }

    public void register() {
        web.always("GET", "/api/session", null, this::session);
        web.always("POST", "/api/login", null, this::login);
        web.always("POST", "/api/logout", null, this::logout);
        web.always("POST", "/api/password", Level.VIEWER, this::password);
        web.get("/api/accounts", Level.OWNER, this::accounts);
        web.post("/api/accounts/create", Level.OWNER, this::create);
        web.post("/api/accounts/level", Level.OWNER, this::level);
        web.post("/api/accounts/reset", Level.OWNER, this::reset);
        web.post("/api/accounts/delete", Level.OWNER, this::delete);
    }

    private void session(Req r) throws IOException {
        Json j = new Json().beginObject().field("csrf", r.session.csrf).field("loggedIn", r.admin != null);
        if (r.admin == null && admins.setupPending()) {
            // First start: the login page tells the person at the server what to type.
            j.field("setupName", AdminStore.DEFAULT_NAME).field("setupPassword", AdminStore.DEFAULT_PASSWORD);
        }
        if (r.admin != null) {
            j.field("name", r.admin.name()).field("level", r.admin.level().name()).field("rank", r.admin.level().rank)
                    .field("mustChange", r.admin.mustChange());
        }
        r.ok(j.endObject());
    }

    private void login(Req r) throws IOException {
        // Spaces around the name come from copy-paste or autofill; names never contain them.
        String name = r.str("name") == null ? null : r.str("name").strip();
        String pw = r.str("password");
        if (name == null || pw == null || name.length() > 64 || pw.length() > 256) {
            r.error(400, "Name and password needed");
            return;
        }
        long wait = limiter.retryAfter(r.ip, name);
        if (wait > 0) {
            audit.record(name, r.ip, "login", null, "refused: too many attempts", false);
            r.ex.getResponseHeaders().set("Retry-After", String.valueOf(wait / 1000 + 1));
            r.error(429, "Too many failed logins. Try again in " + (wait / 60_000 + 1) + " min.");
            return;
        }
        AdminStore.Admin a = admins.get(name);
        if (!Passwords.verify(pw, a == null ? null : a.hash())) {
            limiter.fail(r.ip, name);
            audit.record(name, r.ip, "login", null, "wrong name or password", false);
            r.error(401, "Wrong name or password");
            return;
        }
        if (a.defaultPassword() && !loopback(r.ip)) {
            // The default password is published, so it only works from the server machine itself.
            audit.record(a.name(), r.ip, "login", null, "default password used from another machine", false);
            r.error(403, "Log in from the server machine itself until the default password is changed");
            return;
        }
        // New session id at login, so an id an attacker planted or saw beforehand is useless.
        sessions.remove(r.session);
        web.issueCookie(r, sessions.create(a.name(), r.ip));
        audit.record(a.name(), r.ip, "login", null, null, true);
        r.ok(new Json().beginObject().field("ok", true).field("csrf", r.session.csrf).endObject());
    }

    private void logout(Req r) throws IOException {
        if (r.admin != null) {
            audit.record(r.admin.name(), r.ip, "logout", null, null, true);
        }
        sessions.remove(r.session);
        r.ok(new Json().beginObject().field("ok", true).endObject());
    }

    private void password(Req r) throws IOException {
        String current = r.str("current");
        String next = r.str("password");
        if (current == null || !Passwords.verify(current, r.admin.hash())) {
            audit.record(r.who(), r.ip, "password", r.who(), "wrong current password", false);
            r.error(403, "Current password is wrong");
            return;
        }
        String problem = weak(next, r.admin.name());
        if (problem != null) {
            r.error(400, problem);
            return;
        }
        if (next.equals(current)) {
            r.error(400, "Choose a password different from the current one");
            return;
        }
        admins.setPassword(r.admin.name(), next);
        // Log out every other browser of this account; keep this one with a fresh session.
        sessions.removeAdmin(r.admin.name());
        web.issueCookie(r, sessions.create(r.admin.name(), r.ip));
        audit.record(r.who(), r.ip, "password", r.who(), "changed", true);
        r.ok(new Json().beginObject().field("ok", true).field("csrf", r.session.csrf).endObject());
    }

    private void accounts(Req r) throws IOException {
        Json j = new Json().beginObject().name("accounts").beginArray();
        for (AdminStore.Admin a : admins.all()) {
            j.beginObject().field("name", a.name()).field("level", a.level().name()).field("mustChange", a.mustChange()).endObject();
        }
        r.ok(j.endArray().endObject());
    }

    private void create(Req r) throws IOException {
        String name = r.str("name");
        Level level = Level.parse(r.str("level"));
        if (level == null) {
            r.error(400, "Pick a level");
            return;
        }
        try {
            String pw = admins.create(name, level);
            audit.record(r.who(), r.ip, "account.create", name, level.name(), true);
            // Shown once to the OWNER, who passes it on; it must be changed at first login.
            r.ok(new Json().beginObject().field("ok", true).field("password", pw).endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void level(Req r) throws IOException {
        String name = r.str("name");
        Level level = Level.parse(r.str("level"));
        if (level == null) {
            r.error(400, "Pick a level");
            return;
        }
        try {
            admins.setLevel(name, level);
            sessions.removeAdmin(name); // their open pages were built for the old level
            audit.record(r.who(), r.ip, "account.level", name, level.name(), true);
            r.ok(new Json().beginObject().field("ok", true).endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void reset(Req r) throws IOException {
        String name = r.str("name");
        try {
            String pw = admins.resetPassword(name);
            sessions.removeAdmin(name);
            audit.record(r.who(), r.ip, "account.reset", name, null, true);
            r.ok(new Json().beginObject().field("ok", true).field("password", pw).endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void delete(Req r) throws IOException {
        String name = r.str("name");
        try {
            admins.delete(name);
            sessions.removeAdmin(name);
            audit.record(r.who(), r.ip, "account.delete", name, null, true);
            r.ok(new Json().beginObject().field("ok", true).endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private static boolean loopback(String ip) {
        return ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1");
    }

    /** Null when acceptable, else what's wrong. Length matters far more than character rules. */
    private static String weak(String pw, String name) {
        if (pw == null || pw.length() < Passwords.MIN_LENGTH) {
            return "Use at least " + Passwords.MIN_LENGTH + " characters";
        }
        if (pw.length() > 256) {
            return "That password is too long";
        }
        if (pw.equalsIgnoreCase(AdminStore.DEFAULT_PASSWORD) || pw.toLowerCase().contains(name.toLowerCase())) {
            return "The password must not contain the account name";
        }
        return null;
    }
}
