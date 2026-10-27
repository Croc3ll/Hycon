package com.hycon.api;

import java.util.UUID;

/**
 * A section another plugin adds to every player's profile page in Hycon, e.g. "Economy": what this
 * player mines, earns and spends. Register it with {@link HyconApi#registerProfileSection}. Like a
 * {@link HyconPanel}, it only fills in {@link PanelData}; Hycon draws it (stat tiles, charts, pie
 * charts, bars, tables), under the player's own heading.
 *
 * <p>{@link #render} runs on a Hycon web thread whenever someone opens or refreshes the profile, for
 * online and offline players alike. Read thread-safe data only; throw nothing you can avoid (an
 * exception is shown as an error in the section).
 */
public interface HyconProfileSection {

    /** Unique id, e.g. {@code "myplugin"}. Letters, digits, {@code - _ .} only. */
    String id();

    /** Section heading on the profile. */
    String title();

    /** Lowest account level that may see it; one of {@link HyconApi#VIEWER} etc. */
    default int minLevel() {
        return HyconApi.VIEWER;
    }

    /** Fills in this player's section. {@code out.rangeMillis()} is the range the viewer picked. */
    void render(UUID player, String name, PanelData out);
}
