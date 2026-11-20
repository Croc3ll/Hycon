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
        Set<String> trustedProxies,
        long sessionIdleMs,
        long sessionMaxMs,
        int loginAttempts,
        long loginWindowMs,
        Set<String> blockedCommands,
        int logLines,
        int sharePort,
        String shareBindAddress,
        String ampUrl,
        String ampUser,
        String ampPassword,
        boolean geoIp,
        String serverLocation,
        boolean profiler,
        int lagSpikeMs,
        int lagAlertPercent,
        String lagWebhook,
        int alertSpikeMs,
        int alertSpikesPerMinute,
        int alertMemoryPercent,
        int alertHealthBelow,
        boolean weeklyReport) {

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
                lowerSet(m, "TrustedProxies"),
                (long) (Math.max(1, num(m, "SessionIdleMinutes", 30)) * 60_000),
                (long) (Math.max(1, num(m, "SessionMaxHours", 12)) * 3_600_000),
                (int) Math.max(1, num(m, "LoginAttempts", 5)),
                (long) (Math.max(1, num(m, "LoginWindowMinutes", 10)) * 60_000),
                lowerSet(m, "BlockedCommands"),
                (int) Math.max(100, Math.min(50_000, num(m, "LogLines", 2000))),
                (int) num(m, "SharePort", 8097),
                str(m, "ShareBindAddress", "0.0.0.0"),
                str(m, "AmpUrl", ""),
                str(m, "AmpUser", ""),
                str(m, "AmpPassword", ""),
                bool(m, "GeoIp", true),
                str(m, "ServerLocation", ""),
                bool(m, "Profiler", true),
                (int) Math.max(50, Math.min(5000, num(m, "LagSpikeMs", 100))),
                (int) Math.max(0, Math.min(100, num(m, "LagAlertPercent", 30))),
                str(m, "LagWebhook", ""),
                (int) Math.max(0, Math.min(60_000, num(m, "AlertSpikeMs", 1000))),
                (int) Math.max(0, Math.min(10_000, num(m, "AlertSpikesPerMinute", 10))),
                (int) Math.max(0, Math.min(100, num(m, "AlertMemoryPercent", 90))),
                (int) Math.max(0, Math.min(100, num(m, "AlertHealthBelow", 60))),
                bool(m, "WeeklyReport", true));
    }

    /** True when {@code next} changes an option that is only read when the panel starts. */
    public boolean needsRestart(HyconConfig next) {
        return enabled != next.enabled || !bindAddress.equals(next.bindAddress) || port != next.port
                || sessionIdleMs != next.sessionIdleMs || sessionMaxMs != next.sessionMaxMs
                || loginAttempts != next.loginAttempts || loginWindowMs != next.loginWindowMs || logLines != next.logLines
                || sharePort != next.sharePort || !shareBindAddress.equals(next.shareBindAddress) || geoIp != next.geoIp
                || profiler != next.profiler;
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
