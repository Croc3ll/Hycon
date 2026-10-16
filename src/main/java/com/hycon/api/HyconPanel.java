package com.hycon.api;

/**
 * A dashboard tab that another plugin adds to the Hycon panel. Register it with
 * {@link HyconApi#register}. Hycon draws the page; the plugin only fills in numbers, charts and
 * tables through {@link PanelData}, so it needs no HTML and can't run script in the panel.
 *
 * <p>{@link #render} is called on one of Hycon's web threads (never a game thread) each time a
 * browser loads the tab, roughly every few seconds while it's open. Keep it quick and read only
 * thread-safe data; anything it throws is shown as an error on the tab.
 */
public interface HyconPanel {

    /** Unique id, e.g. {@code "prisoncore"}. Letters, digits, {@code - _ .} only. */
    String id();

    /** Tab title shown in the panel. */
    String title();

    /** Lowest account level that may see this tab; one of {@link HyconApi#VIEWER} etc. */
    default int minLevel() {
        return HyconApi.VIEWER;
    }

    /** Fills in the tab's content. */
    void render(PanelData out);
}
