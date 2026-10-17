package com.hycon.sync;

import com.hycon.audit.AuditLog;
import com.hycon.auth.LoginLimiter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The share port: a second, tiny web server that only answers {@code GET /share/bans} to requests
 * signed with one of this server's share keys. It is separate from the panel so the panel can stay
 * on 127.0.0.1 while other servers still reach the ban feed. It only runs while at least one share
 * key exists, so a server that doesn't share opens no extra port.
 */
public final class ShareServer {

    public static final String PATH = "/share/bans";

    private final SharingStore store;
    private final BanSync sync;
    private final AuditLog audit;
    private final LoginLimiter limiter;
    private final String bind;
    private final int port;
    private HttpServer server;
    private ExecutorService executor;
    private String error;

    public ShareServer(SharingStore store, BanSync sync, AuditLog audit, LoginLimiter limiter, String bind, int port) {
        this.store = store;
        this.sync = sync;
        this.audit = audit;
        this.limiter = limiter;
        this.bind = bind;
        this.port = port;
    }

    public int port() {
        return port;
    }

    public synchronized boolean running() {
        return server != null;
    }

    /** Why the port couldn't be opened, or null. */
    public synchronized String error() {
        return error;
    }

    /** Starts or stops the listener to match whether any key exists. */
    public synchronized void update() {
        if (!store.keys().isEmpty() && server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress(bind, port), 0);
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "Hycon-Share");
                    t.setDaemon(true);
                    return t;
                });
                server.setExecutor(executor);
                server.createContext("/", this::handle);
                server.start();
                error = null;
            } catch (IOException e) {
                server = null;
                error = "Could not open share port " + port + ": " + e.getMessage();
            }
        } else if (store.keys().isEmpty() && server != null) {
            stop();
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handle(HttpExchange ex) {
        try (ex) {
            String ip = ex.getRemoteAddress().getAddress().getHostAddress();
            if (!ex.getRequestMethod().equals("GET") || !ex.getRequestURI().getPath().equals(PATH)) {
                send(ex, 404, "{\"error\":\"Not found\"}");
                return;
            }
            if (limiter.retryAfter(ip, "share-feed") > 0) {
                send(ex, 429, "{\"error\":\"Too many bad requests\"}");
                return;
            }
            String who = store.verify(ex.getRequestHeaders().getFirst("Authorization"), PATH);
            if (who == null) {
                limiter.fail(ip, "share-feed");
                audit.record("-", ip, "share.feed", null, "bad or missing signature (or clocks more than 5 min apart)", false);
                send(ex, 401, "{\"error\":\"Bad signature\"}");
                return;
            }
            send(ex, 200, sync.feed());
        } catch (IOException | RuntimeException ignored) {
            // the other server went away mid-response; it retries on its next check
        }
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
