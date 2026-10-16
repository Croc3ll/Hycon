package com.hycon.game;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.util.MessageUtil;
import com.hycon.auth.AdminStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The "player" a panel command runs as. It has console rights (only ADMIN accounts may run commands,
 * see {@link com.hycon.auth.Level#ADMIN}) but its own name, {@code Hycon:<account>}, so server logs and
 * plugins can tell panel commands apart from the real console. Replies are collected for the browser.
 */
final class PanelCommandSender implements CommandSender {

    private final String name;
    private final UUID uuid;
    private final List<String> output = new ArrayList<>();

    PanelCommandSender(String admin) {
        this.name = "Hycon:" + admin;
        this.uuid = AdminStore.uuidFor(admin);
    }

    @Override
    public void sendMessage(@Nonnull Message message) {
        String text = MessageUtil.toAnsiString(message).toString(); // AttributedString.toString() is plain text
        synchronized (output) {
            if (output.size() < 500) { // a runaway command can't fill memory
                output.add(text);
            }
        }
    }

    List<String> output() {
        synchronized (output) {
            return List.copyOf(output);
        }
    }

    @Override
    public String getUsername() {
        return name;
    }

    @Override
    public UUID getUuid() {
        return uuid;
    }

    @Override
    public boolean hasPermission(@Nonnull String id) {
        return true;
    }

    @Override
    public boolean hasPermission(@Nonnull String id, boolean def) {
        return true;
    }
}
