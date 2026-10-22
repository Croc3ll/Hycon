package com.hycon.commands;

import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hycon.game.KnownPlayers;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A player name argument whose Tab suggestions include players who are offline: online players
 * first, then anyone who has been on the server. Hytale's own player argument only knows who is
 * online, but a report is often about someone who already left.
 */
final class PlayerNameArg extends SingleArgumentType<String> {

    /** More than this is noise in the game's suggestion list. */
    private static final int MAX_SUGGESTIONS = 20;

    private final KnownPlayers known;

    PlayerNameArg(KnownPlayers known) {
        super("player", "A player's name (online or offline)", "Crocell");
        this.known = known;
    }

    @Override
    public String parse(String input, ParseResult result) {
        return input; // looked up by the command itself, so it can say "nobody by that name"
    }

    @Override
    public void suggest(@Nonnull CommandSender sender, @Nonnull String typed, int numParametersTyped, @Nonnull SuggestionResult result) {
        String lower = typed.toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        for (PlayerRef p : Universe.get().getPlayers()) {
            String n = p.getUsername();
            if (n.toLowerCase(Locale.ROOT).startsWith(lower) && seen.add(n.toLowerCase(Locale.ROOT)) && seen.size() <= MAX_SUGGESTIONS) {
                result.suggest(n);
            }
        }
        for (KnownPlayers.Info i : known.search(typed, u -> false, 200)) {
            String n = i.name();
            if (seen.size() >= MAX_SUGGESTIONS) {
                break;
            }
            if (n.toLowerCase(Locale.ROOT).startsWith(lower) && !n.equals(i.uuid().toString()) && seen.add(n.toLowerCase(Locale.ROOT))) {
                result.suggest(n);
            }
        }
    }
}
