# Adding your own tab to Hycon

Any Hytale plugin can add a dashboard tab to the Hycon panel: numbers, charts over time, bar
lists and tables. Hycon draws everything; your plugin only supplies the values. Your plugin keeps
working on servers without Hycon.

The example below is a small "Shop" plugin that shows sales.

## 1. Compile against Hycon, but don't bundle it

Build Hycon (`gradlew build`) or download its jar, then in your plugin's `build.gradle.kts`:

```kotlin
dependencies {
    // Only for compiling. At runtime the classes come from the Hycon plugin itself.
    compileOnly(files("libs/Hycon-0.1.0.jar"))
}
```

Building Hycon also makes `build/api/hycon-api.jar` (just the API). To keep your copy current, add
`hyconApiJar=<path to your libs/hycon-api.jar>` to your own `~/.gradle/gradle.properties`; every
Hycon build then refreshes it. An old copy that ends up on a server's class path loads before Hycon's
own classes and breaks the panel with `NoSuchMethodError`.

Never use `implementation` here: a second copy of Hycon's classes inside your jar would not be
the one Hycon reads, so your tab would never appear.

## 2. Make Hycon an optional dependency

In your `manifest.json` (or the `manifest_opt_dependencies` line of the Hytale Gradle template):

```json
"OptionalDependencies": { "Hycon:Hycon": "*" }
```

That makes Hycon load first when it is installed, and changes nothing when it isn't.

## 3. Write the panel

Put every Hycon import in this one class.

```java
package com.example.shop.hycon;

import com.hycon.api.HyconApi;
import com.hycon.api.HyconPanel;
import com.hycon.api.PanelData;
import com.example.shop.ShopStats;

/** The "Shop" tab in Hycon. The only class that touches Hycon. */
public final class ShopPanel implements HyconPanel {

    private final ShopStats stats;

    public ShopPanel(ShopStats stats) {
        this.stats = stats;
    }

    @Override public String id() { return "shop"; }        // letters, digits, - _ .
    @Override public String title() { return "Shop"; }     // tab name
    @Override public int minLevel() { return HyconApi.VIEWER; } // VIEWER, MODERATOR, ADMIN or OWNER

    /** Called on a Hycon web thread each time the tab refreshes (about every 15 s). Keep it quick. */
    @Override
    public void render(PanelData out) {
        // The 24 h / 7 d / 30 d / All time buttons. For All time, out.allTime() is true and
        // rangeMillis() reaches back to 1970, so this "since" includes everything.
        long since = System.currentTimeMillis() - out.rangeMillis();

        // Number tiles across the top.
        out.stat("Sales", String.valueOf(stats.salesSince(since)))
           .stat("Revenue", stats.revenueSince(since) + " coins", "after tax");

        // A chart over time. area() fills under the first line; gap() draws a red band.
        PanelData.Series s = out.series("Revenue", "coins").area().subtitle("per hour");
        s.line("Revenue");
        for (ShopStats.Point p : stats.hourly(since)) {
            s.point(p.time(), p.revenue());
        }

        // Bars and columns sit side by side.
        PanelData.Bars best = out.bars("Best sellers");
        for (ShopStats.Item i : stats.topItems(since, 5)) {
            best.bar(i.name(), i.sold() + " sold", i.revenue(), i.revenue() + " coins");
        }
        PanelData.Columns hours = out.columns("Sales by hour");
        for (int h = 0; h < 24; h++) {
            hours.column(String.format("%02d", h), stats.salesInHour(h, since));
        }

        // A table. PanelData.time(ms) shows as a date in the viewer's own time zone.
        PanelData.Table t = out.table("Recent sales", "Player", "Item", "Price", "When");
        for (ShopStats.Sale x : stats.recent(20)) {
            t.row(x.player(), x.item(), x.price(), PanelData.time(x.time()));
        }
    }

    /** Called from the plugin's start(); returns false when Hycon isn't installed. */
    public static boolean register(ShopStats stats) {
        HyconApi.register(new ShopPanel(stats));
        return true;
    }

    public static void unregister() {
        HyconApi.unregister("shop");
    }
}
```

## 4. Register it only when Hycon is there

In your plugin's main class, outside the Hycon-only class:

```java
private boolean hycon;

@Override
protected void start() {
    // ... your own start-up ...
    try {
        hycon = ShopPanel.register(stats);
    } catch (LinkageError e) {
        // Hycon isn't installed (or is a version without this API): run without the tab.
        hycon = false;
    }
}

@Override
protected void shutdown() {
    if (hycon) {
        ShopPanel.unregister();
    }
}
```

The JVM only loads `ShopPanel` the first time it's used, so when Hycon is missing the
`LinkageError` is thrown right there, caught, and nothing else in your plugin notices.

## What you can put on a tab

| Call                                  | Shows                                                                 |
|---------------------------------------|-----------------------------------------------------------------------|
| `stat(label, value[, hint])`          | A number tile; tiles in a row share one line.                         |
| `series(title, unit)`                 | Chart over time with a Chart/Table switch. `.line(name)` starts a new line, `.point(ms, value)` adds to it, `.area()` fills under the first line, `.gap(from, to)` marks an outage, `.subtitle(text)` adds grey text. |
| `bars(title).bar(label, note, value, text)` | Horizontal bars; length relative to the largest value.          |
| `columns(title).column(label, value)` | A small column chart (e.g. per hour).                                  |
| `table(title, columns...).row(cells...)` | A table; use `PanelData.time(ms)` for dates.                        |
| `pie(title).unit(u).slice(label, value)` | A donut chart with a legend (shares in %). Sits side by side with bars and columns. |
| `prompt(id, title, popup).text(line).button(label, value, primary)` | A question pinned at the top of the tab as a highlighted card (API 11); with `popup` it also opens as a popup until answered ("Not now" hides it for the browser session). Buttons call `action(id, {choice: value})`. Lines starting with "- " are bullets. |
| `heatmap(title, cellSize, unit).cell(cx, cz, value).pin(label, x, z)` | A top-down world map, coloured by value per square. |
| `replay(title).world(name).actor(name, main).at(time, x, y, z, yaw)`, `.event(time, from, to, bad, label)`, `.shot(...)` (API 9, dashed), `.terrain(x0, z0, w, d, topY[])` (API 10: the ground as blocks in 3D, up to 96 x 96), `.blocks(xyzc[], colours[])` (API 12: the real visible blocks, each x, y, z and a colour index, up to 25,000; drawn instead of the columns), `.blockSource(sourceId, key)` (API 14: the blocks come a tile at a time from a block source, see below) | An animated replay (API 7; `world` and its map from API 8): people moving over time, seen from above over the world map with the camera on the `main` person, their height below, arrowheads for where they looked, and events such as hits as arrows with their distance (red when `bad`). Play, pause, speed and a time slider. Up to 8 people, 1,500 samples each, 300 events. |
| `note(text)`                          | A line of text. Notes in a row share one boxed panel; a short line with no full stop (e.g. "Summary") becomes the box's heading. |

All text is shown as plain text, never as HTML, so player names and item names are safe to pass
straight through. Each tab is capped (100 blocks, 5000 chart points, 1000 table rows).

## Rules of thumb

- `render` runs on a web thread, not a game thread. Read from data you already keep up to date
  (counters, caches, your own database), never from the world or entities directly.
- Keep it fast: it runs on every refresh of every open browser tab.
- Exceptions in `render` show as an error on your tab; they don't affect Hycon or the server.

## A section on every player's profile (API version 3)

Implement `HyconProfileSection` instead of `HyconPanel`: `render(UUID player, String name, PanelData out)`
fills in that one player's numbers and charts. Register it with
`HyconApi.registerProfileSection(...)` and remove it with `HyconApi.unregisterProfileSection(id)`.
It appears under the player's profile with the same range picker.


## Block sources for 3D replays (API 14)

A replay with many blocks makes a big page, rebuilt on every refresh. Instead, register a `HyconBlocks` with
`HyconApi.registerBlockSource(id, source)` and call `.blockSource(id, key)` on the replay. The browser then asks
`/api/blocks` for 16 x 16 tiles around the player it follows, in a height band around them (8 below, 24 above), and
drops tiles once they are out of reach. So the ground under a player only loads when they go down there.
`tile(key, x0, z0, size, yMin, yMax, out)` runs on a web thread: read thread-safe data only. Unregister on shutdown.

## Linked accounts (API 13)

`HyconApi.linkedAccounts(uuid)` gives the other accounts that have played from one of this player's connections (the profile's "Shares a connection"): likely alts. Empty when there are none. Hycon - AntiCheat uses it to alert staff when a banned player's alt joins, and (if the owner turns it on) to ban alts too.
