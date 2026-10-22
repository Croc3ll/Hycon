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
| `note(text)`                          | A line of text. Notes in a row share one boxed panel; a short line with no full stop (e.g. "Summary") becomes the box's heading. |

All text is shown as plain text, never as HTML, so player names and item names are safe to pass
straight through. Each tab is capped (100 blocks, 5000 chart points, 1000 table rows).

## Rules of thumb

- `render` runs on a web thread, not a game thread. Read from data you already keep up to date
  (counters, caches, your own database), never from the world or entities directly.
- Keep it fast: it runs on every refresh of every open browser tab.
- Exceptions in `render` show as an error on your tab; they don't affect Hycon or the server.
