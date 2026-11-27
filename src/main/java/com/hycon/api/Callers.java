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

    private static boolean isHycon(Class<?> c) {
        return c != null && c.getClassLoader() == Callers.class.getClassLoader();
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
        String name = loader == null ? null : loader.getName();
        int open = name == null ? -1 : name.indexOf('('), close = name == null ? -1 : name.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return "unknown";
        }
        String id = name.substring(open + 1, close);
        PluginManager manager = PluginManager.get();
        if (manager == null) {
            return "unknown";
        }
        try {
            PluginBase plugin = manager.getPlugin(PluginIdentifier.fromString(id));
            return plugin != null && plugin.getClass().getClassLoader() == loader ? id : "unknown";
        } catch (IllegalArgumentException e) {
            return "unknown"; // not Group:Name
        }
    }
}
