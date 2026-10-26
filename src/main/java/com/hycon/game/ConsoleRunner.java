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
        // Any whitespace ends the command name, so "stop<tab>" can't slip past BlockedCommands.
        return c.split("\s", 2)[0].toLowerCase(Locale.ROOT);
    }

    public boolean isBlocked(String command) {
        return blocked.contains(root(command));
    }

    /**
     * Completions for the last word of {@code line} (what Tab offers in the panel): Hytale's own
     * suggestions for that command (sub-commands, arguments), plus player names starting with the
     * word: online players first, then anyone who has played. Each is the full replacement word.
     */
    public List<String> complete(String admin, String line, List<String> onlineNames, List<String> knownNames) {
        String c = line.startsWith("/") ? line.substring(1) : line;
        List<String> words = new java.util.ArrayList<>(List.of(c.split(" ", -1)));
        String last = words.get(words.size() - 1);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            out.addAll(CommandManager.get().suggestCompletions(new PanelCommandSender(admin), words, words.size() - 1));
        } catch (RuntimeException ignored) {
            // a command whose completion fails still gets player names
        }
        if (words.size() > 1) { // the first word is the command itself, never a player
            String lower = last.toLowerCase(Locale.ROOT);
            for (List<String> names : List.of(onlineNames, knownNames)) {
                for (String n : names) {
                    if (n.toLowerCase(Locale.ROOT).startsWith(lower)) {
                        out.add(n);
                    }
                }
            }
        }
        out.remove(last); // already typed in full
        return out.stream().limit(30).toList();
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
