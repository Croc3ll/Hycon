package com.hycon.config;

import com.hycon.web.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings from {@code hycon.json}. The file is created from the commented template in the jar
 * and never rewritten, so server owners' edits and comments stay. Missing options keep defaults.
 */
public record HyconConfig(
        boolean enabled,
        String bindAddress,
        int port,
        Set<String> allowedHosts,
        boolean secureCookie,
        boolean trustProxy,
        long sessionIdleMs,
        long sessionMaxMs,
        int loginAttempts,
        long loginWindowMs,
        Set<String> blockedCommands,
        int logLines,
        int sharePort,
        String shareBindAddress) {

    public static HyconConfig load(Path dataDir) throws IOException {
        Path file = dataDir.resolve("hycon.json");
        if (!Files.exists(file)) {
            Files.createDirectories(dataDir);
            try (InputStream in = HyconConfig.class.getClassLoader().getResourceAsStream("defaults/hycon.json")) {
                if (in == null) {
                    throw new IOException("Missing defaults/hycon.json inside the Hycon jar");
                }
                Files.copy(in, file);
            }
        }
        Map<String, Object> m;
        try {
            m = JsonReader.object(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException("hycon.json: " + e.getMessage(), e);
        }
        return new HyconConfig(
                bool(m, "Enabled", true),
                str(m, "BindAddress", "127.0.0.1"),
                (int) num(m, "Port", 8096),
                lowerSet(m, "AllowedHosts"),
                bool(m, "SecureCookie", false),
                bool(m, "TrustProxy", false),
                (long) (Math.max(1, num(m, "SessionIdleMinutes", 30)) * 60_000),
                (long) (Math.max(1, num(m, "SessionMaxHours", 12)) * 3_600_000),
                (int) Math.max(1, num(m, "LoginAttempts", 5)),
                (long) (Math.max(1, num(m, "LoginWindowMinutes", 10)) * 60_000),
                lowerSet(m, "BlockedCommands"),
                (int) Math.max(100, Math.min(50_000, num(m, "LogLines", 2000))),
                (int) num(m, "SharePort", 8097),
                str(m, "ShareBindAddress", "0.0.0.0"));
    }

    /** True when only this machine can reach the panel. */
    public boolean loopbackOnly() {
        return bindAddress.equals("127.0.0.1") || bindAddress.equals("::1") || bindAddress.equalsIgnoreCase("localhost");
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        return m.get(k) instanceof Boolean b ? b : def;
    }

    private static double num(Map<String, Object> m, String k, double def) {
        return m.get(k) instanceof Double d ? d : def;
    }

    private static String str(Map<String, Object> m, String k, String def) {
        return m.get(k) instanceof String s && !s.isBlank() ? s.trim() : def;
    }

    private static Set<String> lowerSet(Map<String, Object> m, String k) {
        if (!(m.get(k) instanceof List<?> list)) {
            return Set.of();
        }
        return list.stream().filter(String.class::isInstance).map(o -> ((String) o).trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
}
