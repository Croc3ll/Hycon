package com.hycon.game;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.auth.AuthConfig;
import com.hypixel.hytale.server.core.auth.ServerAuthManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;

/**
 * Test probe: asks Hytale's Server Discovery for the first page of its server list, signed in
 * with this server's own session token (the one the discovery heartbeat already uses). We want
 * to learn whether a server token may read the list at all. One request at startup, logged.
 */
public final class DiscoveryProbe {

    private static final String LISTINGS_URL = "https://server-discovery.hytale.com/servers/listings?offset=0";
    /** Long enough to see the shape of the reply without flooding the log. */
    private static final int LOG_CHARS = 2000;
    /** The server signs in a few seconds after boot; asking earlier has no token yet. */
    private static final Duration START_DELAY = Duration.ofSeconds(30);

    private final HytaleLogger log;

    public DiscoveryProbe(HytaleLogger log) {
        this.log = log;
    }

    public void start() {
        Thread.ofVirtual().name("Hycon-DiscoveryProbe").start(this::run);
    }

    private void run() {
        try {
            Thread.sleep(START_DELAY);
            String token = ServerAuthManager.getInstance().getSessionToken();
            if (token == null || token.isEmpty()) {
                log.at(Level.INFO).log("Hycon discovery probe: the server has no session token (not signed in)");
                return;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(LISTINGS_URL))
                    .header("User-Agent", AuthConfig.USER_AGENT)
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();
                log.at(Level.INFO).log("Hycon discovery probe: status %d, %d chars: %s", response.statusCode(), body.length(),
                        body.length() > LOG_CHARS ? body.substring(0, LOG_CHARS) + "..." : body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.at(Level.INFO).log("Hycon discovery probe failed: %s", e);
        }
    }
}
