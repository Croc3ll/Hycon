package com.hycon.perf;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out which plugin a stack or class belongs to. Hytale names each plugin's class loader
 * {@code ThirdParty(Group:Name)} or {@code BuiltinPlugin(Group:Name)}, and every stack frame carries
 * that name, so no class lookups are needed.
 */
final class Owners {

    /** Everything that isn't a plugin: the server itself, the JDK and libraries. */
    static final String HYTALE = "Hytale";
    private static final String THIRD_PARTY = "ThirdParty(";
    private static final String BUILT_IN = "BuiltinPlugin(";

    /** Loader name to label, so each distinct name is parsed once. */
    private static final Map<String, String> LABELS = new ConcurrentHashMap<>();

    private Owners() {
    }

    /**
     * The plugin whose code is running: the frame nearest the top that belongs to a third-party
     * plugin. A plugin that calls into Hytale (setting blocks, sending packets) is blamed for that
     * work too. With no plugin frame, a built-in Hytale module or plain {@link #HYTALE}.
     */
    static String of(StackTraceElement[] stack) {
        String builtIn = null;
        for (StackTraceElement f : stack) {
            String loader = f.getClassLoaderName();
            if (loader == null) {
                continue;
            }
            if (loader.startsWith(THIRD_PARTY)) {
                return label(loader);
            }
            if (builtIn == null && loader.startsWith(BUILT_IN)) {
                builtIn = loader;
            }
        }
        return builtIn == null ? HYTALE : label(builtIn);
    }

    /** The owner of a class, for Hytale's own per-system timings. */
    static String of(Class<?> type) {
        String loader = type.getClassLoader() == null ? null : type.getClassLoader().getName();
        if (loader == null || !(loader.startsWith(THIRD_PARTY) || loader.startsWith(BUILT_IN))) {
            return HYTALE;
        }
        return label(loader);
    }

    /** The owner named by a class loader name from a stack frame. */
    static String ofLoader(String loader) {
        return loader != null && (loader.startsWith(THIRD_PARTY) || loader.startsWith(BUILT_IN)) ? label(loader) : HYTALE;
    }

    /** The top frame inside {@code owner}'s code, as {@code Class.method}: where in the plugin the time goes. */
    static String where(StackTraceElement[] stack, String owner) {
        for (StackTraceElement f : stack) {
            String loader = f.getClassLoaderName();
            boolean mine = owner.equals(HYTALE) ? loader == null || !loader.contains("(") : loader != null && owner.equals(label(loader));
            if (mine) {
                return shortClass(f.getClassName()) + "." + f.getMethodName();
            }
        }
        return stack.length == 0 ? "?" : shortClass(stack[0].getClassName()) + "." + stack[0].getMethodName();
    }

    /** Third-party plugins only: the ones a server owner can actually remove or report. */
    static boolean isPlugin(String owner) {
        return !owner.equals(HYTALE) && !owner.startsWith("Hytale ");
    }

    /** Each loaded plugin's label and version, for comparing one release with the next. */
    static Map<String, String> versions() {
        Map<String, String> out = new HashMap<>();
        for (PluginBase p : PluginManager.get().getPlugins()) {
            out.put(of(p.getClass()), String.valueOf(p.getManifest().getVersion()));
        }
        return out;
    }

    private static String label(String loader) {
        return LABELS.computeIfAbsent(loader, Owners::parse);
    }

    /** {@code ThirdParty(Hycon:Hycon)} to {@code Hycon}; built-in modules read {@code Hytale <name>}. */
    private static String parse(String loader) {
        int open = loader.indexOf('('), close = loader.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return HYTALE;
        }
        String id = loader.substring(open + 1, close);
        String name = id.substring(id.indexOf(':') + 1);
        return loader.startsWith(BUILT_IN) ? "Hytale " + name : name;
    }

    private static String shortClass(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
