package com.hycon.api;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;

/**
 * Who called into Hycon. Other plugins share Hycon's JVM, so its own hooks (the account store, the recorder, shutdown)
 * check that Hycon itself is the caller, and account changes are only taken from plugins the owner trusts.
 * This is not a sandbox: reflection, {@code MethodHandles.privateLookupIn} on another plugin's class (plugins are
 * unnamed modules, open to everyone), or a plugin running console commands, gets around it.
 *
 * <p>Call {@link #requireHycon()} and {@link #plugin()} straight from the public method they guard, never from a
 * helper: they look a fixed number of frames up the stack.
 */
public final class Callers {

    /** Hidden frames too, so a method reference a plugin hands Hycon shows up as the plugin's own frame. */
    private static final StackWalker WALKER = StackWalker.getInstance(
            java.util.Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_HIDDEN_FRAMES));
    /** Frames above the caller we want: {@link #callerOf()}, the check, then the guarded method. */
    private static final int GUARD_FRAMES = 3;
    private static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();
    private static final PluginIdentifier HYCON = PluginIdentifier.fromString("Hycon:Hycon");

    private Callers() {
    }

    /** Throws unless the method that called this one was called by Hycon's own code. */
    public static void requireHycon() {
        if (!isHycon(callerOf())) {
            throw new SecurityException("Only Hycon itself can do this");
        }
    }

    /** The plugin ("Group:Name") that called the method that called this one, or "unknown". */
    public static String plugin() {
        return pluginOf(callerOf());
    }

    /** The first caller above the guarded method that isn't the JDK (a forEach or Method.invoke in between). */
    private static Class<?> callerOf() {
        return WALKER.walk(s -> s.skip(GUARD_FRAMES)
                .<Class<?>>map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> c.getClassLoader() != null && c.getClassLoader() != PLATFORM)
                .findFirst().orElse(null));
    }

    /**
     * Hycon's own classes: those loaded by the plugin the server registered as Hycon:Hycon, or before it's registered,
     * those loaded with this one. A server with hycon-api.jar on its class path (a plugin developer's test server, so a
     * plugin run from there can see the API) is why. This class then comes from that class path and Hycon's own from
     * its plugin loader: 0.2.5 refused Hycon itself and the server stopped. Trusting the class path's loader as well
     * (0.2.6) let every plugin run from the class path pass as Hycon.
     */
    private static boolean isHycon(Class<?> c) {
        if (c == null) {
            return false;
        }
        ClassLoader hycon = hyconLoader();
        return c.getClassLoader() == (hycon != null ? hycon : Callers.class.getClassLoader());
    }

    /** The class loader of the plugin the server loaded as Hycon:Hycon, or null before it's loaded. */
    private static ClassLoader hyconLoader() {
        PluginManager manager = PluginManager.get();
        PluginBase hycon = manager == null ? null : manager.getPlugin(HYCON);
        return hycon == null ? null : hycon.getClass().getClassLoader();
    }

    /**
     * Hytale names each plugin's class loader "ThirdParty(Group:Name)". Anyone can give a loader that name, so the id
     * only counts when the server's plugin by that id really was loaded by this loader. Ids are case-sensitive. A
     * plugin that defines a class inside another plugin's loader (privateLookupIn) still passes as that plugin.
     */
    private static String pluginOf(Class<?> c) {
        if (isHycon(c)) {
            return "Hycon:Hycon";
        }
        ClassLoader loader = c == null ? null : c.getClassLoader();
        PluginManager manager = PluginManager.get();
        if (loader == null || manager == null) {
            return "unknown";
        }
        String name = loader.getName();
        int open = name == null ? -1 : name.indexOf('('), close = name == null ? -1 : name.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return classPathPlugin(c, manager);
        }
        String id = name.substring(open + 1, close);
        try {
            PluginBase plugin = manager.getPlugin(PluginIdentifier.fromString(id));
            return plugin != null && plugin.getClass().getClassLoader() == loader ? id : "unknown";
        } catch (IllegalArgumentException e) {
            return "unknown"; // not Group:Name
        }
    }

    /**
     * A plugin run from the server's class path (a developer's test server). Its loader has no plugin name and every
     * class-path plugin shares it, so it's the plugin loaded by that loader whose main class's package holds the
     * caller. Only a plugin the server really loaded counts, as for named loaders. "unknown" when none matches.
     */
    private static String classPathPlugin(Class<?> c, PluginManager manager) {
        for (PluginBase plugin : manager.getPlugins()) {
            Class<?> main = plugin.getClass();
            if (main.getClassLoader() == c.getClassLoader() && c.getName().startsWith(main.getPackageName() + ".")) {
                return plugin.getIdentifier().toString();
            }
        }
        return "unknown";
    }
}
