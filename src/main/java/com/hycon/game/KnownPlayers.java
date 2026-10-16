package com.hycon.game;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names of players who have been online, so the panel can ban someone who already left and show
 * names (not just UUIDs) in the ban list. Saved to {@code players.json} only when something changed.
 */
public final class KnownPlayers {

    private final Path file;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public KnownPlayers(Path dataDir) {
        this.file = dataDir.resolve("players.json");
    }

    public void load() {
        try {
            if (Files.exists(file)) {
                for (Map.Entry<String, Object> e : JsonReader.object(Files.readString(file, StandardCharsets.UTF_8)).entrySet()) {
                    if (e.getValue() instanceof String n) {
                        put(UUID.fromString(e.getKey()), n);
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // only a name cache; starting empty is fine
        }
        dirty = false;
    }

    public void seen(UUID uuid, String name) {
        if (!name.equals(names.get(uuid))) {
            put(uuid, name);
            dirty = true;
        }
    }

    public String name(UUID uuid) {
        return names.get(uuid);
    }

    public UUID uuid(String name) {
        return byName.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized void saveIfChanged() {
        if (!dirty) {
            return;
        }
        dirty = false;
        Json j = new Json().beginObject();
        names.forEach((u, n) -> j.field(u.toString(), n));
        j.endObject();
        try {
            Path tmp = file.resolveSibling("players.json.tmp");
            Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            dirty = true; // try again next time
        }
    }

    private void put(UUID uuid, String name) {
        String old = names.put(uuid, name);
        if (old != null) {
            byName.remove(old.toLowerCase(Locale.ROOT), uuid);
        }
        byName.put(name.toLowerCase(Locale.ROOT), uuid);
    }
}
