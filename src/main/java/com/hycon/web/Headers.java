package com.hycon.web;

/** Browser security headers sent with every response, page and API alike. */
final class Headers {

    /**
     * Content-Security-Policy: scripts and styles only from the panel's own files (no inline script),
     * no other sites contacted, and the panel can't be put in a frame (stops click-jacking a ban button).
     */
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private Headers() {
    }

    static void security(com.sun.net.httpserver.Headers h) {
        h.set("Content-Security-Policy", CSP);
        h.set("X-Frame-Options", "DENY");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }
}
