# Diagnostics

Two tools for finding out what is wrong without guessing.

## `/hycon debug` (server console)

Writes `debug/dump-<UTC time>.txt` in Hycon's data folder and prints the path. It holds:

- Hycon version, JVM uptime, heap and GC counts, thread count
- worlds and which players are in each
- Hycon's state: panel address, open panel sessions, ban sharing on/off, the IO executor, each timed
  task (`STOPPED (threw?)` means a timer died), any service that never started, `AccountPlugins` and the
  plugins refused so far, and which plugin owns each tab, section and source id
- every thread that is running or blocked, with its stack (parked threads are left out)
- the last 300 WARN/SEVERE/ERROR lines (and the stack trace lines after them) from the end
  (last 2 MB) of the newest file in the server's `logs/` folder

Safe on a live server: it costs nothing until run, works on its own low-priority daemon thread (never
a world thread or Hytale's shared scheduler), only reads counters and references, and can run once
every 30 seconds. The newest 20 dumps are kept. A failure is logged and reported, never thrown.
Console only, like the other `/hycon` commands.

## `tools/diag.py` (offline)

```
py tools/diag.py map    one line per package: classes, commands, web routes, doc
py tools/diag.py lint   rules from the docs a grep can catch (exit 1 on a problem)
py tools/diag.py dump   newest dump from the test server (hyconTestMods in ~/.gradle/gradle.properties)
```

`lint` checks: timers on `HytaleServer.SCHEDULED_EXECUTOR` hand their work to `onIo` or a thread;
the web panel never builds HTML from values; the docs never tell add-ons to bundle Hycon with
`implementation`.
