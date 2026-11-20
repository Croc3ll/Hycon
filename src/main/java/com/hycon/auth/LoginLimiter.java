package com.hycon.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits failed logins, counted separately per client address and per account name: the address
 * limit stops one machine guessing many accounts, the name limit stops many machines guessing one.
 * A success does not clear the counters, so a correct guess can't "refill" an attacker's budget.
 *
 * <p>An address that has logged into an account before skips that account's name limit (its own
 * address limit still applies). Otherwise anyone could keep the owner locked out of their own
 * account just by failing five logins with its name.
 */
public final class LoginLimiter {

    private final int attempts;
    private final long windowMs;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    /** "address account" pairs that logged in, with when; in memory, so a restart forgets them. */
    private final Map<String, Long> knownLogins = new ConcurrentHashMap<>();
    /** How long a successful login keeps its address trusted for that account. */
    private static final long KNOWN_MS = 30L * 24 * 60 * 60_000;
    /** Bounds the trusted pairs; only a busy panel with many staff gets near it. */
    private static final int MAX_KNOWN = 10_000;

    public LoginLimiter(int attempts, long windowMs) {
        this.attempts = attempts;
        this.windowMs = windowMs;
    }

    /** Milliseconds until another try is allowed, or 0 if allowed now. */
    public long retryAfter(String ip, String name) {
        long now = System.currentTimeMillis();
        long ipWait = wait("ip:" + ip, now);
        Long known = knownLogins.get(pair(ip, name));
        if (known != null && now - known < KNOWN_MS) {
            return ipWait;
        }
        return Math.max(ipWait, wait("name:" + name.toLowerCase(Locale.ROOT), now));
    }

    /** Remembers that this address logged into this account. */
    public void succeeded(String ip, String name) {
        long now = System.currentTimeMillis();
        if (knownLogins.size() >= MAX_KNOWN) {
            knownLogins.values().removeIf(t -> now - t > KNOWN_MS);
            if (knownLogins.size() >= MAX_KNOWN) {
                knownLogins.clear(); // still full: everyone just counts against the name limit again
            }
        }
        knownLogins.put(pair(ip, name), now);
    }

    private static String pair(String ip, String name) {
        return ip + " " + name.toLowerCase(Locale.ROOT);
    }

    public void fail(String ip, String name) {
        long now = System.currentTimeMillis();
        add("ip:" + ip, now);
        add("name:" + name.toLowerCase(Locale.ROOT), now);
        if (failures.size() > 10_000) {
            // Bounded memory under a spray of random names: forget keys whose window is over.
            failures.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    Long last = e.getValue().peekLast();
                    return last == null || now - last > windowMs;
                }
            });
        }
    }

    private long wait(String key, long now) {
        Deque<Long> q = failures.get(key);
        if (q == null) {
            return 0;
        }
        synchronized (q) {
            prune(q, now);
            return q.size() < attempts ? 0 : q.peekFirst() + windowMs - now;
        }
    }

    private void add(String key, long now) {
        Deque<Long> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now);
            q.addLast(now);
        }
    }

    private void prune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
            q.pollFirst();
        }
    }
}
