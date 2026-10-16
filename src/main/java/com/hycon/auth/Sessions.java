package com.hycon.auth;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Browser sessions, in memory only (a restart logs everyone out, which is fine for an admin tool).
 *
 * <p>Every visitor gets a session before logging in, carrying a CSRF token; the login form must send
 * that token too, so another web site can't log a browser into an account of the attacker's choosing.
 * Logging in issues a brand-new session id, so an id seen before login is worthless afterwards.
 */
public final class Sessions {

    public static final class Session {
        public final String id;
        public final String csrf;
        public final String ip;
        public final long created;
        /** Account name, or null before login. */
        public final String admin;
        volatile long lastSeen;

        Session(String admin, String ip) {
            this.id = Passwords.token();
            this.csrf = Passwords.token();
            this.admin = admin;
            this.ip = ip;
            this.created = System.currentTimeMillis();
            this.lastSeen = created;
        }

        public boolean csrfMatches(String given) {
            return given != null && MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Anonymous sessions live briefly, and their number is capped so nobody can fill memory with them. */
    private static final long ANON_MS = 15 * 60_000;
    private static final int MAX_SESSIONS = 2000;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long idleMs;
    private final long maxMs;

    public Sessions(long idleMs, long maxMs) {
        this.idleMs = idleMs;
        this.maxMs = maxMs;
    }

    /** The live session for this cookie value, or null (expired sessions are dropped here). */
    public Session get(String id) {
        if (id == null) {
            return null;
        }
        Session s = sessions.get(id);
        if (s == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (expired(s, now)) {
            sessions.remove(id);
            return null;
        }
        s.lastSeen = now;
        return s;
    }

    public Session create(String admin, String ip) {
        if (sessions.size() >= MAX_SESSIONS) {
            sweep();
            if (sessions.size() >= MAX_SESSIONS) {
                // Still full: drop anonymous sessions first; logged-in admins are never pushed out.
                sessions.values().removeIf(s -> s.admin == null);
            }
        }
        Session s = new Session(admin, ip);
        sessions.put(s.id, s);
        return s;
    }

    public void remove(Session s) {
        if (s != null) {
            sessions.remove(s.id);
        }
    }

    /** Logs an account out everywhere (password changed, account removed or demoted). */
    public void removeAdmin(String admin) {
        sessions.values().removeIf(s -> s.admin != null && s.admin.equalsIgnoreCase(admin));
    }

    public void sweep() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> expired(s, now));
    }

    private boolean expired(Session s, long now) {
        if (s.admin == null) {
            return now - s.created > ANON_MS;
        }
        return now - s.lastSeen > idleMs || now - s.created > maxMs;
    }
}
