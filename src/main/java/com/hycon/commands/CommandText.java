package com.hycon.commands;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;

import java.util.Locale;

/** Reads the free text typed after a command's name. */
final class CommandText {

    private CommandText() {
    }

    /**
     * The text after the command name, stripped. Hytale's {@code CommandUtil.stripCommandName} returns the whole
     * input when there is no space, so a bare {@code /bug} would come back as "bug"; this returns "" instead.
     */
    static String argsAfterName(String input, AbstractCommand cmd) {
        String s = input == null ? "" : input.strip();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        int sp = s.indexOf(' ');
        if (sp < 0) {
            return isName(s, cmd) ? "" : s;
        }
        String first = s.substring(0, sp);
        String rest = s.substring(sp + 1).strip();
        return isName(first, cmd) ? rest : s;
    }

    private static boolean isName(String word, AbstractCommand cmd) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.equals(cmd.getName().toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String a : cmd.getAliases()) {
            if (w.equals(a.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
