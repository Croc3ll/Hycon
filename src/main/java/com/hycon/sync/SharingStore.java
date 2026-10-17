package com.hycon.sync;

import com.hycon.auth.Passwords;
import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Ban sharing settings, kept in {@code sharing.json}:
 * <ul>
 *   <li><b>keys</b> other servers use to read this server's ban list. Only a SHA-256 of each key is
 *       stored (keys are long random tokens, so a slow hash isn't needed), so the file can't be
 *       used to read the feed.</li>
 *   <li><b>subscriptions</b> to other servers' ban lists. These hold the other server's key in
 *       plain text, because it has to be sent; keep this file private.</li>
 *   <li><b>exceptions</b>: shared bans a moderator lifted here. They stay lifted until the other
 *       server bans that player again (a ban with a different start time).</li>
 * </ul>
 */
public final class SharingStore {

    public record Key(String name, String hash, long created) {
    }

    public record Subscription(String name, String url, String token, boolean enabled) {
        /** "Banned by" id of bans imported from this subscription. */
        public UUID uuid() {
            return syncUuid(name);
        }
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9 _.-]{2,32}");

    private final Path file;
    private final List<Key> keys = new CopyOnWriteArrayList<>();
    private final List<Subscription> subs = new CopyOnWriteArrayList<>();
    /** "subscription|uuid" to the start time of the shared ban that was lifted here. */
    private final Map<String, Long> exceptions = new java.util.concurrent.ConcurrentHashMap<>();

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
        if (m.get("keys") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> k && k.get("name") instanceof String n && k.get("hash") instanceof String h) {
                    keys.add(new Key(n, h, k.get("created") instanceof Double d ? d.longValue() : 0));
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
        if (m.get("subscriptions") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> s && s.get("name") instanceof String n && s.get("url") instanceof String u
                        && s.get("token") instanceof String t) {
                    subs.add(new Subscription(n, u, t, !Boolean.FALSE.equals(s.get("enabled"))));
                }
            }
        }
    }

    public List<Key> keys() {
        return List.copyOf(keys);
    }

    public List<Subscription> subscriptions() {
        return List.copyOf(subs);
    }

    /** True if {@code token} is one of the share keys (constant-time per key). */
    public boolean validKey(String token) {
        if (token == null || token.length() > 200) {
            return false;
        }
        byte[] h = sha256(token).getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        for (Key k : keys) {
            ok |= MessageDigest.isEqual(h, k.hash().getBytes(StandardCharsets.UTF_8));
        }
        return ok;
    }

    /** New key; the token is returned once and never stored. */
    public synchronized String createKey(String name) throws IOException {
        checkName(name);
        if (keys.stream().anyMatch(k -> k.name().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("A key with that name exists");
        }
        String token = "hyk_" + Passwords.token();
        keys.add(new Key(name, sha256(token), System.currentTimeMillis()));
        save();
        return token;
    }

    public synchronized void revokeKey(String name) throws IOException {
        if (!keys.removeIf(k -> k.name().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("No such key");
        }
        save();
    }

    public synchronized void addSubscription(String name, String url, String token) throws IOException {
        checkName(name);
        if (subs.stream().anyMatch(s -> s.name().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("A subscription with that name exists");
        }
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://")) || url.length() > 300) {
            throw new IllegalArgumentException("Use the full feed address, starting with https://");
        }
        if (token == null || token.isBlank() || token.length() > 200) {
            throw new IllegalArgumentException("Paste the key the other server gave you");
        }
        subs.add(new Subscription(name, url.strip(), token.strip(), true));
        save();
    }

    public synchronized Subscription removeSubscription(String name) throws IOException {
        Subscription s = find(name);
        subs.remove(s);
        save();
        return s;
    }

    public synchronized void setEnabled(String name, boolean enabled) throws IOException {
        Subscription s = find(name);
        subs.set(subs.indexOf(s), new Subscription(s.name(), s.url(), s.token(), enabled));
        save();
    }

    public Subscription find(String name) {
        for (Subscription s : subs) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("No such subscription");
    }

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
    public synchronized void pruneExceptions(String subscription, java.util.Set<UUID> stillListed) throws IOException {
        String prefix = subscription.toLowerCase(Locale.ROOT) + "|";
        if (exceptions.keySet().removeIf(k -> k.startsWith(prefix) && !stillListed.contains(UUID.fromString(k.substring(prefix.length()))))) {
            save();
        }
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

    private void save() throws IOException {
        Json j = new Json().beginObject().name("keys").beginArray();
        for (Key k : keys) {
            j.beginObject().field("name", k.name()).field("hash", k.hash()).field("created", k.created()).endObject();
        }
        j.endArray().name("subscriptions").beginArray();
        for (Subscription s : subs) {
            j.beginObject().field("name", s.name()).field("url", s.url()).field("token", s.token()).field("enabled", s.enabled()).endObject();
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

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
