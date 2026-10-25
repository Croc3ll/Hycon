package com.hycon.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hycon.game.BugReports;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * {@code /bug <what happened>} - any player can report a bug. It lands in the panel's Bugs tab with
 * where they stood and the server's recent warnings and errors; online staff with
 * {@value ReportCommand#NOTIFY_PERMISSION} get a chat line.
 */
public final class BugCommand extends CommandBase {

    private static final int MAX_TEXT = 500;

    private final BugReports bugs;

    public BugCommand(BugReports bugs) {
        super("bug", "Report a bug to the staff: /bug <what happened>");
        this.bugs = bugs;
        requireNoPermission();
        setAllowsExtraArguments(true);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        if (!(ctx.sender() instanceof PlayerRef player)) {
            ctx.sendMessage(Message.raw("Only players can report bugs."));
            return;
        }
        String text = CommandUtil.stripCommandName(ctx.getInputString()).strip().replaceAll("[\\r\\n\\t]+", " ");
        if (text.isEmpty()) {
            ctx.sendMessage(Message.raw("Usage: /bug <what happened>"));
            return;
        }
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }
        String where = "";
        try {
            World w = player.getWorldUuid() == null ? null : Universe.get().getWorld(player.getWorldUuid());
            var pos = player.getTransform().getPosition();
            where = (w == null ? "?" : w.getName()) + String.format(" at %.0f, %.0f, %.0f", pos.x, pos.y, pos.z);
        } catch (RuntimeException ignored) {
            // position unknown
        }
        try {
            String refused = bugs.file(player.getUuid(), player.getUsername(), text, where);
            if (refused != null) {
                ctx.sendMessage(Message.raw(refused));
                return;
            }
        } catch (IOException e) {
            ctx.sendMessage(Message.raw("Your bug report could not be saved. Please tell a staff member."));
            return;
        }
        ctx.sendMessage(Message.raw("Thanks, your bug report was sent to the staff.").color("#55FF55"));
        Message note = Message.raw("[Bug] " + player.getUsername() + ": " + text).color("#FFAA00");
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (p.hasPermission(ReportCommand.NOTIFY_PERMISSION)) {
                p.sendMessage(note);
            }
        }
    }
}
