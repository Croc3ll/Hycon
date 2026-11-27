package com.hycon.api;

import java.util.Optional;

/**
 * Who called into Hycon. Other plugins share Hycon's JVM, so its own hooks (the account store, the recorder, shutdown)
 * check that Hycon itself is the caller, and account changes are only taken from plugins the owner trusts.
 */
public final class Callers {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    /** Frames above the caller we want: {@link #callerOf()}, the check, then the guarded method. */
    private static final int GUARD_FRAMES = 3;

    private Callers() {
    }

    /** Throws unless the method that called this one was called by Hycon's own code. */
    public static void requireHycon() {
        if (!isHycon(callerOf())) {
            throw new SecurityException("Only Hycon itself can do this");
        }
    }

    /** The plugin ("Group:Name") that called the method that called this one. */
    public static String plugin() {
        return pluginOf(callerOf());
    }

    private static Class<?> callerOf() {
        Optional<StackWalker.StackFrame> frame = WALKER.walk(s -> s.skip(GUARD_FRAMES).findFirst());
        return frame.<Class<?>>map(StackWalker.StackFrame::getDeclaringClass).orElse(null);
    }

    private static boolean isHycon(Class<?> c) {
        return c != null && c.getClassLoader() == Callers.class.getClassLoader();
    }

    /**
     * Hytale names each plugin's class loader "ThirdParty(Group:Name)" from its manifest. The server refuses two
     * plugins with one id, so a plugin can't pass itself off as one that is installed.
     */
    private static String pluginOf(Class<?> c) {
        if (isHycon(c)) {
            return "Hycon:Hycon";
        }
        String name = c == null || c.getClassLoader() == null ? null : c.getClassLoader().getName();
        if (name == null) {
            return "unknown";
        }
        int open = name.indexOf('('), close = name.lastIndexOf(')');
        return open >= 0 && close > open ? name.substring(open + 1, close) : name;
    }
}
