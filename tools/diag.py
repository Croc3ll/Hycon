"""Offline diagnostics for Hycon, for coding agents and people. Never touches a running server.

  py tools/diag.py map    one line per package: classes, commands, web routes, its doc
  py tools/diag.py lint   checks the rules in docs/ that a grep can catch
  py tools/diag.py dump   prints the newest /hycon debug file from the test server

Exit code 1 from lint means a rule was broken. See docs/diagnostics.md.
"""
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "src/main/java/com/hycon"
WEB = ROOT / "src/main/resources/web"
DOCS = ROOT / "docs"
GRADLE_PROPS = Path.home() / ".gradle/gradle.properties"

# Packages whose doc is not named after them.
PACKAGE_DOCS = {"api": "ADDING-A-PANEL.md", "web": "WEB-PANEL.md", "": "GUIDE.md"}


def read(path):
    return path.read_text(encoding="utf-8", errors="replace")


def cmd_map():
    groups = {"": [f for f in JAVA.glob("*.java")]}
    for pkg in sorted(p for p in JAVA.iterdir() if p.is_dir()):
        groups[pkg.name] = list(pkg.rglob("*.java"))
    for name, files in groups.items():
        commands = sorted({m for f in files if "Command" in f.name
                           for m in re.findall(r'super\("([a-z0-9_-]+)",', read(f))})
        routes = sum(len(re.findall(r'\.(?:get|post|always)\("/', read(f))) for f in files)
        doc = PACKAGE_DOCS.get(name, "")
        print(f"{name or '(root)':10} {len(files):3} classes"
              + (f"  routes: {routes}" if routes else "")
              + (f"  doc: {doc}" if doc else "")
              + (f"  cmds: {', '.join(commands)}" if commands else ""))


def cmd_lint():
    problems = []
    for f in JAVA.rglob("*.java"):
        rel = f.relative_to(ROOT)
        for n, line in enumerate(read(f).splitlines(), 1):
            # Hytale's scheduler is one thread for the whole server: timers must hand work off (onIo or a thread).
            if "SCHEDULED_EXECUTOR.scheduleAtFixedRate" in line and "onIo(" not in line and "Thread." not in line:
                problems.append(f"{rel}:{n}: timer runs its work on Hytale's shared scheduler (wrap it in onIo)")
    for f in WEB.rglob("*.js"):
        rel = f.relative_to(ROOT)
        for n, line in enumerate(read(f).splitlines(), 1):
            # Text from the server is shown as plain text, never as HTML (docs/ADDING-A-PANEL.md).
            if re.search(r"(innerHTML|outerHTML)\s*\+?=.*(\$\{|\+)", line) or "insertAdjacentHTML" in line:
                problems.append(f"{rel}:{n}: builds HTML from values (use textContent)")
    build = read(ROOT / "build.gradle.kts")
    for doc in DOCS.glob("*.md"):
        for m in re.findall(r'^\s*implementation\("[^"]*hycon', read(doc), re.M | re.I):
            problems.append(f"{doc.name}: tells add-ons to bundle Hycon with implementation(): {m.strip()}")
    for p in problems:
        print("ERROR", p)
    print(f"{len(problems)} problems.")
    return 1 if problems else 0


def test_mods():
    if GRADLE_PROPS.is_file():
        m = re.search(r"^hyconTestMods=(.+)$", read(GRADLE_PROPS), re.M)
        if m:
            return Path(m.group(1).strip())
    return ROOT / "run/mods"


def cmd_dump():
    mods = test_mods()
    dumps = [p for p in mods.rglob("dump-*.txt") if "hycon" in str(p.parent.parent).lower()] if mods.is_dir() else []
    if not dumps:
        print(f"No Hycon dump under {mods}. Run /hycon debug in the server console.")
        return 1
    dump = max(dumps, key=lambda p: p.stat().st_mtime)
    print(dump)
    print(read(dump))
    return 0


def main(argv):
    cmds = {"map": cmd_map, "lint": cmd_lint, "dump": cmd_dump}
    if not argv or argv[0] not in cmds:
        print(__doc__)
        return 2
    return cmds[argv[0]]() or 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
