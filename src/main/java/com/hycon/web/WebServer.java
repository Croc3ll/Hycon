package com.hycon.web;

import com.hycon.auth.AdminStore;
import com.hycon.auth.Level;
import com.hycon.auth.Sessions;
import com.hycon.config.HyconConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The panel's web server (the JDK's built-in one) and every security check that applies to all
 * requests, so route handlers can't forget one:
 * <ol>
 *   <li>Host header must be a known name (blocks DNS rebinding: a hostile page whose domain
 *       suddenly points at 127.0.0.1 still sends its own domain as Host).</li>
 *   <li>Every API call has a session (cookie {@code hycon_sid}: HttpOnly, SameSite=Strict).</li>
 *   <li>Every POST needs JSON content, a same-origin Origin header when the browser sends one, and
 *       the session's CSRF token in {@code X-CSRF-Token}. The login POST included.</li>
 *   <li>Each route names the lowest {@link Level} allowed; accounts that must change their password
 *       can do nothing else until they have.</li>
 * </ol>
 */
public final class WebServer {

    @FunctionalInterface
    public interface Handler {
        void handle(Req r) throws IOException;
    }

    /** {@code min == null}: allowed without logging in. */
    private record Route(String method, Level min, boolean duringPasswordChange, Handler handler) {
    }

    private static final String COOKIE = "hycon_sid";
    private static final int MAX_BODY = 16 * 1024;
    private static final Map<String, String> STATIC = Map.of(
            "/", "web/index.html", "/index.html", "web/index.html",
            "/app.js", "web/app.js", "/style.css", "web/style.css", "/logo.svg", "web/logo.svg", "/graph.js", "web/graph.js", "/heatmap.js", "web/heatmap.js");

    private final HyconConfig config;
    private final Sessions sessions;
    private final AdminStore admins;
    private final Set<String> hosts = new HashSet<>();
    private final Map<String, Route> routes = new HashMap<>();
    private final Map<String, byte[]> files = new HashMap<>();
    private HttpServer server;
    private ExecutorService executor;

    public WebServer(HyconConfig config, Sessions sessions, AdminStore admins) throws IOException {
        this.config = config;
        this.sessions = sessions;
        this.admins = admins;
        hosts.addAll(List.of("127.0.0.1", "localhost", "[::1]"));
        hosts.addAll(config.allowedHosts());
        for (String res : Set.copyOf(STATIC.values())) {
            try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(res)) {
                if (in == null) {
                    throw new IOException("Missing " + res + " inside the Hycon jar");
                }
                files.put(res, in.readAllBytes()); // read once; they never change while running
            }
        }
    }

    public void get(String path, Level min, Handler h) {
        routes.put("GET " + path, new Route("GET", min, false, h));
    }

    public void post(String path, Level min, Handler h) {
        routes.put("POST " + path, new Route("POST", min, false, h));
    }

    /** A route that still works while the account must pick a new password (session, password, logout). */
    public void always(String method, String path, Level min, Handler h) {
        routes.put(method + " " + path, new Route(method, min, true, h));
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
        AtomicInteger n = new AtomicInteger();
        // A few threads, because a login check deliberately takes a quarter second (see Passwords).
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Hycon-Web-" + n.incrementAndGet());
            t.setDaemon(true); // never keeps the server process alive on shutdown
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange ex) {
        try (ex) {
            Req r = new Req(ex, clientIp(ex));
            try {
                serve(r);
            } catch (RuntimeException e) {
                r.error(500, "Server error");
            }
        } catch (IOException ignored) {
            // the browser went away mid-response
        }
    }

    private void serve(Req r) throws IOException {
        HttpExchange ex = r.ex;
        if (!hostAllowed(ex)) {
            r.error(421, "Unknown host name; add it to AllowedHosts in hycon.json");
            return;
        }
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String res = STATIC.get(path);
        if (res != null && method.equals("GET")) {
            String type = res.endsWith(".svg") ? "image/svg+xml"
                    : res.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : res.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
            r.send(200, type, files.get(res));
            return;
        }
        Route route = routes.get(method + " " + path);
        if (route == null) {
            r.error(404, "Not found");
            return;
        }

        r.session = sessions.get(cookie(ex));
        if (r.session == null || r.session.admin == null) {
            // Remember me: a saved login on this browser starts a logged-in session by itself.
            Sessions.Session remembered = sessions.fromRemembered(cookie(ex, REMEMBER_COOKIE), r.ip);
            if (remembered != null) {
                sessions.remove(r.session);
                r.session = remembered;
                r.newSession = remembered;
            }
        }
        if (r.session == null) {
            // Only the bootstrap call may start a session; anything else means the old one expired.
            if (!path.equals("/api/session")) {
                r.error(401, "Not logged in");
                return;
            }
            r.session = sessions.create(null, r.ip);
            r.newSession = r.session;
        }
        if (r.session.admin != null) {
            r.admin = admins.get(r.session.admin);
            if (r.admin == null) {
                sessions.remove(r.session); // account was deleted while logged in
                r.error(401, "Not logged in");
                return;
            }
        }

        if (method.equals("POST") && !postAllowed(r)) {
            return;
        }
        if (route.min != null) {
            if (r.admin == null) {
                r.error(401, "Not logged in");
                return;
            }
            if (r.admin.mustChange() && !route.duringPasswordChange) {
                r.error(403, "Choose a new password first");
                return;
            }
            if (!r.admin.level().atLeast(route.min)) {
                r.error(403, "Needs " + route.min + " level");
                return;
            }
        }
        if (r.newSession != null) {
            setCookie(ex, r.newSession); // headers must be set before the handler writes the body
        }
        route.handler.handle(r);
    }

    /** Switches the request to a new session (login) and sends its cookie; call before responding. */
    public void issueCookie(Req r, Sessions.Session s) {
        r.login(s);
        setCookie(r.ex, s);
    }

    private boolean postAllowed(Req r) throws IOException {
        HttpExchange ex = r.ex;
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        // Plain HTML forms can't send JSON, and a cross-site fetch with JSON triggers a CORS check we never pass.
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            r.error(415, "JSON only");
            return false;
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !sameOrigin(origin, ex.getRequestHeaders().getFirst("Host"))) {
            r.error(403, "Cross-site request refused");
            return false;
        }
        String site = ex.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) {
            r.error(403, "Cross-site request refused");
            return false;
        }
        if (!r.session.csrfMatches(ex.getRequestHeaders().getFirst("X-CSRF-Token"))) {
            r.error(403, "Bad or missing CSRF token; reload the page");
            return false;
        }
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            r.error(413, "Request too large");
            return false;
        }
        try {
            r.body = raw.length == 0 ? Map.of() : JsonReader.object(new String(raw, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            r.error(400, "Bad JSON");
            return false;
        }
        return true;
    }

    private boolean hostAllowed(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        return host != null && hosts.contains(stripPort(host).toLowerCase(Locale.ROOT));
    }

    private static boolean sameOrigin(String origin, String host) {
        try {
            // "http://127.0.0.1:8096" has authority "127.0.0.1:8096", exactly what the browser sends as Host.
            String authority = URI.create(origin).getRawAuthority();
            return host != null && authority != null && authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String stripPort(String host) {
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }
        int colon = host.lastIndexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    private String clientIp(HttpExchange ex) {
        if (config.trustProxy()) {
            // Cloudflare (tunnel or proxy) sends the visitor's address here; clients can't set it.
            String cf = ex.getRequestHeaders().getFirst("CF-Connecting-IP");
            if (cf != null && !cf.isBlank()) {
                return cf.trim();
            }
            String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                // The last entry is the one our own proxy added; earlier ones are client-controlled.
                String[] parts = fwd.split(",");
                return parts[parts.length - 1].trim();
            }
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static String cookie(HttpExchange ex) {
        return cookie(ex, COOKIE);
    }

    public static final String REMEMBER_COOKIE = "hycon_remember";

    public String rememberCookie(Req r) {
        return cookie(r.ex, REMEMBER_COOKIE);
    }

    /** Sets (or with null, clears) the remember-me cookie; call before responding. */
    public void setRememberCookie(Req r, String token) {
        r.ex.getResponseHeaders().add("Set-Cookie", REMEMBER_COOKIE + "=" + (token == null ? "" : token) + "; Path=/; HttpOnly; SameSite=Strict"
                + "; Max-Age=" + (token == null ? 0 : Sessions.REMEMBER_MS / 1000) + (config.secureCookie() ? "; Secure" : ""));
    }

    private static String cookie(HttpExchange ex, String COOKIE) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String h : headers) {
            for (String part : h.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=")) {
                    return p.substring(COOKIE.length() + 1);
                }
            }
        }
        return null;
    }

    private void setCookie(HttpExchange ex, Sessions.Session s) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + s.id + "; Path=/; HttpOnly; SameSite=Strict"
                + (config.secureCookie() ? "; Secure" : ""));
    }
}
