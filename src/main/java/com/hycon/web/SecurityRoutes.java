package com.hycon.web;

import com.hycon.auth.AdminStore;
import com.hycon.auth.Level;
import com.hycon.config.HyconConfig;
import com.hycon.sync.ShareServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Security tab: plain-language checks of how this panel is set up, worst first. Each one says what
 * is wrong and what to change, so an owner who isn't a network person can still lock the panel down.
 */
public final class SecurityRoutes {

    /** {@code level}: bad (fix now), warn (worth fixing), info (good to know) or ok. */
    record Check(String level, String title, String detail) {
    }

    private static final List<String> ORDER = List.of("bad", "warn", "info", "ok");

    private final WebServer web;
    private final Supplier<HyconConfig> config;
    private final AdminStore admins;
    private final ShareServer share;
    private final Supplier<String> updateNotice;

    public SecurityRoutes(WebServer web, Supplier<HyconConfig> config, AdminStore admins, ShareServer share, Supplier<String> updateNotice) {
        this.web = web;
        this.config = config;
        this.admins = admins;
        this.share = share;
        this.updateNotice = updateNotice;
    }

    public void register() {
        web.get("/api/security", Level.OWNER, this::checks);
    }

    List<Check> run() {
        HyconConfig c = config.get();
        List<Check> out = new ArrayList<>();
        if (admins.setupPending()) {
            out.add(new Check("bad", "An account still has the default password",
                    "Log in as root on the server machine and choose a new password."));
        }
        if (c.loopbackOnly()) {
            out.add(new Check("ok", "The panel only answers on this machine",
                    "Nobody can reach it from outside unless you put a tunnel or proxy in front."));
        } else if (!c.secureCookie()) {
            out.add(new Check("bad", "The panel is open to the network without HTTPS",
                    "Passwords and logins travel in plain text. Put HTTPS in front (docs/WEB-PANEL.md) and set SecureCookie to true, "
                            + "or set BindAddress back to 127.0.0.1."));
        } else {
            out.add(new Check("ok", "Reached over HTTPS", "Logins only travel encrypted (SecureCookie is on)."));
        }
        String ignored = web.untrustedProxy();
        if (c.trustProxy() && ignored != null) {
            out.add(new Check("bad", "A proxy's visitor addresses are ignored",
                    "Requests from " + ignored + " carried proxy headers, but that address isn't trusted, so every visitor shares one "
                            + "address for the login limit. If it's your proxy, add it to TrustedProxies in hycon.json."));
        } else if (!c.trustProxy() && !c.loopbackOnly() && !c.allowedHosts().isEmpty()) {
            out.add(new Check("info", "TrustProxy is off",
                    "Behind a proxy, every visitor then shares the proxy's address for the login limit. Turn it on if a proxy you control sits in front."));
        }
        List<String> without = admins.all().stream().filter(a -> a.level().atLeast(Level.ADMIN) && a.totp() == null)
                .map(AdminStore.Admin::name).toList();
        out.add(without.isEmpty()
                ? new Check("ok", "Every admin and owner uses two-factor", "A stolen password alone can't open their accounts.")
                : new Check("warn", "Admins without two-factor: " + String.join(", ", without),
                        "These accounts can run console commands. Each person turns two-factor on under My account (click your name)."));
        if (c.blockedCommands().isEmpty()) {
            out.add(new Check("info", "No console commands are blocked",
                    "ADMIN accounts can run any command. List ones they never need in BlockedCommands in hycon.json, e.g. [\"stop\", \"op\"]."));
        }
        if (share != null && share.running()) {
            out.add(new Check("info", "The ban sharing port is open (" + share.port() + ")",
                    "Other servers fetch your bans there. It only answers requests signed with one of your share keys."));
        }
        String update = updateNotice.get();
        if (update != null) {
            out.add(new Check("warn", "A newer Hycon is out", update));
        }
        out.sort((a, b) -> Integer.compare(ORDER.indexOf(a.level()), ORDER.indexOf(b.level())));
        return out;
    }

    private void checks(Req r) throws IOException {
        Json j = new Json().beginObject().name("checks").beginArray();
        for (Check ch : run()) {
            j.beginObject().field("level", ch.level()).field("title", ch.title()).field("detail", ch.detail()).endObject();
        }
        r.ok(j.endArray().endObject());
    }
}
