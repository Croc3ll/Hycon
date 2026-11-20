package com.hycon.web;

import com.hycon.audit.AuditLog;
import com.hycon.auth.Level;
import com.hycon.game.AmpClient;
import com.hycon.game.ConsoleRunner;
import com.hycon.game.Schedule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The Schedule tab: daily restarts with chat countdowns, a one-off restart, and repeating announcements. */
public final class ScheduleRoutes {

    private static final int MAX_DELAY_MINUTES = 24 * 60;

    private final WebServer web;
    private final Schedule schedule;
    private final ConsoleRunner console;
    private final AmpClient amp;
    private final AuditLog audit;

    public ScheduleRoutes(WebServer web, Schedule schedule, ConsoleRunner console, AmpClient amp, AuditLog audit) {
        this.web = web;
        this.schedule = schedule;
        this.console = console;
        this.amp = amp;
        this.audit = audit;
    }

    public void register() {
        web.get("/api/schedule", Level.ADMIN, this::show);
        web.post("/api/schedule/save", Level.ADMIN, this::save);
        web.post("/api/schedule/restart-in", Level.ADMIN, this::restartIn);
        web.post("/api/schedule/cancel", Level.ADMIN, this::cancel);
    }

    /** Restarting is off limits when BlockedCommands blocks stop or restart, like the Stop button. */
    private boolean blocked() {
        return console.isBlocked("stop") || console.isBlocked("restart");
    }

    private void show(Req r) throws IOException {
        Schedule.Settings s = schedule.settings();
        Json j = new Json().beginObject().field("amp", amp.configured()).field("blocked", blocked())
                .field("zone", java.time.ZoneId.systemDefault().getId()).field("next", schedule.nextRestart())
                .field("oneOff", schedule.oneOffAt()).name("restartTimes").beginArray();
        s.restartTimes().forEach(j::value);
        j.endArray().name("warnMinutes").beginArray();
        s.warnMinutes().forEach(m -> j.value(m));
        j.endArray().name("announcements").beginArray();
        for (Schedule.Announcement a : s.announcements()) {
            j.beginObject().field("text", a.text()).field("everyMinutes", a.everyMinutes()).endObject();
        }
        r.ok(j.endArray().endObject());
    }

    private void save(Req r) throws IOException {
        List<String> times = new ArrayList<>();
        List<Integer> warns = new ArrayList<>();
        List<Schedule.Announcement> ann = new ArrayList<>();
        if (r.body.get("restartTimes") instanceof List<?> l) {
            l.forEach(o -> { if (o instanceof String s && !s.isBlank()) times.add(s); });
        }
        if (r.body.get("warnMinutes") instanceof List<?> l) {
            l.forEach(o -> { if (o instanceof Double d) warns.add(d.intValue()); });
        }
        if (r.body.get("announcements") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> a && a.get("text") instanceof String t && a.get("everyMinutes") instanceof Double d) {
                    ann.add(new Schedule.Announcement(t, d.intValue()));
                }
            }
        }
        if (!times.isEmpty() && blocked()) {
            r.error(403, "Restarting is blocked in hycon.json");
            return;
        }
        try {
            schedule.save(new Schedule.Settings(times, warns, ann));
            audit.record(r.who(), r.ip, "schedule", "", String.join(" ", times) + "; " + ann.size() + " announcements", true);
            r.ok(new Json().beginObject().field("ok", true).field("message", "Schedule saved").endObject());
        } catch (IllegalArgumentException e) {
            r.error(400, e.getMessage());
        }
    }

    private void restartIn(Req r) throws IOException {
        if (blocked()) {
            r.error(403, "Restarting is blocked in hycon.json");
            return;
        }
        int minutes = r.body.get("minutes") instanceof Double d ? d.intValue() : -1;
        if (minutes < 1 || minutes > MAX_DELAY_MINUTES) {
            r.error(400, "Pick 1 to " + MAX_DELAY_MINUTES + " minutes");
            return;
        }
        schedule.restartIn(minutes);
        audit.record(r.who(), r.ip, "schedule.restart", "", "in " + minutes + " min", true);
        r.ok(new Json().beginObject().field("ok", true).field("message", "Restart in " + minutes + " min; players get countdown warnings").endObject());
    }

    private void cancel(Req r) throws IOException {
        schedule.cancelOneOff();
        audit.record(r.who(), r.ip, "schedule.cancel", "", null, true);
        r.ok(new Json().beginObject().field("ok", true).field("message", "One-off restart cancelled").endObject());
    }
}
