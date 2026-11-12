package com.hycon.web;

import com.hycon.auth.AdminStore;
import com.hycon.auth.Sessions;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** One API request as the route handlers see it: who is asking, from where, and with what. */
public final class Req {
    /** Bodies smaller than this go out as they are: compressing them saves almost nothing. */
    private static final int GZIP_FROM = 1024;

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
        h.set("Vary", "Accept-Encoding");
        Headers.security(h);
        // Replay data and three.js shrink to a fifth or less, so pages load faster on a slow line.
        if (body.length >= GZIP_FROM && acceptsGzip()) {
            body = gzip(body);
            h.set("Content-Encoding", "gzip");
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private boolean acceptsGzip() {
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        return accept != null && accept.contains("gzip");
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream(body.length / 4);
        try (GZIPOutputStream out = new GZIPOutputStream(packed)) {
            out.write(body);
        }
        return packed.toByteArray();
    }
}
