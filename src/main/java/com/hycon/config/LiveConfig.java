package com.hycon.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The current {@code hycon.json}, read again when the file changes. Options that only matter when the panel
 * opens (address, ports, GeoIp) still need a restart; {@link HyconConfig#needsRestart} says when one changed.
 */
public final class LiveConfig implements Supplier<HyconConfig> {

    /** What a check found. */
    public enum Change { NONE, RELOADED, NEEDS_RESTART }

    private final Path dataDir;
    private final Path file;
    private final HyconConfig started;
    private volatile HyconConfig current;
    private long modified;

    public LiveConfig(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        this.file = dataDir.resolve("hycon.json");
        this.started = HyconConfig.load(dataDir);
        this.current = started;
        this.modified = modified();
    }

    @Override
    public HyconConfig get() {
        return current;
    }

    /** Reads the file again if it changed since the last check. A broken edit keeps the last good settings. */
    public synchronized Change reloadIfChanged() throws IOException {
        long now = modified();
        if (now == modified) {
            return Change.NONE;
        }
        modified = now;
        current = HyconConfig.load(dataDir);
        return started.needsRestart(current) ? Change.NEEDS_RESTART : Change.RELOADED;
    }

    private long modified() throws IOException {
        return Files.getLastModifiedTime(file).toMillis();
    }
}
