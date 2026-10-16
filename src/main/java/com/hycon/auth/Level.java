package com.hycon.auth;

import java.util.Locale;

/**
 * Permission level of a panel account. Each level includes everything below it.
 * The numbers match {@link com.hycon.api.HyconApi#VIEWER} etc. so other plugins can use them
 * without depending on this enum.
 */
public enum Level {
    /** Sees players, bans and plugin dashboards. */
    VIEWER(1),
    /** Also kicks, bans and unbans, and watches the live log. */
    MODERATOR(2),
    /** Also runs console commands (with full console rights) and reads the audit log. */
    ADMIN(3),
    /** Also creates, changes and removes panel accounts. */
    OWNER(4);

    public final int rank;

    Level(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(Level other) {
        return rank >= other.rank;
    }

    public static Level parse(String s) {
        try {
            return s == null ? null : valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
