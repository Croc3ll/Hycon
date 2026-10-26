package com.hycon.auth;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
        if (remembered.values().removeIf(r -> r.admin().equalsIgnoreCase(admin))) {
            saveRemembered();
        }
    }

    // ------------------------------------------------------------------ remember me
    // "Remember me" logins outlive restarts: the browser keeps a random token in an HttpOnly cookie and
    // remember.txt keeps only its SHA-256 hash, so a copy of the file can't be used to log in.

    public static final long REMEMBER_MS = 30L * 24 * 60 * 60_000;

    private record Remembered(String admin, long expires) {}

    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();
    private Path rememberFile;

    /** Loads saved remember-me logins; call once at start. */
    public synchronized void rememberIn(Path dir) {
        rememberFile = dir.resolve("remember.txt");
        try {
            if (Files.exists(rememberFile)) {
                long now = System.currentTimeMillis();
                for (String line : Files.readAllLines(rememberFile, StandardCharsets.UTF_8)) {
                    String[] p = line.split(" ", 3);
                    if (p.length == 3 && Long.parseLong(p[1]) > now) {
                        remembered.put(p[0], new Remembered(p[2], Long.parseLong(p[1])));
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            remembered.clear(); // unreadable: everyone just logs in again
        }
    }

    /** A new remember-me token for this account (the cookie's value). */
    public String remember(String admin) {
        String token = Passwords.token();
        remembered.put(hash(token), new Remembered(admin, System.currentTimeMillis() + REMEMBER_MS));
        saveRemembered();
        return token;
    }

    /** A logged-in session from a remember-me token, or null if it's unknown or expired. */
    public Session fromRemembered(String token, String ip) {
        if (token == null || token.isEmpty() || token.length() > 200) {
            return null;
        }
        Remembered r = remembered.get(hash(token));
        if (r == null) {
            return null;
        }
        if (r.expires() < System.currentTimeMillis()) {
            forget(token);
            return null;
        }
        return create(r.admin(), ip);
    }

    public void forget(String token) {
        if (token != null && remembered.remove(hash(token)) != null) {
            saveRemembered();
        }
    }

    private synchronized void saveRemembered() {
        if (rememberFile == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        remembered.entrySet().removeIf(e -> e.getValue().expires() < now);
        remembered.forEach((h, r) -> lines.add(h + " " + r.expires() + " " + r.admin()));
        try {
            Path tmp = rememberFile.resolveSibling("remember.txt.tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, rememberFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // still works until the next restart
        }
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
