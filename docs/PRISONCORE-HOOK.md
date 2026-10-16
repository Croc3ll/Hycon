# Showing PrisonCore analytics in Hycon (plan)

Hycon has a small hook API (`com.hycon.api`): a plugin registers a `HyconPanel`, and Hycon draws it
as a tab using stat tiles, line charts and tables from `PanelData`. The plugin sends no HTML.

## Change in PrisonCore

1. **Optional dependency.** In `gradle.properties`:
   `manifest_opt_dependencies = TheNewEconomy:VaultUnlocked=*,Hycon:Hycon=*`
2. **Compile against Hycon without bundling it.** In `build.gradle.kts`:
   `compileOnly(files("../../Hycon/build/libs/Hycon-0.1.0.jar"))`
   (or publish Hycon to mavenLocal later). Never `implementation`: the classes must come from the
   Hycon plugin at runtime.
3. **One bridge class, `com.prisoncore.analytics.HyconBridge`**, the only file that imports
   `com.hycon.api`, so PrisonCore still loads without Hycon (same pattern as `VaultUnlockedSupport`):
   - `PrisonCorePanel implements HyconPanel`: id `prisoncore`, title `Prison`, `minLevel()` = `HyconApi.VIEWER`.
   - `render(out)` reads what `DashboardData` already computes (no new queries):
     - `summary()` numbers → `out.stat(...)` (online, money in circulation, money earned/spent today, blocks mined, ...).
     - `history(out.rangeMillis())` samples → `out.series("Economy", "money").line("In circulation").point(t, v)...`
       and a second series for activity (players, blocks/min).
     - top players → `out.table("Richest", "Player", "Money")`.
   - To avoid parsing JSON, give `DashboardData` small typed accessors next to its JSON writers.
4. **Register only when Hycon is there.** In `PrisonCorePlugin.start()` after `analytics.start(...)`:
   ```java
   // (check the exact plugin-lookup API in the decompiled server first)
   if (PluginManager.get().getPlugin(new PluginIdentifier("Hycon", "Hycon")) != null) {
       HyconBridge.register(analytics);   // HyconApi.register(new PrisonCorePanel(...))
   }
   ```
   and `HyconBridge.unregister()` in `shutdown()`. Wrap in `catch (LinkageError e)` so a Hycon
   version mismatch only logs a warning.
5. PrisonCore's own dashboard (`dashboard.json`) keeps working; server owners can turn it off
   once they use Hycon.

## Test

Both jars in one dev server: open Hycon, the "Prison" tab appears for every account level,
charts follow the 1h…30d picker, and removing Hycon still lets PrisonCore start.
