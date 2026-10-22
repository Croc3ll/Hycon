package com.hycon.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hycon.game.KnownPlayers;
import com.hycon.game.Reports;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.UUID;

/**
 * {@code /report <player> <reason>} - any player can report another (online or one who has been on
 * before). Reports land in the panel's Reports tab, and online staff with
 * {@value #NOTIFY_PERMISSION} get a chat line. Limits live in {@link Reports}.
 */
public final class ReportCommand extends CommandBase {

    public static final String NOTIFY_PERMISSION = "hycon.reports.notify";
    private static final int MAX_REASON = 200;

    private final Reports reports;
    private final KnownPlayers known;

    public ReportCommand(Reports reports, KnownPlayers known) {
        super("report", "Report a player to the staff: /report <player> <reason>");
        this.reports = reports;
        this.known = known;
        requireNoPermission();
        // The name is a real argument so Tab suggests players (see PlayerNameArg); the reason is free
        // text after it, read from the raw input like Hytale's own /ban does.
        withRequiredArg("player", "Who to report (Tab suggests names)", new PlayerNameArg(known));
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        if (!(ctx.sender() instanceof PlayerRef reporter)) {
            ctx.sendMessage(Message.raw("Only players can report."));
            return;
        }
        String raw = CommandUtil.stripCommandName(ctx.getInputString()).strip();
        int sp = raw.indexOf(' ');
        String name = sp < 0 ? raw : raw.substring(0, sp);
        String reason = sp < 0 ? "" : raw.substring(sp + 1).strip().replaceAll("[\\r\\n\\t]+", " ");
        if (name.isEmpty() || reason.isEmpty()) {
            ctx.sendMessage(Message.raw("Usage: /report <player> <reason>"));
            return;
        }
        if (reason.length() > MAX_REASON) {
            reason = reason.substring(0, MAX_REASON);
        }
        UUID target = null;
        String targetName = name;
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (p.getUsername().equalsIgnoreCase(name)) {
                target = p.getUuid();
                targetName = p.getUsername();
            }
        }
        if (target == null) {
            target = known.uuid(name);
            if (target != null && known.name(target) != null) {
                targetName = known.name(target);
            }
        }
        if (target == null) {
            ctx.sendMessage(Message.raw("No player called " + name + " has been on this server."));
            return;
        }
        try {
            String refused = reports.file(reporter.getUuid(), reporter.getUsername(), target, targetName, reason);
            if (refused != null) {
                ctx.sendMessage(Message.raw(refused));
                return;
            }
        } catch (IOException e) {
            ctx.sendMessage(Message.raw("Your report could not be saved. Please tell a staff member."));
            return;
        }
        ctx.sendMessage(Message.raw("Thanks, your report about " + targetName + " was sent to the staff.").color("#55FF55"));
        Message note = Message.raw("[Report] " + reporter.getUsername() + " reported " + targetName + ": " + reason).color("#FFAA00");
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (p.hasPermission(NOTIFY_PERMISSION)) {
                p.sendMessage(note);
            }
        }
    }
}
