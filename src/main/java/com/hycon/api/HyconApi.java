package com.hycon.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public static final int API_VERSION = 17; // 2: area charts, outage bands, bars, columns; 3: profile sections, pies; 4: forms, row buttons, actions; 5: player records; 6: risk sources; 7: replays; 8: replay world maps; 9: replay shots; 10: replay terrain; 11: prompts; 12: replay blocks; 13: linked accounts; 14: block sources; 15: risk cleared time; 16: tab icons; 17: panel accounts (level, lock, changes)

    /** Icons a tab may pick with {@link HyconPanel#icon()}. Since API version 16. */
    public static final java.util.Set<String> ICONS = java.util.Set.of("chart", "swords", "shield", "coins", "key", "pickaxe", "gift",
            "eye", "users", "globe", "star", "crown", "box", "map", "heart", "bolt", "flag");

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,40}");
    private static final Map<String, HyconPanel> PANELS = new ConcurrentHashMap<>();
    private static final Map<String, HyconProfileSection> SECTIONS = new ConcurrentHashMap<>();
    /** The plugin that first registered each tab, section, risk source or block source ("panel/prison"). */
    private static final Map<String, String> OWNERS = new ConcurrentHashMap<>();

    private HyconApi() {
    }

    private static final String HYCON = "Hycon:Hycon";
    /** Where clash warnings go (Hycon's log), and the ones already given, so a plugin retrying doesn't flood it. */
    private static volatile java.util.function.Consumer<String> warnings = message -> { };
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** Set by Hycon itself. */
    public static void setWarnings(java.util.function.Consumer<String> w) {
        Callers.requireHycon();
        warnings = w;
    }

    /**
     * Puts {@code value} under {@code id} for {@code plugin}, unless another plugin registered that id first. A clash
     * is ignored with a warning, never thrown, so it can't stop a plugin (or the server) starting. Hycon's own ids
     * always go to Hycon. A reload keeps its claim: the plugin id stays the same.
     */
    private static <T> void put(Map<String, T> map, String kind, String id, T value, String plugin) {
        String key = kind + "/" + id;
        synchronized (OWNERS) {
            String owner = OWNERS.get(key);
            if (owner != null && !owner.equals(plugin) && !plugin.equals(HYCON)) {
                String message = "Hycon ignored " + plugin + " registering the " + kind + " \"" + id + "\": it belongs to "
                        + owner + ". Pick another id.";
                if (WARNED.add(message)) {
                    warnings.accept(message);
                }
                return;
            }
            OWNERS.put(key, plugin);
            map.put(id, value);
        }
    }

    /** Removes {@code id} when {@code plugin} registered it (or nobody did); another plugin's id stays. */
    private static <T> void remove(Map<String, T> map, String kind, String id, String plugin) {
        String key = kind + "/" + id;
        synchronized (OWNERS) {
            String owner = OWNERS.get(key);
            if (owner != null && !owner.equals(plugin)) {
                return;
            }
            OWNERS.remove(key);
            map.remove(id);
        }
    }

    /** Which plugin owns each tab, section and source id ("tab/prison" to "PrisonCore:PrisonCore"), for /hycon debug. */
    public static Map<String, String> owners() {
        Callers.requireHycon();
        return new java.util.TreeMap<>(OWNERS);
    }

    /**
     * Panel accounts by name, for a plugin that keeps its staff in step with them (PrisonCore: helper, mod and admin).
     * Accounts are names, not players: a plugin links an account to the player of the same name. Set by Hycon itself.
     * Since API version 17.
     */
    public interface Accounts {
        /** The account's level ({@link #VIEWER} to {@link #OWNER}), or 0 when there is no account by that name. */
        int level(String name);

        boolean locked(String name);

        /**
         * Sets a non-owner account's level (viewer to admin) and unlocks it, for {@code plugin} ("Group:Name"). False
         * when it's missing, an owner, or the plugin isn't in AccountPlugins in hycon.json.
         */
        boolean setLevel(String name, int level, String by, String plugin);

        /** Locks or unlocks a non-owner account; a locked one can't log in. False as for {@link #setLevel}. */
        boolean setLocked(String name, boolean locked, String by, String plugin);
    }

    /** Hears an owner changing an account in the panel (level, lock or removal: level 0). Since API version 17. */
    public interface AccountListener {
        void changed(String name, int level, boolean locked, String by);
    }

    private static volatile Accounts accounts;
    private static final List<AccountListener> ACCOUNT_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Set by Hycon itself. */
    public static void setAccounts(Accounts a) {
        Callers.requireHycon();
        accounts = a;
    }

    /** The panel account's level, or 0 when there is none or Hycon isn't running. Since API version 17. */
    public static int accountLevel(String name) {
        Accounts a = accounts;
        return a == null || name == null ? 0 : a.level(name);
    }

    public static boolean accountLocked(String name) {
        Accounts a = accounts;
        return a != null && name != null && a.locked(name);
    }

    /**
     * See {@link Accounts#setLevel}; false when Hycon isn't running. Doesn't call the listeners. Only works for a
     * plugin the owner listed in AccountPlugins in hycon.json.
     */
    public static boolean setAccountLevel(String name, int level, String by) {
        Accounts a = accounts;
        return a != null && name != null && a.setLevel(name, level, by, Callers.plugin());
    }

    /** See {@link Accounts#setLocked}; false when Hycon isn't running. Doesn't call the listeners. */
    public static boolean setAccountLocked(String name, boolean locked, String by) {
        Accounts a = accounts;
        return a != null && name != null && a.setLocked(name, locked, by, Callers.plugin());
    }

    public static void addAccountListener(AccountListener l) {
        ACCOUNT_LISTENERS.add(l);
    }

    public static void removeAccountListener(AccountListener l) {
        ACCOUNT_LISTENERS.remove(l);
    }

    /** Called by Hycon after an owner changed an account in the panel. A listener that fails doesn't stop the rest. */
    public static void accountChanged(String name, int level, boolean locked, String by) {
        Callers.requireHycon();
        for (AccountListener l : ACCOUNT_LISTENERS) {
            try {
                l.changed(name, level, locked, by);
            } catch (RuntimeException e) {
                // a broken plugin listener must not break the panel's own change
            }
        }
    }

    /** Writes an entry to a player's record (their profile's history, beside warnings and bans). */
    public interface Recorder {
        void record(UUID player, String type, String by, String reason, String detail);
    }

    private static volatile Recorder recorder;

    private static final Map<String, HyconRisk> RISK = new ConcurrentHashMap<>();

    /** Adds (or replaces your own, by id) reasons for the profile's risk score. Since API version 6. */
    public static void registerRiskSource(String id, HyconRisk source) {
        if (id != null && source != null) {
            put(RISK, "risk source", id, source, Callers.plugin());
        }
    }

    public static void unregisterRiskSource(String id) {
        if (id != null) {
            remove(RISK, "risk source", id, Callers.plugin());
        }
    }

    public static List<HyconRisk> riskSources() {
        return new ArrayList<>(RISK.values());
    }

    /** Gives the accounts linked to a player. */
    public interface Links {
        Set<UUID> linked(UUID player);
    }

    private static volatile Links links;

    private static final Map<String, HyconBlocks> BLOCKS = new ConcurrentHashMap<>();

    /** Adds (or replaces your own, by id) a source of replay blocks fetched a tile at a time. Since API version 14. */
    public static void registerBlockSource(String id, HyconBlocks source) {
        if (id == null || !ID.matcher(id).matches() || source == null) {
            throw new IllegalArgumentException("Block source id must be 1-40 letters, digits, - _ or .");
        }
        put(BLOCKS, "block source", id, source, Callers.plugin());
    }

    public static void unregisterBlockSource(String id) {
        if (id != null) {
            remove(BLOCKS, "block source", id, Callers.plugin());
        }
    }

    public static HyconBlocks blockSource(String id) {
        return id == null ? null : BLOCKS.get(id);
    }

    /** When staff last cleared a player's risk score, in epoch milliseconds (0 = never). */
    public interface ClearedAt {
        long clearedAt(UUID player);
    }

    private static volatile ClearedAt clearedAt;

    /** Set by Hycon itself. */
    public static void setClearedAt(ClearedAt c) {
        Callers.requireHycon();
        clearedAt = c;
    }

    /**
     * When staff last cleared this player's risk score with "Clear risk" (epoch milliseconds, 0 if never or Hycon
     * isn't running). A risk source should leave out anything from before this moment. Since API version 15.
     */
    public static long riskClearedAt(UUID player) {
        ClearedAt c = clearedAt;
        return c == null || player == null ? 0 : c.clearedAt(player);
    }

    /** Set by Hycon itself. */
    public static void setLinks(Links l) {
        Callers.requireHycon();
        links = l;
    }

    /**
     * Other accounts that have played from one of this player's connections (Hycon's "Shares a connection" on the
     * profile): likely alts. Empty when there are none or Hycon isn't running. Any thread. Since API version 13.
     */
    public static Set<UUID> linkedAccounts(UUID player) {
        Links l = links;
        return l == null || player == null ? Set.of() : l.linked(player);
    }

    /** Set by Hycon itself. */
    public static void setRecorder(Recorder r) {
        Callers.requireHycon();
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

    /** Adds (or replaces your own, by id) a dashboard tab. Another plugin's id is left alone, with a warning in the log. */
    public static void register(HyconPanel panel) {
        if (panel == null || panel.id() == null || !ID.matcher(panel.id()).matches()) {
            throw new IllegalArgumentException("Panel id must be 1-40 letters, digits, - _ or .");
        }
        put(PANELS, "tab", panel.id(), panel, Callers.plugin());
    }

    public static void unregister(String id) {
        if (id != null) {
            remove(PANELS, "tab", id, Callers.plugin());
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

    /** Adds (or replaces your own, by id) a section on every player's profile. Since API version 3. */
    public static void registerProfileSection(HyconProfileSection section) {
        if (section == null || section.id() == null || !ID.matcher(section.id()).matches()) {
            throw new IllegalArgumentException("Section id must be 1-40 letters, digits, - _ or .");
        }
        put(SECTIONS, "profile section", section.id(), section, Callers.plugin());
    }

    public static void unregisterProfileSection(String id) {
        if (id != null) {
            remove(SECTIONS, "profile section", id, Callers.plugin());
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
        Callers.requireHycon();
        OWNERS.clear();
        PANELS.clear();
        SECTIONS.clear();
        recorder = null;
        links = null;
        accounts = null;
        ACCOUNT_LISTENERS.clear();
        clearedAt = null;
        BLOCKS.clear();
        RISK.clear();
    }
}
