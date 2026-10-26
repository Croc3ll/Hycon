# Hycon

A web admin panel (RCON-style) for Hytale servers, running as a server plugin. Open it in a browser
on the same machine: see who's online, kick, ban and unban (temporary or permanent, with a reason),
run console commands, watch the live log, and view dashboards other plugins add.

**Full setup and usage guide: [docs/GUIDE.md](docs/GUIDE.md)** (install, first login, every tab,
staff accounts, all settings, remote access over HTTPS, troubleshooting).

## Features

- **Players**: who's online and where, plus a searchable directory of everyone who has ever joined
  (online or offline, first and last seen).
- **Moderation**: warn, kick, ban (temporary or permanent, with a reason) and unban. Each player has a
  **record** of warnings, kicks, bans and staff notes on their profile.
- **Player profiles**: playtime, sessions, minutes online per day and by hour, PvP kills and deaths,
  moderation record, and any sections other plugins add.
- **Risk score**: every profile gets a 0-100 score (Low / Medium / High / Very high) built from warnings,
  kicks, bans, open and closed reports, other accounts sharing their connection (and whether those are
  banned), and new accounts, plus reasons plugins add. Each point is listed with its reason, so it's a
  pointer for staff, not a black box.
- **Reports and alts**: player reports to work through, and accounts linked by connection or by often
  joining together.
- **Bug reports**: players send them in game with `/bug`; staff read and close them in the panel.
- **Console**: the live server log, and console commands for admins (with a blocked-commands list).
- **Analytics**: player counts, ping, activity and other charts, plus dashboards from other plugins.
- **Ban sharing** between servers (below) and a full **audit log** of staff actions.
- **Remember me**: tick it at login and the browser logs you in by itself for 30 days, even across
  server restarts. Logging out, a password change or losing the account ends it.
- **Stays current**: open pages refresh their data by themselves and reload when Hycon is updated.

## Install

Put `Hycon-x.y.z.jar` in the server's `mods` folder and start the server. Hycon creates
`hycon.json` (commented settings) and the default account:

| Account | Password |
|---------|----------|
| `root`  | `hycon`  |

Open `http://127.0.0.1:8096/` **on the server machine**, log in with those, and choose a new
password; the panel allows nothing else until you do. Until it's changed, the default password
only works from the server machine itself. Then add accounts for your staff in the **Accounts** tab.

Locked out? In the server console: `/hycon resetpassword <name>` writes a new password to
`FIRST-LOGIN.txt` (and creates the account as OWNER if it doesn't exist).

## Ban sharing

Owners can share ban lists between servers running Hycon (for example a network-wide ban list).
Each server makes a share code for the others; following a code imports that server's bans every
2 minutes and lifts them when they're lifted there. Bans made locally are never touched, the panel
stays private, and codes are never sent over the network (requests are signed). See
[docs/GUIDE.md](docs/GUIDE.md#4b-sharing-bans-between-servers).

## Account levels

| Level     | Can                                                            |
|-----------|----------------------------------------------------------------|
| VIEWER    | see players, bans, plugin dashboards                           |
| MODERATOR | + kick, ban, unban, read the live console                      |
| ADMIN     | + run console commands (full console rights), read audit log   |
| OWNER     | + create, change and remove panel accounts                     |

## Security

The panel can ban players and run commands, so:

- It listens on **127.0.0.1 only** by default. To reach it from elsewhere, put an HTTPS reverse
  proxy in front, set `SecureCookie`, `AllowedHosts` and (only behind your own proxy) `TrustProxy`.
- Passwords are stored as PBKDF2-SHA256 hashes (600,000 rounds) in `admins.json`.
- Sessions use an HttpOnly, SameSite=Strict cookie; they expire when idle and after a maximum time.
  Logging in, and changing a password, issue a new session id.
- "Remember me" uses a random token in its own HttpOnly cookie; the server keeps only its SHA-256
  hash (`remember.txt`), so a copy of that file can't be used to log in.
- Every POST (login included) needs the session's CSRF token, JSON content and a same-origin
  `Origin`. Unknown `Host` headers are refused (DNS rebinding).
- Failed logins are limited per address and per account name (`LoginAttempts` per `LoginWindowMinutes`).
- A strict Content-Security-Policy; all text is put on the page as text, never as HTML.
- Every login, failed login, kick, ban, unban, command and account change goes to `audit.log`
  (append-only, one JSON object per line).
- `BlockedCommands` in `hycon.json` lists commands the panel refuses even for ADMIN.

Bans use Hytale's own ban list (`bans.json`, same as `/ban`), so the server enforces them and
temporary bans expire by themselves. "Banned by" shows `Hycon:<account>`.

## For plugin developers: adding a dashboard

```java
public final class MyPanel implements HyconPanel {
    public String id() { return "myplugin"; }
    public String title() { return "My plugin"; }
    public void render(PanelData out) {
        out.stat("Things", "42");
        out.series("Things over time", "count").point(System.currentTimeMillis(), 42);
        out.table("Top", "Player", "Score").row("Alice", 10);
    }
}
// start():    HyconApi.register(new MyPanel());
// shutdown(): HyconApi.unregister("myplugin");
```

Beyond tabs, the API (`HyconApi.API_VERSION`, currently 6) lets a plugin:

- add a **section to every player profile** (`registerProfileSection`, version 3);
- put **forms and row buttons** on its tab and handle them in `action(...)`, with the viewer's
  account level (version 4);
- write **warnings and notes to a player's record** (`HyconApi.record`, version 5);
- add **reasons to the risk score** (`registerRiskSource`, version 6).

Read `API_VERSION` at run time (reflectively, since the constant is compiled in) before using newer calls.

List `Hycon:Hycon` as an optional dependency and keep Hycon imports in one class you only use when
Hycon is installed. See [docs/ADDING-A-PANEL.md](docs/ADDING-A-PANEL.md) for a full example.

## Develop

`test-server.bat` (or `gradlew runServer`) builds the plugin and starts a local server in `run/`.

To test inside another server too, add `hyconTestMods=<that server>/mods` to your own
`~/.gradle/gradle.properties`; every `gradlew build` then copies the jar there (stop that server first,
Windows locks the jar while it runs).
