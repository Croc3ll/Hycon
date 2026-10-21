package com.hycon.game;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ties between players for the connection map, recorded as they join:
 * <ul>
 *   <li><b>Same connection</b>: joined from the same IP address, which usually means alt accounts
 *       or one household. The address itself is never stored: only an HMAC of it with a random
 *       secret made per server, so {@code ties.json} can't be turned back into addresses (not even
 *       by trying every IP, without the secret).</li>
 *   <li><b>Co-joins</b>: joined within {@link #CO_JOIN_WINDOW_MS} of each other. Strangers do this by
 *       chance now and then; a tie needs {@link #MIN_CO_JOINS} times, which means they play together.</li>
 * </ul>
 */
public final class Ties {

    public static final long CO_JOIN_WINDOW_MS = 120_000;
    public static final int MIN_CO_JOINS = 3;
    /** Addresses remembered per player; enough for home, phone hotspot, a friend's place. */
    private static final int MAX_ADDRESSES = 10;

    private record Join(UUID player, long time) {
    }

    private final Path file;
    private byte[] secret;
    private final Map<String, Set<UUID>> byAddress = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> addresses = new ConcurrentHashMap<>();
    /** "uuidA|uuidB" (sorted) to how many times they joined together. */
    private final Map<String, Integer> coJoins = new ConcurrentHashMap<>();
    private final Deque<Join> recent = new ArrayDeque<>();
    private volatile boolean dirty;

    public Ties(Path dataDir) {
        this.file = dataDir.resolve("ties.json");
    }

    public synchronized void load() throws IOException {
        if (Files.exists(file)) {
            Map<String, Object> m = JsonReader.object(Files.readString(file, StandardCharsets.UTF_8));
            if (m.get("secret") instanceof String s) {
                secret = HexFormat.of().parseHex(s);
            }
            if (m.get("addresses") instanceof Map<?, ?> a) {
                a.forEach((k, v) -> {
                    if (k instanceof String hash && v instanceof List<?> l) {
                        for (Object o : l) {
                            if (o instanceof String u) {
                                link(hash, UUID.fromString(u));
                            }
                        }
                    }
                });
            }
            if (m.get("coJoins") instanceof Map<?, ?> c) {
                c.forEach((k, v) -> {
                    if (k instanceof String pair && v instanceof Double n) {
                        coJoins.put(pair, n.intValue());
                    }
                });
            }
        }
        if (secret == null) {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            dirty = true;
            saveIfChanged();
        }
    }

    /** A player joined (from {@code address}, null if unknown or a local connection). */
    public synchronized void joined(UUID player, InetAddress address) {
        long now = System.currentTimeMillis();
        if (address != null && !address.isLoopbackAddress()) {
            link(hash(address), player);
            dirty = true;
        }
        while (!recent.isEmpty() && now - recent.peekFirst().time() > CO_JOIN_WINDOW_MS) {
            recent.pollFirst();
        }
        Set<UUID> counted = new HashSet<>();
        for (Join j : recent) {
            if (!j.player().equals(player) && counted.add(j.player())) {
                coJoins.merge(pair(player, j.player()), 1, Integer::sum);
                dirty = true;
            }
        }
        recent.addLast(new Join(player, now));
    }

    /** Other accounts seen on one of this player's addresses. */
    public Set<UUID> sameConnection(UUID player) {
        Set<UUID> out = new HashSet<>();
        for (String h : addresses.getOrDefault(player, Set.of())) {
            out.addAll(byAddress.getOrDefault(h, Set.of()));
        }
        out.remove(player);
        return out;
    }

    /** Players this one joined together with at least {@link #MIN_CO_JOINS} times, with the count. */
    public Map<UUID, Integer> coJoins(UUID player) {
        Map<UUID, Integer> out = new HashMap<>();
        String me = player.toString();
        coJoins.forEach((k, n) -> {
            if (n >= MIN_CO_JOINS) {
                int bar = k.indexOf('|');
                String a = k.substring(0, bar), b = k.substring(bar + 1);
                if (a.equals(me)) {
                    out.put(UUID.fromString(b), n);
                } else if (b.equals(me)) {
                    out.put(UUID.fromString(a), n);
                }
            }
        });
        return out;
    }

    public synchronized void saveIfChanged() {
        if (!dirty) {
            return;
        }
        dirty = false;
        Json j = new Json().beginObject().field("secret", HexFormat.of().formatHex(secret)).name("addresses").beginObject();
        byAddress.forEach((h, set) -> {
            j.name(h).beginArray();
            for (UUID u : set) {
                j.value(u.toString());
            }
            j.endArray();
        });
        j.endObject().name("coJoins").beginObject();
        coJoins.forEach(j::field);
        j.endObject().endObject();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("ties.json.tmp");
            Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            dirty = true; // try again next minute
        }
    }

    private void link(String hash, UUID player) {
        Set<String> mine = addresses.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet());
        if (!mine.contains(hash) && mine.size() >= MAX_ADDRESSES) {
            return;
        }
        mine.add(hash);
        byAddress.computeIfAbsent(hash, k -> ConcurrentHashMap.newKeySet()).add(player);
    }

    private String hash(InetAddress address) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            // 16 bytes is plenty to tell addresses apart and keeps the file small.
            return HexFormat.of().formatHex(mac.doFinal(address.getAddress()), 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pair(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }
}
