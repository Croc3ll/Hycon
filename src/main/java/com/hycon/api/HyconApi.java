package com.hycon.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Entry point for other plugins. Typical use, from a plugin that lists Hycon as an optional
 * dependency ({@code "Hycon:Hycon": "*"}):
 *
 * <pre>{@code
 * HyconApi.register(new MyPanel());      // in start()
 * HyconApi.unregister("my-panel");       // in shutdown()
 * }</pre>
 *
 * Keep every reference to Hycon classes in one small class of your own that you only touch when
 * Hycon is installed, so your plugin still loads without it.
 */
public final class HyconApi {

    /** Account levels, lowest to highest (see {@link HyconPanel#minLevel()}). */
    public static final int VIEWER = 1, MODERATOR = 2, ADMIN = 3, OWNER = 4;

    /** Version of this API; raised when something is added. */
    public static final int API_VERSION = 2; // 2: area charts, outage bands, bars, columns

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,40}");
    private static final Map<String, HyconPanel> PANELS = new ConcurrentHashMap<>();

    private HyconApi() {
    }

    /** Adds (or replaces, by id) a dashboard tab. */
    public static void register(HyconPanel panel) {
        if (panel == null || panel.id() == null || !ID.matcher(panel.id()).matches()) {
            throw new IllegalArgumentException("Panel id must be 1-40 letters, digits, - _ or .");
        }
        PANELS.put(panel.id(), panel);
    }

    public static void unregister(String id) {
        if (id != null) {
            PANELS.remove(id);
        }
    }

    public static HyconPanel get(String id) {
        return id == null ? null : PANELS.get(id);
    }

    /** All registered tabs, sorted by title. */
    public static List<HyconPanel> panels() {
        Collection<HyconPanel> all = PANELS.values();
        List<HyconPanel> list = new ArrayList<>(all);
        list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.title(), b.title()));
        return list;
    }

    /** Drops every tab; called when Hycon shuts down. */
    public static void clear() {
        PANELS.clear();
    }
}
