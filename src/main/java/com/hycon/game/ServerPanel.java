package com.hycon.game;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hycon.api.HyconPanel;
import com.hycon.api.PanelData;

import java.time.Duration;

/**
 * Hycon's own "Server" dashboard, built through the same {@link HyconPanel} API other plugins use,
 * so the API is exercised even on servers without any.
 */
public final class ServerPanel implements HyconPanel {

    @Override
    public String id() {
        return "server";
    }

    @Override
    public String title() {
        return "Server";
    }

    @Override
    public void render(PanelData out) {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long upMs = System.currentTimeMillis() - HytaleServer.get().getBootStart();
        out.stat("Players online", String.valueOf(Universe.get().getPlayers().size()))
                .stat("Uptime", uptime(upMs))
                .stat("Memory used", mb(used) + " MB", "of " + mb(rt.maxMemory()) + " MB max")
                .stat("CPU cores", String.valueOf(rt.availableProcessors()))
                .stat("Java", Runtime.version().toString());
    }

    private static String uptime(long ms) {
        Duration d = Duration.ofMillis(Math.max(0, ms));
        return d.toDays() > 0 ? d.toDays() + "d " + d.toHoursPart() + "h" : d.toHoursPart() + "h " + d.toMinutesPart() + "m";
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }
}
