package com.hycon.web;

import com.hycon.auth.AdminStore;
import com.hycon.auth.Sessions;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** One API request as the route handlers see it: who is asking, from where, and with what. */
public final class Req {

    final HttpExchange ex;
    public final String ip;
    /** Never null by the time a handler runs. */
    public Sessions.Session session;
    /** Logged-in account, or null. */
    public AdminStore.Admin admin;
    /** Parsed JSON body of a POST; empty for GET. */
    public Map<String, Object> body = Map.of();
    boolean sent;
    /** Set by the login route: the new session whose cookie must be sent. */
    Sessions.Session newSession;

    Req(HttpExchange ex, String ip) {
        this.ex = ex;
        this.ip = ip;
    }

    public String str(String key) {
        Object v = body.get(key);
        return v instanceof String s ? s : null;
    }

    public String query(String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Account name for the audit log ("-" before login). */
    public String who() {
        return admin != null ? admin.name() : "-";
    }

    public void login(Sessions.Session s) {
        this.newSession = s;
        this.session = s;
    }

    public void json(int status, Json j) throws IOException {
        send(status, "application/json; charset=utf-8", j.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void ok(Json j) throws IOException {
        json(200, j);
    }

    public void error(int status, String message) throws IOException {
        json(status, new Json().beginObject().field("error", message).endObject());
    }

    void send(int status, String type, byte[] body) throws IOException {
        if (sent) {
            return;
        }
        sent = true;
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        Headers.security(h);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
