package com.hycon.game;

import com.hypixel.hytale.server.core.command.system.CommandManager;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs console commands for the panel through Hytale's normal command system. */
public final class ConsoleRunner {

    public record Result(boolean finished, List<String> output, String error) {
    }

    private final Set<String> blocked;

    public ConsoleRunner(Set<String> blocked) {
        this.blocked = blocked;
    }

    /** First word without a leading "/", lower case; used for the block list and the audit log. */
    public static String root(String command) {
        String c = command.strip();
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        int sp = c.indexOf(' ');
        return (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
    }

    public boolean isBlocked(String command) {
        return blocked.contains(root(command));
    }

    /**
     * Runs the command and waits up to 5 seconds for it to finish, so its replies can be shown.
     * Slower commands keep running; their later replies just aren't returned.
     */
    public Result run(String admin, String command) {
        String c = command.strip();
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        PanelCommandSender sender = new PanelCommandSender(admin);
        try {
            CommandManager.get().handleCommand(sender, c).get(5, TimeUnit.SECONDS);
            return new Result(true, sender.output(), null);
        } catch (TimeoutException e) {
            return new Result(false, sender.output(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, sender.output(), "Interrupted");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new Result(true, sender.output(), cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }
}
