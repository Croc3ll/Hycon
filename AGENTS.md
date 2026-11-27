# Agents

- Debugging: run `/hycon debug` and `py tools/diag.py` first, see [docs/diagnostics.md](docs/diagnostics.md).
- Other plugins share Hycon's JVM and can call any public method. A new hook only Hycon should call (a setter in
  `HyconApi`, a public method on `HyconPlugin` or on a service with a static `get()`) starts with
  `Callers.requireHycon()`, called directly in that method, never from a helper: it checks a fixed stack depth.
  Anything a plugin registers by id goes through `claim` / `release` in `HyconApi`. The guard doesn't stop
  reflection or a plugin running `/hycon` as the console, so never call it a sandbox. See "What plugins can't do"
  in docs/ADDING-A-PANEL.md.
- Before a release, run `node --check` on every `src/main/resources/web/*.js`. The build doesn't check the panel
  script, and one syntax error (0.2.3: `can't` in single quotes) leaves the panel stuck on "Loading...".
