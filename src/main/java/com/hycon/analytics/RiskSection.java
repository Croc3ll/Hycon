package com.hycon.analytics;

import com.hycon.api.HyconApi;
import com.hycon.api.HyconProfileSection;
import com.hycon.api.HyconRisk;
import com.hycon.api.PanelData;
import com.hycon.game.KnownPlayers;
import com.hycon.game.Moderation;
import com.hycon.game.PlayerRecords;
import com.hycon.game.Reports;
import com.hycon.game.Ties;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A rough risk score (0-100) on every profile, from what's known about the player: their
 * moderation record, reports, accounts sharing their connection (and whether those are banned),
 * how new they are, plus whatever plugins add (PrisonCore: economy alerts and rebalances).
 * It's a pointer for staff, not proof: every point is listed with its reason.
 */
public final class RiskSection implements HyconProfileSection {

    public record Factor(String what, int points, String detail) {}

    /** A player's score: 0-100, its level, and every reason (most points first). */
    public record Score(int score, String level, List<Factor> factors) {}

    private static volatile RiskSection instance;

    /** The running section (for the profile header), or null before Hycon starts. */
    public static RiskSection get() {
        return instance;
    }

    private final KnownPlayers known;
    private final PlayerRecords records;
    private final Reports reports;
    private final Ties ties;
    private final Moderation moderation;

    public RiskSection(KnownPlayers known, PlayerRecords records, Reports reports, Ties ties, Moderation moderation) {
        this.known = known;
        this.records = records;
        this.reports = reports;
        this.ties = ties;
        this.moderation = moderation;
        instance = this;
    }

    @Override
    public String id() {
        return "risk";
    }

    @Override
    public String title() {
        return "Risk";
    }

    @Override
    public void render(UUID player, String name, PanelData out) {
        Score s = score(player);
        out.stat("Risk", s.level(), s.score() + " / 100");
        out.stat("Reasons", String.valueOf(s.factors().size()), s.factors().isEmpty() ? "nothing stands out" : "listed below");
        PanelData.Table t = out.table("Why", "Reason", "Points", "Detail");
        for (Factor x : s.factors()) {
            t.row(x.what(), (x.points() > 0 ? "+" : "") + x.points(), x.detail());
        }
        out.note("A pointer for staff, not proof: check the reasons before acting.");
    }

    public Score score(UUID player) {
        List<Factor> f = new ArrayList<>();
        HyconRisk.Factors add = (what, points, detail) -> {
            if (points != 0) {
                f.add(new Factor(what, points, detail == null ? "" : detail));
            }
        };

        int warns = records.count(player, PlayerRecords.Type.WARN), kicks = records.count(player, PlayerRecords.Type.KICK),
                bans = records.count(player, PlayerRecords.Type.BAN);
        add.add("Warnings", Math.min(30, warns * 10), warns + " on record");
        add.add("Kicks", Math.min(16, kicks * 8), kicks + " on record");
        add.add("Bans", Math.min(50, bans * 25), bans + " on record");
        if (moderation.ban(player) != null) {
            add.add("Banned now", 30, null);
        }
        int open = reports.openAgainst(player), all = reports.against(player);
        add.add("Open reports", Math.min(24, open * 8), open + " waiting for staff");
        add.add("Reports (all time)", Math.min(15, (all - open) * 3), (all - open) + " closed");

        var alts = ties.sameConnection(player);
        int bannedAlts = 0;
        for (UUID a : alts) {
            if (moderation.ban(a) != null) {
                bannedAlts++;
            }
        }
        add.add("Shares a connection", Math.min(20, alts.size() * 10), alts.size() + " other account" + (alts.size() == 1 ? "" : "s"));
        add.add("Linked account banned", Math.min(40, bannedAlts * 20), bannedAlts + " of them banned");

        KnownPlayers.Info info = known.get(player);
        long play = info == null ? 0 : known.playtimeNow(info);
        if (play < TimeUnit.HOURS.toMillis(2)) {
            add.add("New account", 5, "under 2 hours played");
        }

        for (HyconRisk source : HyconApi.riskSources()) {
            try {
                source.factors(player, add);
            } catch (RuntimeException e) {
                // one plugin's trouble mustn't hide the rest
            }
        }

        int score = Math.max(0, Math.min(100, f.stream().mapToInt(Factor::points).sum()));
        String level = score >= 80 ? "Very high" : score >= 50 ? "High" : score >= 20 ? "Medium" : "Low";
        f.sort((a, b) -> Integer.compare(b.points(), a.points()));
        return new Score(score, level, f);
    }
}
