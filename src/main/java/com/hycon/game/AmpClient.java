package com.hycon.game;

import com.hycon.config.HyconConfig;
import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Asks AMP (the host panel) to restart or stop this server instance through AMP's own web API:
 * {@code /API/Core/Login} for a session, then {@code /API/Core/Restart} or {@code /API/Core/Stop}.
 * Hytale can't start itself again, so a restart has to come from AMP.
 */
public final class AmpClient {

    private final Supplier<HyconConfig> config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Reads the AMP address and login from {@code config} on each use, so edits to hycon.json apply at once. */
    public AmpClient(Supplier<HyconConfig> config) {
        this.config = config;
    }

    public boolean configured() {
        HyconConfig c = config.get();
        return !url().isEmpty() && !c.ampUser().isEmpty() && !c.ampPassword().isEmpty();
    }

    /** Runs Core/Restart or Core/Stop. Returns null when AMP accepted it, else why not. */
    public String run(String action) {
        HyconConfig c = config.get();
        String url = url();
        try {
            Map<String, Object> login = call(url, "Core/Login", new Json().beginObject().field("username", c.ampUser())
                    .field("password", c.ampPassword()).field("token", "").field("rememberMe", false).endObject().toString());
            Object sid = login.get("sessionID");
            if (!(sid instanceof String s) || s.isEmpty() || Boolean.FALSE.equals(login.get("success"))) {
                return "AMP refused the login in hycon.json (AmpUser / AmpPassword)";
            }
            call(url, "Core/" + action, new Json().beginObject().field("SESSIONID", s).endObject().toString());
            return null;
        } catch (IOException | IllegalArgumentException e) {
            return "Couldn't reach AMP at " + url + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
    }

    private String url() {
        return config.get().ampUrl().replaceAll("/+$", "");
    }

    private Map<String, Object> call(String url, String method, String body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/API/" + method))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + res.statusCode());
        }
        String b = res.body().strip();
        return b.startsWith("{") ? JsonReader.object(b) : Map.of();
    }
}
