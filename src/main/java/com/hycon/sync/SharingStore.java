package com.hycon.sync;

import com.hycon.auth.Passwords;
import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Ban sharing settings, kept in {@code sharing.json} (keep it private: it holds the secrets):
 * <ul>
 *   <li><b>keys</b> this server hands out. Each has an id and a secret. Other servers never send
 *       the secret: they sign each request with it (HMAC-SHA256 over id, time and path), so a key
 *       can't be picked up off the network even without HTTPS, and an old request can't be replayed
 *       after {@link #MAX_SKEW_S} seconds.</li>
 *   <li><b>subscriptions</b>: servers this one follows, each with the key that server gave us.</li>
 *   <li><b>exceptions</b>: shared bans a moderator lifted here. They stay lifted until the other
 *       server bans that player again (a ban with a different start time).</li>
 *   <li><b>publicAddress</b>: how other servers reach this one ("play.example.com" or an IP); goes
 *       into the share codes.</li>
 * </ul>
 * A share code bundles address, port, key id and secret into one string, so owners exchange a single
 * code instead of an address plus a key.
 */
public final class SharingStore {

    public record Key(String name, String id, String secret, long created) {
    }

    public record Subscription(String name, String url, String keyId, String secret, boolean enabled) {
        /** "Banned by" id of bans imported from this subscription. */
        public UUID uuid() {
            return syncUuid(name);
        }
    }

    /** What a share code contains. */
    public record Code(String url, String keyId, String secret) {
    }

    public static final String CODE_PREFIX = "hycon1_";
    /** How far a signed request's clock may differ from ours. */
    static final long MAX_SKEW_S = 300;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9 _.-]{2,32}");
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9.:\\[\\]-]{1,253}");

    private final Path file;
    private final List<Key> keys = new CopyOnWriteArrayList<>();
    private final List<Subscription> subs = new CopyOnWriteArrayList<>();
    /** "subscription|uuid" to the start time of the shared ban that was lifted here. */
    private final Map<String, Long> exceptions = new ConcurrentHashMap<>();
    private volatile String publicAddress = "";

    public SharingStore(Path dataDir) {
        this.file = dataDir.resolve("sharing.json");
    }

    public static UUID syncUuid(String subscription) {
        return UUID.nameUUIDFromBytes(("hycon-sync:" + subscription.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void load() throws IOException {
        keys.clear();
        subs.clear();
        exceptions.clear();
        if (!Files.exists(file)) {
            return;
        }
        Map<String, Object> m = JsonReader.object(Files.readString(file, StandardCharsets.UTF_8));
        publicAddress = m.get("publicAddress") instanceof String a ? a : "";
        if (m.get("keys") instanceof List<?> l) {
            for (Object o : l) {
                // Keys from the first sharing version (hash only, no id) can't sign and are dropped.
                if (o instanceof Map<?, ?> k && k.get("name") instanceof String n && k.get("id") instanceof String id
                        && k.get("secret") instanceof String sec) {
                    keys.add(new Key(n, id, sec, k.get("created") instanceof Double d ? d.longValue() : 0));
                }
            }
        }
        if (m.get("subscriptions") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> s && s.get("name") instanceof String n && s.get("url") instanceof String u
                        && s.get("keyId") instanceof String id && s.get("secret") instanceof String sec) {
                    subs.add(new Subscription(n, u, id, sec, !Boolean.FALSE.equals(s.get("enabled"))));
                }
            }
        }
        if (m.get("exceptions") instanceof Map<?, ?> ex) {
            ex.forEach((k, v) -> {
                if (k instanceof String key && v instanceof Double since) {
                    exceptions.put(key, since.longValue());
                }
            });
        }
    }

    public List<Key> keys() {
        return List.copyOf(keys);
    }

    public List<Subscription> subscriptions() {
        return List.copyOf(subs);
    }

    public String publicAddress() {
        return publicAddress;
    }

    public synchronized void setPublicAddress(String address) throws IOException {
        String a = address == null ? "" : address.strip();
        if (!a.isEmpty() && !ADDRESS.matcher(a).matches()) {
            throw new IllegalArgumentException("Enter a host name or IP address only, e.g. play.example.com");
        }
        publicAddress = a;
        save();
    }

    // ---------- Keys this server hands out ----------

    /** New key; returns it so the share code can be shown. */
    public synchronized Key createKey(String name) throws IOException {
        checkName(name);
        if (keys.stream().anyMatch(k -> k.name().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("A key with that name exists");
        }
        Key k = new Key(name, Passwords.token().substring(0, 12), Passwords.token(), System.currentTimeMillis());
        keys.add(k);
        save();
        return k;
    }

    public synchronized void revokeKey(String name) throws IOException {
        if (!keys.removeIf(k -> k.name().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("No such key");
        }
        save();
    }

    public Key key(String name) {
        for (Key k : keys) {
            if (k.name().equalsIgnoreCase(name)) {
                return k;
            }
        }
        throw new IllegalArgumentException("No such key");
    }

    /**
     * Checks an {@code Authorization: Hycon <keyId>:<unixSeconds>:<hexSignature>} header for
     * {@code path}. Returns the key's name, or null if it isn't valid.
     */
    public String verify(String header, String path) {
        if (header == null || !header.startsWith("Hycon ") || header.length() > 300) {
            return null;
        }
        String[] p = header.substring(6).trim().split(":");
        if (p.length != 3) {
            return null;
        }
        long ts;
        try {
            ts = Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (Math.abs(System.currentTimeMillis() / 1000 - ts) > MAX_SKEW_S) {
            return null;
        }
        for (Key k : keys) {
            if (k.id().equals(p[0])) {
                byte[] want = sign(k.secret(), k.id(), ts, path).getBytes(StandardCharsets.UTF_8);
                return MessageDigest.isEqual(want, p[2].getBytes(StandardCharsets.UTF_8)) ? k.name() : null;
            }
        }
        return null;
    }

    /** The header value a follower sends. */
    public static String authorization(String keyId, String secret, String path) {
        long ts = System.currentTimeMillis() / 1000;
        return "Hycon " + keyId + ":" + ts + ":" + sign(secret, keyId, ts, path);
    }

    static String sign(String secret, String keyId, long ts, String path) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((keyId + ":" + ts + ":" + path).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 missing from this Java runtime", e);
        }
    }

    // ---------- Share codes ----------

    public static String encode(Code c) {
        String json = new Json().beginObject().field("u", c.url()).field("k", c.keyId()).field("s", c.secret()).endObject().toString();
        return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static Code decode(String code) {
        String c = code == null ? "" : code.strip();
        if (!c.startsWith(CODE_PREFIX) || c.length() > 1000) {
            throw new IllegalArgumentException("That isn't a Hycon share code (it starts with " + CODE_PREFIX + ")");
        }
        try {
            Map<String, Object> m = JsonReader.object(new String(Base64.getUrlDecoder().decode(c.substring(CODE_PREFIX.length())), StandardCharsets.UTF_8));
            if (m.get("u") instanceof String u && m.get("k") instanceof String k && m.get("s") instanceof String s
                    && (u.startsWith("http://") || u.startsWith("https://"))) {
                return new Code(u, k, s);
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to the message below
        }
        throw new IllegalArgumentException("That share code is damaged; ask for it again");
    }

    // ---------- Servers this one follows ----------

    public synchronized void addSubscription(String name, Code code) throws IOException {
        checkName(name);
        if (subs.stream().anyMatch(s -> s.name().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("You already follow a server with that name");
        }
        if (subs.stream().anyMatch(s -> s.keyId().equals(code.keyId()) && s.url().equals(code.url()))) {
            throw new IllegalArgumentException("You already follow that server with this code");
        }
        subs.add(new Subscription(name, code.url(), code.keyId(), code.secret(), true));
        save();
    }

    public synchronized Subscription removeSubscription(String name) throws IOException {
        Subscription s = find(name);
        subs.remove(s);
        exceptions.keySet().removeIf(k -> k.startsWith(s.name().toLowerCase(Locale.ROOT) + "|"));
        save();
        return s;
    }

    public synchronized void setEnabled(String name, boolean enabled) throws IOException {
        Subscription s = find(name);
        subs.set(subs.indexOf(s), new Subscription(s.name(), s.url(), s.keyId(), s.secret(), enabled));
        save();
    }

    public Subscription find(String name) {
        for (Subscription s : subs) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("No such server");
    }

    /** Name of the subscription whose "banned by" id this is, or null for a ban made here. */
    public String sourceOf(UUID by) {
        for (Subscription s : subs) {
            if (s.uuid().equals(by)) {
                return s.name();
            }
        }
        return null;
    }

    // ---------- Local exceptions ----------

    /** A moderator lifted this shared ban here; keep it lifted until the source bans again. */
    public synchronized void addException(String subscription, UUID player, long since) throws IOException {
        exceptions.put(subscription.toLowerCase(Locale.ROOT) + "|" + player, since);
        save();
    }

    /** True if this exact shared ban (same start time) was lifted here. */
    public boolean isException(String subscription, UUID player, long since) {
        Long s = exceptions.get(subscription.toLowerCase(Locale.ROOT) + "|" + player);
        return s != null && s == since;
    }

    /** Drops exceptions for bans the source no longer lists, so the file doesn't grow forever. */
    public synchronized void pruneExceptions(String subscription, Set<UUID> stillListed) throws IOException {
        String prefix = subscription.toLowerCase(Locale.ROOT) + "|";
        if (exceptions.keySet().removeIf(k -> k.startsWith(prefix) && !stillListed.contains(UUID.fromString(k.substring(prefix.length()))))) {
            save();
        }
    }

    private void save() throws IOException {
        Json j = new Json().beginObject().field("publicAddress", publicAddress).name("keys").beginArray();
        for (Key k : keys) {
            j.beginObject().field("name", k.name()).field("id", k.id()).field("secret", k.secret()).field("created", k.created()).endObject();
        }
        j.endArray().name("subscriptions").beginArray();
        for (Subscription s : subs) {
            j.beginObject().field("name", s.name()).field("url", s.url()).field("keyId", s.keyId()).field("secret", s.secret())
                    .field("enabled", s.enabled()).endObject();
        }
        j.endArray().name("exceptions").beginObject();
        exceptions.forEach((k, v) -> j.field(k, v));
        j.endObject().endObject();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("sharing.json.tmp");
        Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Names are 2-32 letters, digits, spaces, _ . or -");
        }
    }
}
