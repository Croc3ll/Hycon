package com.hycon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Asks GitHub for the newest Hycon release at start and every few hours. When a newer one is out
 * it warns once in the console, and {@link #notice()} gives the line admins see when they join.
 */
final class UpdateCheck {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final int FIRST_CHECK_SECONDS = 20;
    /** Often enough that owners hear about a release the same day. */
    private static final int CHECK_HOURS = 6;

    private static final String LATEST_RELEASE = "https://api.github.com/repos/Croc3ll/Hycon/releases/latest";

    private final String current;
    private final HytaleLogger logger;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private ScheduledExecutorService thread;
    private volatile String latest;
    private volatile String download;

    UpdateCheck(String current, HytaleLogger logger) {
        this.current = current;
        this.logger = logger;
    }

    void start() {
        thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Hycon-UpdateCheck");
            t.setDaemon(true);
            return t;
        });
        thread.scheduleWithFixedDelay(this::check, FIRST_CHECK_SECONDS, CHECK_HOURS * 3600L, TimeUnit.SECONDS);
    }

    void stop() {
        if (thread != null) {
            thread.shutdownNow();
        }
    }

    /** What to tell admins, or null when this copy is up to date. */
    String notice() {
        String newest = latest;
        return newest == null ? null : "Hycon " + newest + " is out (you run " + current + "). Get it at " + download;
    }

    private void check() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST_RELEASE)).timeout(TIMEOUT)
                    .header("User-Agent", "Hycon/" + current).header("Accept", "application/vnd.github+json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                return; // GitHub's rate limit or an outage: try again next time
            }
            JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
            String newest = body.get("tag_name").getAsString().replaceFirst("^v", "");
            if (!isNewer(newest, current) || newest.equals(latest)) {
                return;
            }
            download = body.get("html_url").getAsString();
            latest = newest;
            logger.at(Level.WARNING).log("%s", notice());
        } catch (Exception e) {
            // A failed check only means no notice this time; it isn't worth a warning.
        }
    }

    /** True when {@code a} is a higher dotted version than {@code b} (1.10.0 beats 1.9.0). */
    static boolean isNewer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) {
                return p > q;
            }
        }
        return false;
    }

    private static int number(String part) {
        String digits = part.replaceAll("\\D.*", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }
}
