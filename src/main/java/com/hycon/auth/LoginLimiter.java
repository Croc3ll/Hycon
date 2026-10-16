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
 */
public final class LoginLimiter {

    private final int attempts;
    private final long windowMs;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public LoginLimiter(int attempts, long windowMs) {
        this.attempts = attempts;
        this.windowMs = windowMs;
    }

    /** Milliseconds until another try is allowed, or 0 if allowed now. */
    public long retryAfter(String ip, String name) {
        long now = System.currentTimeMillis();
        return Math.max(wait("ip:" + ip, now), wait("name:" + name.toLowerCase(Locale.ROOT), now));
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
