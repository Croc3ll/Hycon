# Changelog

Every Hycon release, newest first. Downloads are on the [releases page](https://github.com/Croc3ll/Hycon/releases).
"API" is the plugin API in `hycon-api.jar`, only for developers whose plugins add tabs to Hycon.

## 0.2.3 (2026-11-25)
- **Lock an account**: Accounts → **Lock** keeps an account and its level but stops it logging in (and logs it out)
  until an owner clicks **Unlock**. The last owner that can log in can't be locked.
- **In-game staff in step** (API 17): a plugin can read and set an account's level and lock it, and hears when an
  owner changes one. PrisonCore uses it to keep staff accounts in step with its helper, mod and admin groups (an
  account named after the player); see the guide's "Staff accounts". Owner accounts are never changed by a plugin.

## 0.2.2 (2026-11-21)
- **Account labels**: profiles show **2FA app** (the player uses an authenticator app for two-factor login on their
  Hytale account) and **Chapter 1 boss** (a cosmetic) as small labels beside the edition, instead of
  "Cursebreaker (security.totp)". Staff aren't alerted about them any more. Add your own under `Labels` in
  `staff-watch.json`.
- **Forged logins ignored**: the staff watch only keeps a player's entitlements, and only alerts, once Hytale has
  accepted their login. A bot logging in with a fake token no longer shows up as a player with no edition.

## 0.2.1 (2026-11-21)
- **Two-factor QR code**: setting up two-factor login under My account now shows the QR code 0.2.0's notes promised
  (its jar was built just before it). Scan it with Google Authenticator or any authenticator app. It's drawn by your
  own server, so the key never goes to an outside site. The setup key and Copy key stay below it.

## 0.2.0 (2026-11-20)
A new look and a big moderation update. Updating from 0.1.48 or older? Read **Action needed** under 0.1.49.

- **Sidebar**: the tabs moved from the two-row top bar into a sidebar, grouped under Moderation, Server, Plugins and
  Admin, with the open count on Reports and Bugs. Phones keep the menu button, now with the same groups.
- **Players table**: session length, total playtime, live ping and a risk pill for everyone online. The UUID moved into
  the name's tooltip and a **Copy UUID** button. Row buttons show when you point at the row. The All players list
  shows playtime and "first seen 41 days ago".
- **Needs attention**: one line above every page with open reports and bugs, a lagging server, memory almost full
  and (owners) security problems. Click an item to go there.
- **Mute**: muted players can't chat until it ends, and are told why and for how long. Mutes are on the Bans tab,
  on profiles and in the risk score.
- **Punish dialog**: one dialog for every punishment: a preset, or a plain warn, mute, kick or ban.
- **Punishment presets** (Presets tab, owners): per offence, steps that get harsher for repeat offenders,
  e.g. "Spam: warn, mute 30m, mute 1d, ban 1d". Hycon picks each player's next step. Six presets to start with.
- **Two-factor login**: turn it on under My account (click your name) with any authenticator app. Scan the QR code
  (Google Authenticator: tap +, then Scan a QR code) or type the setup key. Owners can remove it for someone who lost
  their phone, and the console's `/hycon resetpassword` resets it too.
- **Security checklist** (Security tab, owners): what to fix in your setup, worst first, in plain words.
- **Schedule** (admins): daily restarts with countdown warnings in chat (through AMP when it's set up), a one-off
  restart, and announcements that repeat on their own timer.
- **My account**: change your own password without asking an owner.
- Profiles: "1 warning" instead of "1 warns", "40 days ago" with the full date on hover, and the ping card shows the
  four numbers that matter with the rest behind **More**.
- API 16: plugins can give their tab an icon (`HyconPanel.icon()`).

## 0.1.51 (2026-11-20)
Updating from 0.1.48 or older? Read **Action needed** under 0.1.49.

- Only OWNER accounts can download log files (last hour, today's log). Whole log files hold every player's IP
  address. Moderators still watch the live console.

## 0.1.50 (2026-11-20)
- Nobody can lock you out of your own account any more. Before, five wrong passwords for an account name, from
  anywhere, locked that account for everyone. Now an address that has logged into the account before (in the last
  30 days, since the last restart) can still log in; its own failed tries are still limited.

## 0.1.49 (2026-11-20)
Security fixes. Updating is recommended.

**Action needed** if a proxy reaches your panel from a public IP (for example a VPS in front of your game server):
add that IP to `TrustedProxies` in `hycon.json`, e.g. `"TrustedProxies": ["203.0.113.7"]`. Otherwise every visitor
shares the proxy's address for the login limit. If it's missing, the server log names the address to add.
Cloudflare Tunnel and proxies on the same machine or a private network need no change.

- Slow clients can no longer freeze the panel or the ban-share port. A client that sent its request one byte at a
  time held a worker forever, so four of them froze the panel and one froze ban sharing. Requests now have 20
  seconds to arrive and 120 seconds to be answered.
- The ban-share port no longer locks every follower out after a few bad requests from anyone. Bad requests now
  only lock out the address that sent them.
- `BlockedCommands` also blocks a command's aliases (and the command behind a blocked alias). Before, an ADMIN
  could get past a blocked command by typing one of its other names.
- With `TrustProxy` on, the visitor address a proxy sends is only believed from this machine, a private network
  (LAN, VPN, WireGuard, Tailscale) or the new `TrustedProxies` list. Before, anyone reaching an open panel port
  could fake their address and get around the per-address login limit. A proxy on a public IP (Option B in
  `docs/WEB-PANEL.md`) must now be added to `TrustedProxies`; the server log names the address if it is missing.
- One address can hold at most 20 login-page sessions, so a client asking for sessions in a loop can't push
  everyone else off the login page.
- The release jar no longer carries the developer's test data (test share key, test logs). Only the panel files,
  the default config and the license ship now.
- The GeoIP download stops at 1 GB unpacked, so a broken or hostile file can't fill the disk.
- A bad `status` on the Bugs or Reports list answers "Unknown status" instead of a server error.

## 0.1.48 (2026-11-20)
- Hycon now has a license (`LICENSE`, also inside the jar): free to use on your servers, no reselling or
  redistributing the jar.

## 0.1.47 (2026-11-19)
- The README is translated into every panel language (German, Spanish, French, Italian, Dutch, Polish, Portuguese,
  Russian, Turkish), with a language bar at the top.
- New "On your phone" promo card. Leftover translations for the removed At a glance tab are gone.

## 0.1.46 (2026-11-19)
- Phones: dropdown lists are dark with readable text again (0.1.45 left them white with pale text).

## 0.1.45 (2026-11-19)
- Phones: the header is one row (logo, account, Log out) and the language picker moved into the tab menu, under the tabs.
- Phones: dropdowns open the phone's own picker instead of the desktop-styled list. Dropdowns and text fields in forms
  fill the width instead of being squeezed side by side, and fields are 16px so iPhones don't zoom in when tapped.

## 0.1.44 (2026-11-19)
- Phones: the tabs are behind a menu button that names the open tab. Tapping it shows every tab as a grid of large
  buttons, so none are hidden off the edge of the screen. Picking a tab, tapping the button again or Esc closes it.

## 0.1.43 (2026-11-19)
- A cleaner panel on phones: every table shows each row as a card (label over value, buttons big enough to tap), so
  nothing scrolls sideways. The header is more compact. Phones no longer hide each table's third column (on Bans that was "Time left"); only UUIDs are hidden.

## 0.1.42 (2026-11-19)
- `/bug` with nothing after it shows the usage line instead of filing a report that just says "bug", and reports under
  3 characters are refused. `/report` reads its text the same way.
- The At a glance tab is gone.

## 0.1.41 (2026-11-19)
- Translations for the new Performance, At a glance and alert wording in every panel language.

## 0.1.40 (2026-11-19)
- New **At a glance** tab for staff on a phone: health, players online, open reports and bugs, who is where.
- Performance: Server health charts (chosen range and the last 7 days), and each lag spike lists the players in that world.
- Alerts when Server health stays under `AlertHealthBelow` (60) for 10 minutes, and a **weekly report** to the Discord
  webhook every Monday (`WeeklyReport`).

## 0.1.39 (2026-11-19)
- Profiler: a paused server (machine asleep, VM frozen) is no longer reported as hours-long lag spikes with alerts.

## 0.1.38 (2026-11-19)
- Performance: Hytale's networking (a packet nudging the network thread) no longer counts as waiting on files, so it
  stops costing health points. The Scheduler card says whether "busy" or "late" is the problem. The summary no longer
  names a plugin at 0%.

## 0.1.37 (2026-11-19)
- **Performance** tab: a Server health score (0 to 100) at the top, with what cost it points.

## 0.1.36 (2026-11-19)
- **Performance** tab: lag spikes now name the ECS system or scheduler task behind them and whether a chunk
  load, world generation or save was running. New tables for late scheduler tasks (and the task that held
  them up), the slowest scheduler tasks, a per-plugin drill-down, world-thread time per world, network traffic
  per player, and which method waits on files or a database on a game thread. History graphs of spikes,
  memory, players, and each world's chunks, entities and NPCs. Memory shows the longest GC pause.
- Configurable alerts: new settings `AlertSpikeMs`, `AlertSpikesPerMinute`, `AlertMemoryPercent`.

## 0.1.35 (2026-11-18)
- New **Performance** tab and `/lag`: an always-on profiler that shows which plugins use the most server
  time, each lag spike and its cause, the slowest ECS systems, blocking file or database work on game
  threads, and each plugin's change between versions. Staff and Discord alerts for a plugin that hogs a
  world or a long freeze. New settings `Profiler`, `LagSpikeMs`, `LagAlertPercent`, `LagWebhook`.

## 0.1.33 (2026-11-16)
- Much more of the panel is translated: the AntiCheat, Prison, Arena, Economy Guard, Permissions and Referral tabs,
  sentences with numbers or names in them, server messages, and confirm boxes.

## 0.1.32 (2026-11-16)
- Fixed: in a translated panel the gold pill behind the open tab sat off to one side.

## 0.1.31 (2026-11-16)
- Fixed: switching from one translation to another sometimes kept the old language until a refresh.

## 0.1.30 (2026-11-16)
- Nicer language picker: a rounded pill with a gold globe, left of your account name.

## 0.1.29 (2026-11-16)
- A globe icon on the language picker, so it is easy to find in any language.

## 0.1.28 (2026-11-16)
- The panel speaks 10 languages: English, Spanish, German, French, Portuguese, Polish, Russian, Italian, Dutch and Turkish.
  Pick one from the dropdown at the top right; it starts in your browser's language.

## 0.1.27 (2026-11-15)
- Profiles and Analytics: sections below the top card are tabs, one at a time.
- Each profile shows the player's game edition as a badge, and staff get an alert for unusual editions.
- Risk scores fade over time and staff can clear them.
- Safer first login: the default password only works from a browser on the server machine.
- Darker, cleaner dropdowns in the panel.

## 0.1.26 (2026-11-12)
- Faster panel on slow connections: pages are compressed, so replays and the 3D view load much quicker.
- Saves and samples run on Hycon's own thread, so they never hold up the server.

## 0.1.25 (2026-11-12)
- Fixed: the panel slowed down in long sessions. Replays now free their 3D view when you leave them.

## 0.1.24 (2026-11-12)
- 3D replays load their blocks bit by bit around the watched player, so big replays open fast. (API 14)

## 0.1.23 (2026-11-12)
- Plugins can see a player's linked accounts (same connection). AntiCheat uses it to catch banned players' alts. (API 13)

## 0.1.20 to 0.1.22 (2026-11-12)
- See-through walls in 3D replays: a soft round hole opens through walls and roofs between the camera and the player, so you can watch inside houses.

## 0.1.19 (2026-11-12)
- **Chat tab** (moderators and up): everything players said, kept 14 days. Search by word or name and click a name to open the profile.
- **Chat history** on every player profile.

## 0.1.18 (2026-11-12)
- 3D replays show the real blocks, coloured by kind, so houses, trees and walls look as they do in game. (API 12)

## 0.1.16 and 0.1.17 (2026-11-12)
- Fixed: 3D replays could stay flat, and 0.1.16 replays showed an error.
- **Reset camera** button, and zoom stops at 60 blocks.

## 0.1.15 (2026-11-12)
- Plugins can ask admins a question as a highlighted card or a popup. (API 11)

## 0.1.14 (2026-11-12)
- 3D replays get the real landscape: hills, cliffs and holes. (API 10)

## 0.1.13 (2026-11-12)
- Replays show shots (bullets, arrows) as dashed lines, red when flagged. (API 9)

## 0.1.12 (2026-11-12)
- Replays: players who get hit flash red.

## 0.1.11 (2026-11-12)
- **3D replays**: blocky players walk over the world, with name tags and where they look. Hits are arrows with their distance. Drag to turn, scroll to zoom, or switch to the map view.

## 0.1.10 (2026-11-12)
- Clearer replays: the world map underneath, a camera that follows the player, zoom, distance labels and a height strip you can click to jump. (API 8)

## 0.1.8 and 0.1.9 (2026-11-12)
- **Replays**: watch people move over time from above, with hits and where they look. Play, pause, slow motion and a time slider. Used by AntiCheat for fights saved at each alert. (API 7)
- Fixed: replays didn't draw in 0.1.8.

## 0.1.7 (2026-11-11)
- New gold diamond logo, in the panel and on the login page.
- Screenshots of every feature on GitHub.
- Fixed: Analytics could fail to open on a fast server, and a few layout glitches.

## 0.1.6 (2026-11-10)
- Coloured console: time, level and source, with errors in red.
- New player profile header: avatar, online status, first and last seen, world.
- The panel always loads fresh after an update.
- Layout fixes for phones, wide tables and big numbers.

## 0.1.5 (2026-11-10)
- Numbers count up, cards slide in, charts draw themselves, and the tab highlight glides. Off when your system is set to reduce motion.

## 0.1.4 (2026-11-10)
- **New modern look**: glass header with tab pills and icons, rounder cards, a cleaner font, and nicer buttons, tables and pop-ups.
- New login page, softer charts, and a swipeable tab bar on phones.

## 0.1.3 (2026-11-02)
- Update notices: Hycon tells admins when a new version is out, in the console and in chat when they join.

## 0.1.2 (2026-11-02)
- Player charts show whole numbers (most players online), and "Most players by hour" replaces the hourly average.

## 0.1.1 (2026-10-30)
- **Player globe** on Analytics, with dots coloured by ping and a table by country.
- **Location card** on every profile, with the distance to the server and a VPN warning.
- A profile's back link returns to the tab you came from.

## 0.1.0 (2026-10-27)
- First release: the web admin panel with player profiles, records, risk score, reports and alts, bans and analytics.
