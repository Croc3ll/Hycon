package com.hycon.analytics;

import com.hycon.api.HyconProfileSection;
import com.hycon.api.PanelData;
import com.hycon.game.KillLog;
import com.hycon.game.KnownPlayers;
import com.hycon.game.PingLog;
import com.hycon.game.PlayerRecords;
import com.hycon.game.Reports;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Hycon's own section on every profile: how much and when the player plays, their PvP and their
 * moderation record. "Minutes online" comes from the ping log, which has one sample for every
 * minute a player is online, so it needs no extra tracking.
 */
public final class ActivitySection implements HyconProfileSection {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d");

    private final KnownPlayers known;
    private final PingLog pings;
    private final KillLog kills;
    private final PlayerRecords records;
    private final Reports reports;

    public ActivitySection(KnownPlayers known, PingLog pings, KillLog kills, PlayerRecords records, Reports reports) {
        this.known = known;
        this.pings = pings;
        this.kills = kills;
        this.records = records;
        this.reports = reports;
    }

    @Override
    public String id() {
        return "activity";
    }

    @Override
    public String title() {
        return "Activity";
    }

    @Override
    public void render(UUID player, String name, PanelData out) {
        KnownPlayers.Info info = known.get(player);
        long play = info == null ? 0 : known.playtimeNow(info);
        int sessions = info == null ? 0 : info.sessions();
        long today = System.currentTimeMillis() / TimeUnit.DAYS.toMillis(1);
        int days30 = info == null ? 0 : info.daysSince(today - 29);

        long since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(PingLog.KEEP_DAYS);
        int k = 0, d = 0;
        for (KillLog.Kill x : kills.involving(player, since)) {
            if (x.killer().equals(player)) {
                k++;
            } else {
                d++;
            }
        }
        out.stat("Playtime", AnalyticsPanel.duration(play), "since Hycon was installed")
                .stat("Sessions", String.valueOf(sessions), sessions == 0 ? null : "avg " + AnalyticsPanel.duration(play / sessions))
                .stat("Days played", days30 + " of 30", "last 30 days")
                .stat("PvP (14 days)", k + " / " + d, d == 0 ? (k == 0 ? "no fights" : "no deaths") : "K/D " + String.format("%.2f", k / (double) d))
                .stat("Warnings", String.valueOf(records.count(player, PlayerRecords.Type.WARN)))
                .stat("Reported", reports.against(player) + "×");

        // Minutes online per day and per hour of day, from the per-minute ping samples.
        List<PingLog.Sample> mins;
        try {
            mins = pings.read(player, since);
        } catch (IOException e) {
            mins = List.of();
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate first = LocalDate.now(zone).minusDays(PingLog.KEEP_DAYS - 1);
        int[] perDay = new int[PingLog.KEEP_DAYS];
        int[] perHour = new int[24];
        for (PingLog.Sample s : mins) {
            var t = Instant.ofEpochMilli(s.time()).atZone(zone);
            int idx = (int) (t.toLocalDate().toEpochDay() - first.toEpochDay());
            if (idx >= 0 && idx < perDay.length) {
                perDay[idx]++;
            }
            perHour[t.getHour()]++;
        }
        PanelData.Columns days = out.columns("Minutes online per day (last " + PingLog.KEEP_DAYS + " days)");
        for (int i = 0; i < perDay.length; i++) {
            days.column(first.plusDays(i).format(DAY), perDay[i]);
        }
        PanelData.Columns hours = out.columns("When they play (minutes by hour, " + zone.getId() + ")");
        for (int h = 0; h < 24; h++) {
            hours.column(String.format("%02d", h), perHour[h]);
        }

        PanelData.Pie record = out.pie("Moderation record").unit("times");
        for (PlayerRecords.Type t : PlayerRecords.Type.values()) {
            record.slice(switch (t) {
                case WARN -> "Warnings";
                case KICK -> "Kicks";
                case BAN -> "Bans";
                case UNBAN -> "Unbans";
                case NOTE -> "Staff notes";
            }, records.count(player, t));
        }
        out.pie("PvP (last 14 days)").unit("").slice("Kills", k).slice("Deaths", d);
    }
}
