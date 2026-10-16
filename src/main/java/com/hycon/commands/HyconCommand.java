package com.hycon.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hycon.HyconPlugin;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code /hycon} - console-only recovery for the panel.
 * <pre>
 * /hycon info                  panel address and status
 * /hycon resetpassword &lt;name&gt;  new password for a panel account, written to FIRST-LOGIN.txt
 *                              (creates it as OWNER if it doesn't exist - the way back in
 *                              if every owner is locked out)
 * </pre>
 * Console only: anyone in game with a permission plugin mistake could otherwise take over the panel.
 */
public final class HyconCommand extends AbstractCommandCollection {

    public HyconCommand() {
        super("hycon", "Hycon web panel (console only)");
        addSubCommand(new Info());
        addSubCommand(new ResetPassword());
    }

    private static boolean console(CommandContext ctx) {
        if (ctx.sender() instanceof ConsoleSender) {
            return true;
        }
        ctx.sendMessage(Message.raw("Hycon commands only work from the server console."));
        return false;
    }

    private static final class Info extends CommandBase {
        Info() {
            super("info", "Panel address and status");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            if (console(ctx)) {
                ctx.sendMessage(Message.raw(HyconPlugin.get().status()));
            }
        }
    }

    private static final class ResetPassword extends CommandBase {
        private final RequiredArg<String> nameArg = withRequiredArg("name", "Panel account name", ArgTypes.STRING);

        ResetPassword() {
            super("resetpassword", "New password for a panel account (written to FIRST-LOGIN.txt)");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            if (!console(ctx)) {
                return;
            }
            String name = nameArg.get(ctx);
            try {
                Path file = HyconPlugin.get().resetPasswordToFile(name);
                // The password itself is never printed: the console log is visible in the panel.
                ctx.sendMessage(Message.raw("New password for '" + name + "' written to " + file.toAbsolutePath()));
            } catch (IOException | IllegalArgumentException e) {
                ctx.sendMessage(Message.raw("Could not reset: " + e.getMessage()));
            }
        }
    }
}
