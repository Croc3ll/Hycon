package com.hycon.analytics;

import com.hycon.api.HyconApi;
import com.hycon.api.HyconPanel;
import com.hycon.api.PanelData;
import com.hycon.game.BugReports;
import com.hycon.game.Moderation;
import com.hycon.game.Reports;

import java.util.List;
import java.util.function.Supplier;

/**
 * "At a glance": one short page for staff on a phone. Server health, players online, open reports and bugs, and who
 * is on where. Read only: kicking and banning stay on the Players tab, where they are checked and logged.
 */
public final class GlancePanel implements HyconPanel {

    /** Health score and its reasons, or null when the profiler is off. */
    public record Health(int score, String word, List<String> reasons) {
    }

    private final Moderation moderation;
    private final Reports reports;
    private final BugReports bugs;
    private final Supplier<Health> health;

    public GlancePanel(Moderation moderation, Reports reports, BugReports bugs, Supplier<Health> health) {
        this.moderation = moderation;
        this.reports = reports;
        this.bugs = bugs;
        this.health = health;
    }

    @Override
    public String id() {
        return "glance";
    }

    @Override
    public String title() {
        return "At a glance";
    }

    @Override
    public int minLevel() {
        return HyconApi.MODERATOR;
    }

    @Override
    public void render(PanelData out) {
        Health h = health.get();
        List<Moderation.OnlinePlayer> online = moderation.online();
        int openReports = reports.list(Reports.Status.OPEN, 1000).size();
        int openBugs = bugs.list(BugReports.Status.OPEN, 1000).size();
        if (h != null) {
            out.stat("Server health", h.score() < 0 ? "..." : h.score() + " / 100", h.word()
                    + (h.reasons().isEmpty() ? "" : ": " + String.join(", ", h.reasons().subList(0, Math.min(2, h.reasons().size())))));
        }
        out.stat("Players online", String.valueOf(online.size()))
                .stat("Open reports", String.valueOf(openReports), openReports == 0 ? "Nothing to handle" : "See the Reports tab")
                .stat("Open bug reports", String.valueOf(openBugs));
        PanelData.Table t = out.table("Online now", "Player", "World");
        online.stream().sorted(java.util.Comparator.comparing(p -> p.name().toLowerCase(java.util.Locale.ROOT)))
                .forEach(p -> t.row(p.name(), p.world()));
        out.note("A short page for checking in from a phone. Kick, ban and the details are on the Players, Reports and Performance tabs.");
    }
}
