package com.hycon.web;

import com.hycon.auth.Level;
import com.hycon.game.ChatLog;
import com.hycon.game.Moderation;

import java.io.IOException;
import java.util.UUID;

/** The Chat tab and the chat card on a profile: what players said, newest first. */
public final class ChatRoutes {

    /** Lines sent per page; "Older" asks for the next page. */
    private static final int PAGE = 200;

    private final WebServer web;
    private final ChatLog chat;
    private final Moderation moderation;

    public ChatRoutes(WebServer web, ChatLog chat, Moderation moderation) {
        this.web = web;
        this.chat = chat;
        this.moderation = moderation;
    }

    public void register() {
        web.get("/api/chat", Level.MODERATOR, this::list);
    }

    /** Optional {@code player} (one player's lines), {@code q} (a word to find) and {@code before} (time, for paging). */
    private void list(Req r) throws IOException {
        UUID player = null;
        String who = r.query("player");
        if (who != null && !who.isBlank()) {
            Moderation.Target t = moderation.resolve(who);
            if (t == null) {
                r.error(404, "Unknown player");
                return;
            }
            player = t.uuid();
        }
        String q = r.query("q");
        if (q != null && q.length() > 64) {
            q = q.substring(0, 64);
        }
        long before = Long.MAX_VALUE;
        try {
            if (r.query("before") != null) {
                before = Long.parseLong(r.query("before"));
            }
        } catch (NumberFormatException ignored) {
            // a bad page marker just shows the newest lines
        }
        var lines = chat.find(player, q, before, PAGE);
        Json j = new Json().beginObject().field("more", lines.size() == PAGE).name("lines").beginArray();
        for (ChatLog.Line l : lines) {
            j.beginObject().field("time", l.time()).field("uuid", l.player().toString()).field("name", l.name())
                    .field("text", l.text()).endObject();
        }
        r.ok(j.endArray().endObject());
    }
}
