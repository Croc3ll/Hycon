package com.hycon.game;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Asks AMP (the host panel) to restart or stop this server instance through AMP's own web API:
 * {@code /API/Core/Login} for a session, then {@code /API/Core/Restart} or {@code /API/Core/Stop}.
 * Hytale can't start itself again, so a restart has to come from AMP.
 */
public final class AmpClient {

    private final String url;
    private final String user;
    private final String password;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AmpClient(String url, String user, String password) {
        this.url = url == null ? "" : url.replaceAll("/+$", "");
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
    }

    public boolean configured() {
        return !url.isEmpty() && !user.isEmpty() && !password.isEmpty();
    }

    /** Runs Core/Restart or Core/Stop. Returns null when AMP accepted it, else why not. */
    public String run(String action) {
        try {
            Map<String, Object> login = call("Core/Login", new Json().beginObject().field("username", user)
                    .field("password", password).field("token", "").field("rememberMe", false).endObject().toString());
            Object sid = login.get("sessionID");
            if (!(sid instanceof String s) || s.isEmpty() || Boolean.FALSE.equals(login.get("success"))) {
                return "AMP refused the login in hycon.json (AmpUser / AmpPassword)";
            }
            call("Core/" + action, new Json().beginObject().field("SESSIONID", s).endObject().toString());
            return null;
        } catch (IOException | IllegalArgumentException e) {
            return "Couldn't reach AMP at " + url + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
    }

    private Map<String, Object> call(String method, String body) throws IOException, InterruptedException {
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
