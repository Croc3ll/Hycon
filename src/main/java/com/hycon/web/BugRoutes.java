package com.hycon.web;

import com.hycon.audit.AuditLog;
import com.hycon.auth.Level;
import com.hycon.game.BugReports;

import java.io.IOException;

/** The Bugs tab: players' {@code /bug} reports, with where they stood and the server log around it. */
public final class BugRoutes {

    private final WebServer web;
    private final BugReports bugs;
    private final AuditLog audit;

    public BugRoutes(WebServer web, BugReports bugs, AuditLog audit) {
        this.web = web;
        this.bugs = bugs;
        this.audit = audit;
    }

    public void register() {
        web.get("/api/bugs", Level.MODERATOR, this::list);
        web.post("/api/bugs/close", Level.MODERATOR, this::close);
    }

    private void list(Req r) throws IOException {
        String s = r.query("status");
        BugReports.Status status = s == null || s.equals("all") ? null : BugReports.Status.valueOf(s.toUpperCase());
        Json j = new Json().beginObject().field("open", bugs.list(BugReports.Status.OPEN, 10_000).size()).name("bugs").beginArray();
        for (BugReports.Bug b : bugs.list(status, 500)) {
            BugReports.write(j.beginObject(), b).endObject();
        }
        r.ok(j.endArray().endObject());
    }

    /** Marks a bug report fixed or dismissed. */
    private void close(Req r) throws IOException {
        BugReports.Status status = "dismissed".equalsIgnoreCase(r.str("status")) ? BugReports.Status.DISMISSED : BugReports.Status.FIXED;
        Object id = r.body.get("id");
        if (!(id instanceof Double d)) {
            r.error(400, "Which bug report?");
            return;
        }
        String note = r.str("note") == null ? "" : r.str("note").strip();
        if (note.length() > 300) {
            note = note.substring(0, 300);
        }
        try {
            BugReports.Bug b = bugs.close(d.longValue(), status, r.who(), note);
            audit.record(r.who(), r.ip, "bug." + status.name().toLowerCase(), b.reporterName(), b.text(), true);
            r.ok(new Json().beginObject().field("ok", true).field("message", "Bug report " + status.name().toLowerCase()).endObject());
        } catch (IllegalArgumentException e) {
            r.error(404, e.getMessage());
        }
    }
}
