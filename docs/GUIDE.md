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

The tabs you see depend on your account level (see [section 4](#4-staff-accounts)). They sit in a sidebar in
four groups: **Moderation** (Players, Bans, Reports, Bugs, Chat), **Server** (Analytics, Performance, Console,
Schedule), **Plugins** (tabs other plugins add) and **Admin** (Security, Presets, Audit log, Ban sharing, Accounts).
Reports and Bugs show how many are open. On a phone the tabs sit behind a menu button.

**Needs attention**: moderators and up see one line above every page with what needs them now: open reports and
bugs, the server lagging (TPS under 90%) or memory over 90% full, and (owners) security problems. Click an item to
open its tab. When nothing is waiting it says "All clear". It refreshes every 30 seconds.

**Language**: the dropdown at the top right switches the panel between English, Spanish, German, French,
Portuguese, Polish, Russian, Italian, Dutch and Turkish (the same languages as hytaleserver.xyz). It starts in your
browser's language and remembers your pick. Player names, chat and logs stay as they are. Translators: the files are
`src/main/resources/web/lang/<code>.json`, keyed by the English text; `py tools/make_lang.py` fills new lines through DeepL.

### Players
Everyone online, with their world, how long they've been on (**Session**), their total **Playtime**, their
**Ping** (green, gold or red) and their **Risk** (Low to Very high; hover for the score). Refreshes every 5
seconds. Point at a name to see the UUID. Point at a row and its buttons appear: **Copy UUID**, **Punish**,
and **Unmute** or **Unban** when that applies. Muted players have a purple **Muted** badge.

- **Punish** opens one dialog with five choices:
  - **Preset**: pick the offence ("Spam", "Hacking", ...). The dialog shows the steps for that offence and which one
    this player gets next (a first offence gets the first step). See [Presets](#presets-owner).
  - **Warn**, **Mute**, **Kick** or **Ban** with your own reason. Mutes and bans take a time: 10 minutes up to 30 days,
    or **Permanent**.
- **Mute**: the player can't chat until it ends. Each line they try is stopped before it reaches anyone, and they
  are told why and for how long.
- **Banned players** show "Banned · 2d 4h left" (or "permanent") in red, muted ones "Muted · 1h 20m left".
- **All players**: below the online list is everyone who has ever joined, online or offline,
  with their status ("Online" or "Last seen 3 h ago"), playtime and when they first joined. Type in the search
  box to find someone by part of their name or the start of their UUID. On first start Hycon also reads the
  players the server saved before Hycon was installed (`universe/players`), so they can be found too.

- **Warn**: type a reason. If the player is online, a big "Warning" title appears in the middle of
  their screen, the reason shows in a red notification popup ("Warning #2"), and a chat line
  "[Warning #2] ..." keeps it readable afterwards. Offline players get the warning
  saved to their record only. Every warning is counted.

### Player profile
Click any player's name to open their profile (their record). Each player gets an emblem in
the colour of their UUID (Hytale offers no picture of a player's character to show instead).
Everything under the top card is in tabs (Activity, other plugins' sections, Ping, Chat, Location,
Connections, History), one at a time; the tab you pick stays open when you move to another player.

- UUID, first and last seen, last world, and whether they're online,
- a count of warnings, kicks and bans, and their current ban if any,
- buttons to Warn, Kick, Ban or Unban,
- the full **history**: every warning, kick, ban and unban with when, who and why, plus
  **staff notes** (written by moderators, never shown to the player).

This history is kept in `moderation.log` and stays even after a ban ends.

**Player stats** (on the profile, with its own 24 h / 7 d / 30 d / All time picker):

- **Activity**: playtime, sessions and average length, days played in the last 30 days, PvP
  kills/deaths, warnings and reports; minutes online per day and by hour of day; pie charts of
  their moderation record and PvP.
- **Where and what**: the world they spend most time in, distance walked, a **heatmap** of where
  they spend time over the real world map: smooth heat from **blue (little time) to red (most
  time)**, the **path** they walked (each stretch coloured the same way), **markers** where they
  broke (B), placed (P) or used (U) blocks, and a "Last seen here" pin. Zoom with **+ / − / Fit** or
  click the map and scroll; drag to move; hover for coordinates, time spent and what was done there.
  With several worlds (e.g. a hideout world), a **World** row of tabs picks which world's map
  to show, with the time spent in each. The **Map / Heat / Path / Marks** buttons switch layers on and off, and **↻ Refresh** reloads the
  numbers without losing your zoom, and pie
  charts of the blocks they break, place and use and what they craft, plus the zones they found.
- Other plugins can add their own sections here (for example, an economy plugin could show where their money
  comes from and goes).

Positions are noted every 15 seconds while players are online (kept 14 days per player); interactions
are counted since this version of Hycon was installed. The **Analytics** tab shows the same kind
of heatmap for the whole server. The first time an area's map is shown, Hytale may need a few
seconds to draw it; it's cached for 5 minutes after that (areas up to 48x48 chunks).

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
punish someone by name or UUID from the box at the top, and **Unban** to lift a ban. Below the bans is
every active **mute**, with an **Unmute** button.

Hycon uses the server's own ban list (`bans.json`), so bans made with `/ban` in game show up here
and the other way round. Temporary bans end on their own. Bans from the panel show
`Hycon:<account>` as "by".

### Reports (MODERATOR and up)
Players report others in game with **`/report <player> <reason>`** (one report a minute, one open
report per player they report). The **Reports** tab lists them, with the open count in the tab
name: **Warn** or **Ban** straight from a report, mark it **Handled** (with an optional note, which is
also saved in the player's history) or **Dismiss** it. Staff with the permission
`hycon.reports.notify` get a chat line in game for every new report.

### Chat (MODERATOR and up)
The **Chat** tab shows everything players said in chat, newest first, kept 14 days. Type a word or
a name to find it, and press **Older** to go further back. Click a name to open that player's
profile. Every profile also has a **Chat history** card with just that player's lines. Lines that a
mute or chat filter cancelled are not kept.

### Connection map (on profiles, MODERATOR and up)
A web of everyone tied to the player, and everyone tied to them ("further out"), with a summary
like "18 direct, 10 further out · among them 3 banned, 5 reported". Ties:

| Tie | Meaning |
|---|---|
| **Same connection** (purple) | Joined from the same IP address: alt accounts or one household. |
| **Joins together** (dashed) | Joined within 2 minutes of each other at least 3 times: they play together. |
| **Reported** (orange) | One reported the other with `/report`. |
| **Killed** (red) | Player-versus-player kills in the last 14 days. |

Rings: red = banned (filled red = a ban shared from another server), orange = reported; online
players are lighter. Click a player to see their ties in a side panel, click a tie to walk across,
**Expand** to pull in that player's own web, **Open profile** to jump there. Drag players to pin them,
drag the background to pan, click the map then scroll to zoom; **Settle**, **Fit** and **Unpin** tidy up.

IP addresses are never stored: Hycon keeps only a keyed hash (HMAC with a secret made for your server)
in `ties.json`, enough to tell "same address" and nothing more. Ties start building from when this
version is installed.

### Console
The live server log, updated every 1.5 seconds. Warnings are yellow, errors red, panel commands blue.
Scroll up to read back; it keeps scrolling with new lines while you're at the bottom.

ADMIN and OWNER accounts also get a command box. Type a command without the `/`
(e.g. `who`, `say Restart in 5 minutes`) and press Enter. Arrow up/down go through what you typed.
Commands run with **full console rights**, as the sender `Hycon:<your account>`. The reply shows in
the log. If a command takes longer than 5 seconds, the panel stops waiting but the command keeps running.

### Analytics, and dashboards from other plugins
**Analytics** shows players online now, unique players, peak and average players, uptime and
memory, for the last 24 hours, 7 days or 30 days (buttons at the top right). Below that, in tabs
(Activity, Where players are, Players, World, More):

- **Players online** over time (the most online in each step); red bands are times the server was off. Hover for exact values,
  or switch to **Table** for the raw numbers.
- **Memory used** over time.
- **Most players by hour** of the day, and **Most playtime**.
- **Most active players**: playtime, sessions, first and last seen.
- **New players**, **Returning** players (here before this period) and **Came back**: the share
  of players who played again on a later day.
- **Players who stick around**: most days played in the last 7 days (or the chosen range), with
  sessions, playtime and average session length.

**Player globe** (MODERATOR and up), under "Where players are": every player who has joined is a dot
on a 3D globe, green under 80 ms ping, yellow up to 150, red above; online players are brighter.
Drag to turn it, scroll to zoom, hover a dot for the player's place and ping, click it to open their
profile. Next to it, **Countries** lists players and median ping per country. Set `ServerLocation`
to draw a line from every player to the server. Each **profile** also has a **Location** card: a globe
zoomed in on the player, their place, country, median ping and distance to the server, and a warning
when the ping is too low for that distance (they're probably on a VPN or proxy).

Locations come from each player's last join address, looked up in the free DB-IP city database
(db-ip.com, CC BY 4.0), which Hycon downloads once a month (about 60 MB). They're city-level at best
and can be wrong. Addresses themselves aren't shown. Set `GeoIp` to `false` to turn it off.

Hycon samples every 30 seconds and keeps 31 days (`samples.log`). Playtime counts from when Hycon
was installed. Other plugins can add their own tabs next to Analytics.

### Performance (MODERATOR and up) and /lag
Hycon's profiler is always on and costs well under 1% of the server. It shows which plugins use the
server's time and what caused each lag spike, so you can tell a slow plugin from a busy server.

- **Server health**, 0 to 100, for a quick look: 90+ excellent, 75+ good (in good standing), 50+ fair, below that poor.
  It starts at 100 and loses points for slow or frozen ticks, lag spikes, full memory, a slow shared scheduler and file
  or database waits on game threads, and lists what cost the points. Charts show it over the chosen range and the last
  7 days (hourly, kept 30 days in `health-history.jsonl`).
- **Verdict** at the top in plain words: healthy, busy or lagging, the plugin using the most time, and
  any plugin that waits on files or a database on a game thread.
- **Who uses the world threads** and **the shared scheduler thread**: each plugin's share of the time.
  A plugin is also blamed for the Hytale code it calls (setting blocks, sending packets).
- **Tick time** over time (average and worst; 33 ms is the budget for 30 ticks a second), and each top
  plugin's share over time.
- **History graphs** over the chosen range (up to a day): lag spikes, memory in use, players online, and
  each world's loaded chunks, entities and NPCs.
- **Lag spikes**: every tick over `LagSpikeMs`, with the players who were in that world (for the scheduler, everyone online), how long it took, what ran during it, the ECS system (or
  scheduler task) seen most, whether a chunk load, world generation or a save was running, and whether garbage
  collection caused it. During a long tick the profiler looks every 5 ms, so the cause is exact.
- **Late scheduler tasks**: each time a task on Hytale's shared scheduler started 20 ms or more late, and the
  task the scheduler was last seen running before it (the one that held it up). **Slowest scheduler tasks**:
  each task by its run method and plugin, its longest run, busy time a minute and runs seen. These come from
  samples, so very short tasks are missed and lengths are accurate to about one sample.
- **Where the time goes**: the busiest methods in each plugin. **Plugin drill-down**: per plugin, its share,
  top methods, top ECS systems and top scheduler tasks in one row. **Slowest ECS systems**: Hytale's own
  per-system timings, with the plugin each belongs to.
- **Waiting on files, network or a database**: work that freezes the world until it finishes, by plugin and
  the method that started the wait. The file or table name isn't on a thread's stack, so the method is the
  most a sampler can name.
- **Worlds**: tick times, entities, NPCs and loaded chunks per world, and **world-thread time by world** with
  the plugins using each.
- **Network traffic per player**: bytes sent to and received from each player in the last minute, from
  Hytale's own packet counters (as sent on the wire, compressed).
- **Memory** also shows the longest single garbage collection pause in the range.
- **Plugins by version**: each plugin's share on its current version against its version before, so you can
  see whether an update made it slower.

In game (or the console), **`/lag`** shows the last 5 minutes right away; **`/lag 60`** the last hour and
**`/lag spikes`** the latest spikes. It needs the `hycon.lag` permission. Staff with `hycon.lag.alerts` are told when
a plugin uses over `LagAlertPercent` of the world threads for 3 minutes, a spike lasts over `AlertSpikeMs`, a
minute has `AlertSpikesPerMinute` spikes, memory stays over `AlertMemoryPercent` for 3 minutes, or Server health stays
under `AlertHealthBelow` for 10 minutes
(also sent to `LagWebhook` if set; each alert repeats at most every 15 minutes). `/lag` and the alerts work even with the panel turned off.

How it stays light: tick and system times are timers Hytale already keeps. On top of that one Hycon
thread looks at what each world thread and the scheduler are doing about 20 times a second, and slows
itself down if that ever costs over 0.5%. Memory use per plugin is not measured (that can't be done cheaply).

### Audit log (ADMIN and up)
The newest 200 actions: logins (and failed ones), kicks, bans, unbans, commands and account changes,
with the account and the address they came from. Failed or refused actions are marked ✗.
The full history is in `audit.log` ([section 7](#7-files-hycon-keeps)).

### Schedule (ADMIN and up)
- **Daily restarts**: type times like `04:00, 16:00` (24-hour, the server's time zone) and how many minutes before
  to warn players (e.g. `15, 5, 1`). Players also get a last warning 30 and 10 seconds before. With AMP set up
  (`AmpUrl` in `hycon.json`), the restart goes through AMP, which starts the server again. Without AMP the server
  stops, and your host has to start it again by itself.
- **Restart soon**: a one-off restart in a few minutes, with the same countdown. **Cancel** takes it back.
- **Announcements**: lines sent to every player's chat, each on its own timer (every 30 minutes, every hour, ...).

Restarts are refused while `BlockedCommands` blocks `stop` or `restart`.

### Security (OWNER)
A checklist of how safely the panel is set up, worst first, each with what to change: the default password still in
use, the panel open to the network without HTTPS, a proxy whose visitor addresses are ignored, admins and owners
without two-factor, no blocked commands, the ban sharing port being open, and a newer Hycon being out. Problems
also show in the needs-attention line.

### Presets (OWNER)
Ready-made punishments that get harsher for repeat offenders, one offence per line:

```
Spam: warn, mute 30m, mute 1d, ban 1d
Hacking: ban 30d, ban permanent
```

Steps are `warn`, `kick`, `mute <time>` or `ban <time>`, with times like `30m`, `12h`, `7d`, `2w` or `permanent`.
A punishment from a preset starts its reason with the preset's name (`[Spam] ...`), which is how Hycon counts
earlier offences: a player's next step is the number of times they were already punished for it. Past the last
step, they get the last step again. A new server starts with six presets to change as you like.

### Your account
Click your name at the bottom of the sidebar for **My account**:
- **Change password**.
- **Two-factor login**: **Turn on two-factor** and scan the QR code with an authenticator app on your phone (Google
  Authenticator, Microsoft Authenticator, Authy, 1Password and others). Can't scan it? Type the setup key under it
  instead. Then type the 6-digit code the app shows. Hycon draws the QR code itself, so the key never goes to an
  outside QR service. From then on, logging in asks for the code after your password. A browser where you ticked **Remember me** keeps logging
  in by itself. Turning two-factor on logs every other browser out. Lost your phone? An OWNER clicks
  **Remove two-factor** on the Accounts tab, or `/hycon resetpassword <name>` in the console resets both.

**Log out** ends the session. Sessions also end by themselves after 30 idle minutes or 12 hours, whichever comes
first (both can be changed in `hycon.json`).

## 4. Staff accounts

Each staff member should have their own account, so the audit log shows who did what.

| Level     | Can                                                                         |
|-----------|-----------------------------------------------------------------------------|
| VIEWER    | See players, bans and dashboards                                            |
| MODERATOR | Also warn, mute, kick, ban, unban, and watch the live console               |
| ADMIN     | Also run console commands, set the restart schedule, read the audit log     |
| OWNER     | Also manage accounts, presets and security, and download log files          |

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
Most changes apply within 5 seconds of saving the file, no restart needed (the server log says when it read them).
`BindAddress`, `Port`, the session and login limits, `LogLines`, `SharePort`, `ShareBindAddress` and `GeoIp` need a
**server restart**.

| Option               | Default       | What it does                                                                                      |
|----------------------|---------------|---------------------------------------------------------------------------------------------------|
| `Enabled`            | `true`        | `false` turns the panel off completely.                                                           |
| `BindAddress`        | `"127.0.0.1"` | Network address to listen on. `127.0.0.1` = this machine only. See section 6 before changing it. |
| `Port`               | `8096`        | Port of the panel. Change it if 8096 is taken.                                                    |
| `AllowedHosts`       | `[]`          | Extra host names the panel may be opened under, e.g. `["admin.example.com"]`.                     |
| `SecureCookie`       | `false`       | Set `true` when the panel is only reached over HTTPS.                                             |
| `TrustProxy`         | `false`       | Set `true` only behind your own reverse proxy, so real client addresses are logged and limited.   |
| `TrustedProxies`     | `[]`          | Proxy addresses believed besides this machine and private networks (a VPS in front).             |
| `SessionIdleMinutes` | `30`          | Log out after this long without activity.                                                         |
| `SessionMaxHours`    | `12`          | Log out at the latest this long after logging in.                                                 |
| `LoginAttempts`      | `5`           | Failed logins allowed per address and per account name...                                         |
| `LoginWindowMinutes` | `10`          | ...within this many minutes. After that, logins wait until the window has passed.                 |
| `BlockedCommands`    | `[]`          | Commands the panel refuses even for ADMIN, e.g. `["stop", "op"]`. Aliases are blocked too.        |
| `LogLines`           | `2000`        | How many recent log lines the Console tab keeps.                                                  |
| `GeoIp`              | `true`        | Look up players' rough location for the globe (downloads the DB-IP database once a month).       |
| `ServerLocation`     | `""`          | The server's `"lat,lon"`, e.g. `"50.11,8.68"`, so the globe draws lines to it and shows distances. |
| `Profiler`           | `true`        | The always-on profiler (Performance tab, `/lag`, lag alerts). Needs a restart.                    |
| `LagSpikeMs`         | `100`         | A tick longer than this (ms) is saved as a lag spike.                                             |
| `LagAlertPercent`    | `30`          | Alert staff when one plugin uses this share of the world threads for 3 minutes. `0` = no alerts.  |
| `LagWebhook`         | `""`          | A Discord webhook URL for the same alerts.                                                        |
| `AlertSpikeMs`       | `1000`        | Alert when one tick or scheduler task freezes longer than this (ms). `0` = off.                   |
| `AlertSpikesPerMinute` | `10`        | Alert when a minute has this many lag spikes. `0` = off.                                          |
| `AlertMemoryPercent` | `90`          | Alert when memory in use stays over this share of the maximum for 3 minutes. `0` = off.           |
| `AlertHealthBelow`   | `60`          | Alert when Server health (Performance tab) stays under this for 10 minutes. `0` = off.            |
| `WeeklyReport`       | `true`        | Every Monday 12:00 UTC, post a weekly summary (health, spikes, peak players, memory, busiest plugin) to `LagWebhook`. |

## 6. Opening the panel from another computer

The panel can ban players and run commands, so think of it like your server's console password.

Renting your server from a host? See [WEB-PANEL.md](WEB-PANEL.md) for putting the panel on your own
domain without access to the machine.

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

### Game edition and staff watch (on profiles)
Each profile shows the player's **game edition** as a badge next to their name: green for Standard,
orange for Supporter, purple for Cursebreaker. Hycon reads it from the player's login token when they
join, so there's no badge until their first join after this update.

If a joining player has an entitlement not listed in `staff-watch.json` under `Editions`, or is listed
under `WatchPlayers` (`"uuid": "note"`), staff with `hycon.reports.notify` get a chat line, and it goes
in the audit log and the server log. The hope is that a Hytale staff account carries an entitlement
normal players don't. When an alert turns out to be a new edition, add it to `Editions` (lowest edition
first) and restart. The token is read before Hytale checks it, so this only informs staff: never give
anything based on it.

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
| `chat.log`        | Every chat line (time, player, text), kept 14 days. Keep it private.                          |
| `kills.log`       | Player-versus-player kills with the killer's ping, kept 14 days.                              |
| `ping/`           | One small file per player with their ping every minute, kept 14 days.                          |
| `reports.json`    | Player reports and what staff did with them.                                                  |
| `mutes.json`      | Mutes in effect: who, by whom, why and until when.                                              |
| `presets.json`    | Punishment presets (edit them on the Presets tab).                                              |
| `schedule.json`   | Daily restart times, warnings and announcements (edit them on the Schedule tab).               |
| `ties.json`       | Same-connection (hashed) and co-join ties for the connection map. Keep it private.            |
| `activity/`       | Per player: blocks broken, placed and used, things crafted, zones found.                     |
| `positions/`      | Per player: where they were each minute, kept 14 days.                                        |
| `heat.json`       | Server-wide minutes per 16-block square, for the Analytics heatmap.                           |
| `staff-watch.json` | Known editions and watched players for the staff watch (yours to edit).                        |
| `editions.json`   | Each player's entitlements from their last join.                                               |
| `players.json`    | Player directory: UUID, name, first/last seen and last world of everyone who has joined.         |
| `geo.json`        | Each player's looked-up place (city, country, coordinates) for the globe.                       |
| `geoip/`          | The downloaded DB-IP location database.                                                         |
| `profiler-history.jsonl` | Each plugin's share of the world threads per hour with its version, kept 30 days.        |
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

The jar is in `build/libs/`. `gradlew runServer` builds it and starts a
local test server in `run/` with Hycon loaded; the panel's data folder then links to
`src/main/resources` (those runtime files are git-ignored).

For adding your own dashboard tab from another plugin, see `docs/ADDING-A-PANEL.md`.
