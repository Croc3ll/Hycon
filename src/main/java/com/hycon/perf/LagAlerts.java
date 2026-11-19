package com.hycon.perf;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hycon.config.HyconConfig;
import com.hycon.perf.PerfData.Minute;
import com.hycon.perf.PerfData.Spike;
import com.hycon.web.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Tells staff (permission {@value #PERMISSION}) and an optional Discord webhook when one plugin
 * keeps using a big share of a world thread, a spike lasts over {@code AlertSpikeMs}, a minute has
 * {@code AlertSpikesPerMinute} spikes, or memory stays over {@code AlertMemoryPercent}. Each alert repeats at
 * most every 15 minutes. Called on the profiler thread; sending never blocks it.
 */
final class LagAlerts {

    static final String PERMISSION = "hycon.lag.alerts";
    /** Minutes in a row over the limit before a plugin is reported, so one busy minute isn't noise. */
    private static final int STREAK = 3;
    private static final long COOLDOWN_MS = TimeUnit.MINUTES.toMillis(15);

    private final HytaleLogger log;
    private final Supplier<HyconConfig> config;
    private final Map<String, Integer> streaks = new HashMap<>();
    private int memoryStreak;
    private final Map<String, Long> lastSent = new HashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    LagAlerts(HytaleLogger log, Supplier<HyconConfig> config) {
        this.log = log;
        this.config = config;
    }

    void minute(Minute m) {
        HyconConfig c = config.get();
        if (c.lagAlertPercent() > 0) {
            if (c.alertSpikesPerMinute() > 0 && m.spikes() >= c.alertSpikesPerMinute()) {
                send("spikes", String.format("%d lag spikes in the last minute. Check the Performance tab.", m.spikes()));
            }
            double memory = m.heapMaxMb() <= 0 ? 0 : m.heapMb() * 100.0 / m.heapMaxMb();
            memoryStreak = c.alertMemoryPercent() > 0 && memory >= c.alertMemoryPercent() ? memoryStreak + 1 : 0;
            if (memoryStreak >= STREAK) {
                send("memory", String.format("Memory has been over %d%% for %d minutes (%d of %d MB).", c.alertMemoryPercent(),
                        memoryStreak, m.heapMb(), m.heapMaxMb()));
            }
        }
        int percent = c.lagAlertPercent();
        if (percent <= 0 || m.worldMs() <= 0) {
            streaks.clear();
            return;
        }
        Map<String, Integer> next = new HashMap<>();
        m.owners().forEach((owner, ms) -> {
            double share = ms / m.worldMs() * 100;
            if (Owners.isPlugin(owner) && share >= percent) {
                int streak = streaks.getOrDefault(owner, 0) + 1;
                next.put(owner, streak);
                if (streak >= STREAK) {
                    send("plugin:" + owner, String.format("%s has used %.0f%% of the world threads for %d minutes. Check the Performance tab.",
                            owner, share, streak));
                }
            }
        });
        streaks.clear();
        streaks.putAll(next);
    }

    void spike(Spike s) {
        int limit = config.get().alertSpikeMs();
        if (config.get().lagAlertPercent() > 0 && limit > 0 && s.ms() >= limit) {
            String in = s.system() == null || s.system().isEmpty() ? "" : " in " + s.system();
            send("spike:" + s.lane(), String.format("Lag spike: %s froze for %.1f s, mostly %s%s.", s.lane(), s.ms() / 1000.0, s.cause(), in));
        }
    }

    private void send(String key, String text) {
        long now = System.currentTimeMillis();
        if (now - lastSent.getOrDefault(key, 0L) < COOLDOWN_MS) {
            return;
        }
        lastSent.put(key, now);
        log.at(Level.WARNING).log("Hycon lag: %s", text);
        Message note = Message.raw("[Lag] " + text).color("#FFAA00");
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (p.hasPermission(PERMISSION)) {
                p.sendMessage(note);
            }
        }
        String webhook = config.get().lagWebhook();
        if (webhook.startsWith("https://")) {
            String body = new Json().beginObject().field("content", "[Lag] " + text).endObject().toString();
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(e -> {
                log.at(Level.FINE).log("Hycon lag webhook failed: %s", e);
                return null;
            });
        }
    }
}
