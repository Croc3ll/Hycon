package com.hycon.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    public static final int API_VERSION = 5; // 2: area charts, outage bands, bars, columns; 3: profile sections, pies; 4: forms, row buttons, actions; 5: player records

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,40}");
    private static final Map<String, HyconPanel> PANELS = new ConcurrentHashMap<>();
    private static final Map<String, HyconProfileSection> SECTIONS = new ConcurrentHashMap<>();

    private HyconApi() {
    }

    /** Writes an entry to a player's record (their profile's history, beside warnings and bans). */
    public interface Recorder {
        void record(UUID player, String type, String by, String reason, String detail);
    }

    private static volatile Recorder recorder;

    /** Set by Hycon itself. */
    public static void setRecorder(Recorder r) {
        recorder = r;
    }

    /**
     * Adds an entry to a player's record, shown on their profile with Hycon's own warnings and bans.
     * {@code type} is WARN or NOTE. Since API version 5.
     */
    public static void record(UUID player, String type, String by, String reason, String detail) {
        Recorder r = recorder;
        if (r != null && player != null) {
            r.record(player, type, by, reason, detail);
        }
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

    /** Adds (or replaces, by id) a section on every player's profile. Since API version 3. */
    public static void registerProfileSection(HyconProfileSection section) {
        if (section == null || section.id() == null || !ID.matcher(section.id()).matches()) {
            throw new IllegalArgumentException("Section id must be 1-40 letters, digits, - _ or .");
        }
        SECTIONS.put(section.id(), section);
    }

    public static void unregisterProfileSection(String id) {
        if (id != null) {
            SECTIONS.remove(id);
        }
    }

    /** All profile sections, sorted by title. */
    public static List<HyconProfileSection> profileSections() {
        List<HyconProfileSection> list = new ArrayList<>(SECTIONS.values());
        list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.title(), b.title()));
        return list;
    }

    /** Drops every tab and section; called when Hycon shuts down. */
    public static void clear() {
        PANELS.clear();
        SECTIONS.clear();
        recorder = null;
    }
}
