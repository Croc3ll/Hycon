package com.hycon.perf;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hycon.perf.PerfData.Spike;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

/**
 * {@code /lag [minutes]}: what has been using the server over the last few minutes (default 5),
 * from data the profiler already collected, so it answers at once. {@code /lag spikes} lists the
 * latest lag spikes. Needs {@value #PERMISSION}.
 */
public final class LagCommand extends CommandBase {

    public static final String PERMISSION = "hycon.lag";
    private static final int DEFAULT_MINUTES = 5, MAX_MINUTES = 24 * 60, SHOWN = 6, BAR_WIDTH = 20;

    private final Perf perf;
    private final OptionalArg<String> arg = withOptionalArg("minutes", "Minutes to look back (default 5), or 'spikes'", ArgTypes.STRING);

    public LagCommand(Perf perf) {
        super("lag", "Which plugins use the most server time");
        this.perf = perf;
        requirePermission(PERMISSION);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String raw = arg.get(ctx);
        if (raw != null && raw.trim().equalsIgnoreCase("spikes")) {
            spikes(ctx);
            return;
        }
        int minutes = DEFAULT_MINUTES;
        if (raw != null) {
            try {
                minutes = Math.max(1, Math.min(MAX_MINUTES, Integer.parseInt(raw.trim())));
            } catch (NumberFormatException e) {
                ctx.sendMessage(Message.raw("Use /lag, /lag <minutes> or /lag spikes.").color("#FF5555"));
                return;
            }
        }
        Report r = perf.recent(minutes);
        ctx.sendMessage(Message.raw("Server load, last " + minutes + " min").color("#FFAA00"));
        ctx.sendMessage(Message.raw(r.verdict()).color(colour(r.tickAvgMs / Report.TICK_BUDGET_MS)));
        if (r.minutes == 0) {
            return;
        }
        ctx.sendMessage(Message.raw(String.format("World threads busy %.0f%%  |  worst tick %.0f ms  |  scheduler late %.0f ms  |  GC %.0f ms/min",
                r.busy * 100, r.tickMaxMs, r.schedLateMaxMs, r.gcMsPerMin)).color("#AAAAAA"));
        int shown = 0;
        for (Map.Entry<String, Double> e : r.worldShare.entrySet()) {
            if (shown++ == SHOWN) {
                break;
            }
            double share = e.getValue();
            ctx.sendMessage(Message.raw(String.format("%s %5.1f%%  %s", bar(share / Math.max(0.01, r.busy)), share * 100, e.getKey()))
                    .color(colour(share * 3)));
        }
        r.ioMsPerMin.forEach((owner, ms) -> ctx.sendMessage(
                Message.raw(String.format("! %s waits on files or a database on a game thread: %.0f ms/min", owner, ms)).color("#FF5555")));
        ctx.sendMessage(Message.raw("More in the Hycon panel's Performance tab. /lag spikes for spikes.").color("#777777"));
    }

    private void spikes(CommandContext ctx) {
        List<Spike> list = perf.profiler().spikes();
        if (list.isEmpty()) {
            ctx.sendMessage(Message.raw("No lag spikes since the server started.").color("#55FF55"));
            return;
        }
        ctx.sendMessage(Message.raw("Latest lag spikes").color("#FFAA00"));
        for (Spike s : list.subList(0, Math.min(8, list.size()))) {
            long agoMin = (System.currentTimeMillis() - s.time()) / 60_000;
            String where = s.where().isEmpty() ? "" : " in " + s.where().keySet().iterator().next().replace('\t', ' ');
            ctx.sendMessage(Message.raw(String.format("%d ms  %s  %s%s  (%d min ago)", s.ms(), s.lane(), s.cause(), where, agoMin))
                    .color(colour(s.ms() / 250.0)));
        }
    }

    /** A text bar like {@code ||||||....}, full when {@code fill} is 1. */
    private static String bar(double fill) {
        int n = (int) Math.round(Math.max(0, Math.min(1, fill)) * BAR_WIDTH);
        return "|".repeat(n) + ".".repeat(BAR_WIDTH - n);
    }

    /** Green under 60% of a limit, yellow up to it, red over. */
    private static String colour(double ofLimit) {
        return ofLimit < 0.6 ? "#55FF55" : ofLimit <= 1 ? "#FFFF55" : "#FF5555";
    }
}
