# Hycon guide: setup and use

Contents: [1. Install](#1-install) · [2. First login](#2-first-login) · [3. Using the panel](#3-using-the-panel) ·
[4. Staff accounts](#4-staff-accounts) · [5. Settings (hycon.json)](#5-settings-hyconjson) ·
[6. Opening the panel from another computer](#6-opening-the-panel-from-another-computer) ·
[7. Files Hycon keeps](#7-files-hycon-keeps) · [8. Troubleshooting](#8-troubleshooting) ·
[9. Building from source](#9-building-from-source)

---

## 1. Install

You need a Hytale server on version 0.7.2 or newer (below 0.8.0).

1. Download `Hycon-<version>.jar` (or build it, see [section 9](#9-building-from-source)).
2. Stop the server and put the jar in the server's `mods` folder.
3. Start the server. In the server log you should see:

   ```
   [Hycon] Hycon first start: log in at http://127.0.0.1:8096/ as 'root' with password 'hycon' ...
   [Hycon] Hycon panel: http://127.0.0.1:8096/
   ```

Hycon has now made its data folder inside `mods` (named after the plugin, e.g. `mods/Hycon_Hycon/`)
with `hycon.json` and `admins.json` in it.

## 2. First login

1. On **the same computer as the server**, open `http://127.0.0.1:8096/` in a browser.
   The login page shows the default account while it is still unchanged.
2. Log in as **`root`** with password **`hycon`**.
3. The panel asks you to **choose your own password** (at least 10 characters, not containing the
   account name). Nothing else works until you do.

The default password is public, so Hycon only accepts it from the server machine itself
(a request from another address is refused and written to the audit log). Behind a reverse proxy,
set `TrustProxy` so Hycon can tell the real client address apart.

**Running the server on a rented host / VPS?** The panel only listens on that machine by default.
Either reach it through an SSH tunnel (quick and safe):

```bash
ssh -L 8096:127.0.0.1:8096 you@your-server
```

and then open `http://127.0.0.1:8096/` on your own PC, or set up HTTPS access as in
[section 6](#6-opening-the-panel-from-another-computer).

## 3. Using the panel

The tabs you see depend on your account level (see [section 4](#4-staff-accounts)).

### Players
Everyone online, with their world and UUID. Refreshes every 5 seconds.

- **Kick**: enter an optional reason; the player sees it when disconnected.
- **Ban**: enter a reason and pick how long: 30 minutes up to 30 days, or **Permanent**.
- **Banned players** show "Banned · 2d 4h left" (or "permanent") in red, with an **Unban** button.
- **All players**: below the online list is everyone who has ever joined, online or offline,
  with their status ("Online" or "Last seen 3 h ago"), UUID and first-seen date. Type in the search
  box to find someone by part of their name or the start of their UUID, then **Ban** (or **Kick** if
  they're online). On first start Hycon also reads the players the server saved before Hycon was
  installed (`universe/players`), so they can be found too.

- **Warn**: type a reason. If the player is online, a big "Warning" title appears in the middle of
  their screen, the reason shows in a red notification popup ("Warning #2"), and a chat line
  "[Warning #2] ..." keeps it readable afterwards. Offline players get the warning
  saved to their record only. Every warning is counted.

### Player profile
Click any player's name to open their profile (their record). Each player gets an emblem in
the colour of their UUID (Hytale offers no picture of a player's character to show instead).

- UUID, first and last seen, last world, and whether they're online,
- a count of warnings, kicks and bans, and their current ban if any,
- buttons to Warn, Kick, Ban or Unban,
- the full **history**: every warning, kick, ban and unban with when, who and why, plus
  **staff notes** (written by moderators, never shown to the player).

This history is kept in `moderation.log` and stays even after a ban ends.

**Ping** (on the profile): the player's ping as the server measures it, a one-minute average
taken every minute while they're online, kept 14 days. Shows median, average, the 95% mark,
worst, best, jitter (how much it jumps minute to minute), share of minutes over 150 and 250 ms,
and spikes (a short burst of 250 ms or more, marked in red on the chart). **Steady** means 95% of
minutes under 150 ms with little jitter; **Unstable** otherwise. Useful when someone blames lag,
or when deciding whether odd behaviour could be connection trouble.

Player-versus-player **kills** show on the ping chart as dots at the killer's ping at that second
(red when 150 ms or more), and deaths as hollow circles; hover a dot for who and when. Below the
chart a verdict says how many of the player's kills came at high ping. With at least 5 kills and
more than half at high ping it says **Worth an eye**: a pattern seen with "lag switching" (lagging
on purpose so others can't hit back). It's a pointer for a human look, not proof.

The **UUID** is Hytale's permanent ID for an account. It never changes, even if the player changes
their name, so bans are always stored by UUID.

Names must match exactly (any upper/lower case). Hycon never guesses a "closest" name, so a typo
can't ban the wrong player.

### Bans
Every active ban, with a **Temporary** or **Permanent** badge and, for temporary bans, the
**time left** (counts down; hover for the exact end). Filter with All / Temporary / Permanent,
ban someone by name or UUID from the box at the top, and **Unban** to lift a ban.

Hycon uses the server's own ban list (`bans.json`), so bans made with `/ban` in game show up here
and the other way round. Temporary bans end on their own. Bans from the panel show
`Hycon:<account>` as "by".

### Console
The live server log, updated every 1.5 seconds. Warnings are yellow, errors red, panel commands blue.
Scroll up to read back; it keeps scrolling with new lines while you're at the bottom.

ADMIN and OWNER accounts also get a command box. Type a command without the `/`
(e.g. `who`, `say Restart in 5 minutes`) and press Enter. Arrow up/down go through what you typed.
Commands run with **full console rights**, as the sender `Hycon:<your account>`. The reply shows in
the log. If a command takes longer than 5 seconds, the panel stops waiting but the command keeps running.

### Analytics, and dashboards from other plugins
**Analytics** shows players online now, unique players, peak and average players, uptime and
memory, for the last 24 hours, 7 days or 30 days (buttons at the top right). Below that:

- **Players online** over time; red bands are times the server was off. Hover for exact values,
  or switch to **Table** for the raw numbers.
- **Memory used** over time.
- **Average players by hour** of the day, and **Most playtime**.
- **Most active players**: playtime, sessions, first and last seen.
- **New players**, **Returning** players (here before this period) and **Came back**: the share
  of players who played again on a later day.
- **Players who stick around**: most days played in the last 7 days (or the chosen range), with
  sessions, playtime and average session length.

Hycon samples every 30 seconds and keeps 31 days (`samples.log`). Playtime counts from when Hycon
was installed. Other plugins can add their own tabs next to Analytics.

### Audit log (ADMIN and up)
The newest 200 actions: logins (and failed ones), kicks, bans, unbans, commands and account changes,
with the account and the address they came from. Failed or refused actions are marked ✗.
The full history is in `audit.log` ([section 7](#7-files-hycon-keeps)).

### Your account
Top right: **Log out** ends the session. To change a password, an OWNER uses **Reset password**
in the Accounts tab; the person then logs in with the temporary password and chooses a new one. Sessions also end by themselves after 30 idle minutes
or 12 hours, whichever comes first (both can be changed in `hycon.json`).

## 4. Staff accounts

Each staff member should have their own account, so the audit log shows who did what.

| Level     | Can                                                                         |
|-----------|-----------------------------------------------------------------------------|
| VIEWER    | See players, bans and dashboards                                            |
| MODERATOR | Also kick, ban, unban, and watch the live console                           |
| ADMIN     | Also run console commands (full console rights) and read the audit log      |
| OWNER     | Also create, change and remove panel accounts                               |

Give out ADMIN carefully: it is the same as access to the server console.

**Add an account** (OWNER): Accounts tab → enter a name (3-32 letters, digits, `_ . -`), pick a
level, **Create**. A temporary password is shown **once**; send it to the person privately.
They must pick their own password at first login.

**Change a level**: use the dropdown in the list. The person is logged out so their page reloads
with the new level.

**Forgotten password**: **Reset password** gives a new temporary one (shown once) and logs them out.

**Remove**: **Remove** deletes the account and ends its sessions. The last OWNER can't be removed
or demoted, so you can't lock everyone out by accident.

**Locked out completely?** In the server console (not in game):

```
/hycon resetpassword root
```

writes a new password for `root` to `FIRST-LOGIN.txt` in Hycon's data folder (not to the log,
which is visible in the panel). With a name that doesn't exist, it creates
that account as OWNER. `/hycon info` prints the panel's address.

## 4b. Sharing bans between servers

OWNER accounts get a **Ban sharing** tab. Servers that run Hycon can share their ban lists, for
example one global ban list for a network. Owners swap a single **share code**; nobody has to give
out their panel address, and the panel itself stays private on 127.0.0.1.

**Set up (on the server that shares its bans)**

1. Ban sharing → *This server's address*: the address other servers reach it at, usually the same
   one players use (`play.example.com` or an IP). Save.
2. *New share code*: type which server it's for (e.g. "Server 2") and **Create code**. Copy the
   `hycon1_...` code and send it to that server's owner privately. **Show code** shows it again later.

**Follow (on the other server)**: Ban sharing → *Follow a server* → a name and the code → **Follow**.

From then on, every 2 minutes, new bans from that server are added here (shown as
"Shared: <name>" in Bans), and bans lifted or expired there are lifted here. Only bans a server
made itself are passed on, so servers that follow each other never bounce bans back and forth.

**A global ban list**: every server follows every other server, or all servers follow one main
server whose staff do the banning.

**What stays local**

- Bans made on your own server are never changed by sharing.
- Unbanning a shared ban here keeps it unbanned here, until that server bans the player again.
- **Pause** stops checking but keeps the shared bans. **Remove** stops following and lifts every
  ban that came from that server. **Revoke** a code and that server stops receiving your bans.

**Network and security**

- Bans are served on a separate **share port** (`SharePort`, default 8097). It only opens once you
  create your first code, closes when you revoke the last one, and answers nothing but the ban list.
  If you share with servers on other machines, open that port in your firewall/router, like the game port.
- The secret in a code never travels over the network: each request is signed with it and only
  valid for 5 minutes, so it can't be stolen or replayed even without HTTPS. Server clocks must be
  within 5 minutes of each other. The ban list itself (names, reasons) is not encrypted.
- Wrong signatures are limited like failed logins and written to the audit log.
- `sharing.json` holds the secrets of your codes and of the servers you follow: keep it private.

## 5. Settings (hycon.json)

`hycon.json` is in Hycon's data folder and explains every option in its own comments.
Changes need a **server restart**.

| Option               | Default       | What it does                                                                                      |
|----------------------|---------------|---------------------------------------------------------------------------------------------------|
| `Enabled`            | `true`        | `false` turns the panel off completely.                                                           |
| `BindAddress`        | `"127.0.0.1"` | Network address to listen on. `127.0.0.1` = this machine only. See section 6 before changing it. |
| `Port`               | `8096`        | Port of the panel. Change it if 8096 is taken.                                                    |
| `AllowedHosts`       | `[]`          | Extra host names the panel may be opened under, e.g. `["admin.example.com"]`.                     |
| `SecureCookie`       | `false`       | Set `true` when the panel is only reached over HTTPS.                                             |
| `TrustProxy`         | `false`       | Set `true` only behind your own reverse proxy, so real client addresses are logged and limited.   |
| `SessionIdleMinutes` | `30`          | Log out after this long without activity.                                                         |
| `SessionMaxHours`    | `12`          | Log out at the latest this long after logging in.                                                 |
| `LoginAttempts`      | `5`           | Failed logins allowed per address and per account name...                                         |
| `LoginWindowMinutes` | `10`          | ...within this many minutes. After that, logins wait until the window has passed.                 |
| `BlockedCommands`    | `[]`          | Commands the panel refuses even for ADMIN, e.g. `["stop", "op"]`.                                 |
| `LogLines`           | `2000`        | How many recent log lines the Console tab keeps.                                                  |

## 6. Opening the panel from another computer

The panel can ban players and run commands, so think of it like your server's console password.

**Simplest and safest: an SSH tunnel** (see [section 2](#2-first-login)). Nothing to configure.

**Permanent web address: HTTPS reverse proxy.** Keep `BindAddress` at `127.0.0.1` and put a web
server with HTTPS in front of Hycon on the same machine. Example with [Caddy](https://caddyserver.com),
which gets a certificate by itself:

```
admin.example.com {
    reverse_proxy 127.0.0.1:8096
}
```

or nginx:

```nginx
server {
    listen 443 ssl;
    server_name admin.example.com;
    # ssl_certificate / ssl_certificate_key ...
    location / {
        proxy_pass http://127.0.0.1:8096;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
    }
}
```

Then in `hycon.json`:

```json
"AllowedHosts": ["admin.example.com"],
"SecureCookie": true,
"TrustProxy": true,
```

and restart. Open `https://admin.example.com/`.

**Don't** set `BindAddress` to `0.0.0.0` and open the port to the internet without HTTPS:
passwords and session cookies would travel unencrypted. Hycon warns in the log if it listens
beyond the local machine.

## 7. Files Hycon keeps

All in Hycon's data folder:

| File              | What                                                                                             |
|-------------------|--------------------------------------------------------------------------------------------------|
| `hycon.json`      | Settings (yours to edit; Hycon never rewrites it).                                               |
| `admins.json`     | Panel accounts with **hashed** passwords. Managed by Hycon; change accounts in the panel. Keep it private anyway. |
| `audit.log`       | Every panel action, one JSON line each, only ever added to. Rotate or archive it yourself if it grows. |
| `moderation.log`  | Every player's history: warnings, kicks, bans, unbans and staff notes, one JSON line each.     |
| `samples.log`     | Players online and memory every 30 s, kept 31 days, for the Analytics charts.                    |
| `sharing.json`    | Your share codes, followed servers and local exceptions. Holds secrets: keep it private.        |
| `kills.log`       | Player-versus-player kills with the killer's ping, kept 14 days.                              |
| `ping/`           | One small file per player with their ping every minute, kept 14 days.                          |
| `players.json`    | Player directory: UUID, name, first/last seen and last world of everyone who has joined.         |
| `FIRST-LOGIN.txt` | Only after `/hycon resetpassword`; holds the new password until the account changes it.          |

Back up `admins.json` and `audit.log` with the rest of your server.

## 8. Troubleshooting

| Problem                                                     | Fix                                                                                                         |
|-------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| Log says `could not start the panel on 127.0.0.1:8096`      | Another program uses the port. Change `Port` in `hycon.json` and restart.                                   |
| Browser shows "Unknown host name"                           | You opened the panel under a name Hycon doesn't know. Use `127.0.0.1`, or add the name to `AllowedHosts`.   |
| "Too many failed logins"                                    | Wait the number of minutes shown (default up to 10), or restart the server to clear it.                     |
| "Bad or missing CSRF token; reload the page"                | Your session ended (restart or timeout). Reload the page and log in again.                                  |
| "Choose a new password first"                               | The account still has a temporary password; the panel shows the change form after login.                    |
| Can't log in the first time                                 | Use `root` / `hycon` from the server machine. Changed and forgotten? Run `/hycon resetpassword root` in the server console and read `FIRST-LOGIN.txt`. |
| "Log in from the server machine itself..."                  | The default password only works locally. Log in on the server machine (or through an SSH tunnel) first. |
| "Unknown player" when banning someone offline               | Use their UUID, or the exact name of someone who has been online since Hycon was installed.                 |
| A command is refused as blocked                             | It's listed in `BlockedCommands` in `hycon.json`.                                                           |
| Panel doesn't appear at all                                 | Check the log for `Hycon`. `Enabled` may be `false`, or `admins.json` may be damaged (the log says so).      |

## 9. Building from source

Needs JDK 25.

```bash
git clone https://github.com/Croc3ll/Hycon.git
cd Hycon
gradlew build
```

The jar is in `build/libs/`. `test-server.bat` (or `gradlew runServer`) builds it and starts a
local test server in `run/` with Hycon loaded; the panel's data folder then links to
`src/main/resources` (those runtime files are git-ignored).

For adding your own dashboard tab from another plugin, see `docs/ADDING-A-PANEL.md`.
