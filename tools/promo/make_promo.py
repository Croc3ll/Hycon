"""Promo art for the Hycon panel.

Serves the panel's web files with demo.js (a made-up busy server, no real players), captures
pages with headless Edge, then lays them out on branded cards (cards.html) and renders those
to docs/promo/*.png.

    py tools/promo/make_promo.py          # shots + cards
    py tools/promo/make_promo.py --serve  # just serve the demo panel to look at it
"""
import functools
import http.server
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WEB = ROOT / "src" / "main" / "resources" / "web"
HERE = Path(__file__).resolve().parent
OUT = ROOT / "docs" / "promo"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
PORT = 8197
SETTLE_SECONDS = 6  # how long a page draws before its screenshot

# name, panel address, element to scroll to (or None), window size
SHOTS = [
    ("analytics", "#panel:analytics", None, (1440, 900)),
    ("globe", "#panel:analytics", ".globegrid", (1440, 900)),
    ("players", "#players", None, (1440, 900)),
    ("profile", "#profile:{hero}", None, (1440, 900)),
    ("console", "#console", None, (1440, 900)),
]


def build_site(site: Path) -> None:
    for f in WEB.iterdir():
        shutil.copy(f, site / f.name)
    shutil.copy(HERE / "demo.js", site / "demo.js")
    shutil.copy(HERE / "cards.html", site / "cards.html")
    page = (WEB / "index.html").read_text(encoding="utf-8")
    # demo.js has to swap fetch before app.js (deferred) asks for anything.
    page = page.replace('<script src="app.js" defer></script>', '<script src="demo.js"></script>\n<script src="app.js" defer></script>')
    # A slow image holds back the load event, and Edge's --screenshot fires on load: the
    # panel gets a few seconds to fetch the demo data and draw first.
    page = page.replace("</body>", '<img src="/__wait" alt="" hidden>\n</body>')
    (site / "index.html").write_text(page, encoding="utf-8")


class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/__wait"):
            time.sleep(SETTLE_SECONDS)
            self.send_response(204)
            self.end_headers()
            return
        super().do_GET()

    def log_message(self, *args):
        pass


def serve(site: Path) -> http.server.ThreadingHTTPServer:
    handler = functools.partial(Handler, directory=str(site))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def capture(url: str, out: Path, size: tuple[int, int]) -> None:
    profile = tempfile.mkdtemp(prefix="hycon-promo-")
    out.unlink(missing_ok=True)
    subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--user-data-dir={profile}",
                    "--force-device-scale-factor=2", f"--window-size={size[0]},{size[1]}",
                    "--timeout=30000", f"--screenshot={out}", url],
                   check=True, capture_output=True, timeout=120)
    # msedge.exe hands the work to a browser process and returns at once: wait for the file.
    deadline = time.time() + 90
    while not (out.exists() and out.stat().st_size > 0):
        if time.time() > deadline:
            raise TimeoutError(f"Edge never wrote {out}")
        time.sleep(0.5)
    time.sleep(1)
    shutil.rmtree(profile, ignore_errors=True)


def main() -> None:
    site = Path(tempfile.mkdtemp(prefix="hycon-site-"))
    build_site(site)
    server = serve(site)
    base = f"http://127.0.0.1:{PORT}"
    if "--serve" in sys.argv:
        print(f"Demo panel on {base}/index.html  (Ctrl+C to stop)")
        threading.Event().wait()
    shots = site / "shots"
    shots.mkdir()
    hero = "%08x-4c1a-4b7e-9d2f-%012x" % ((1 * 2654435761) & 0xFFFFFFFF, 1 * 40503 * 977)
    for name, page, target, size in SHOTS:
        query = f"?shot={target}" if target else ""
        capture(f"{base}/index.html{query}{page.format(hero=hero)}", shots / f"{name}.png", size)
        print("shot", name)
    OUT.mkdir(parents=True, exist_ok=True)
    for card in ["banner", "analytics", "globe", "profile", "console", "players"]:
        capture(f"{base}/cards.html?card={card}", OUT / f"hycon-{card}.png", (1600, 900))
        print("card", card)
    server.shutdown()
    shutil.rmtree(site, ignore_errors=True)


if __name__ == "__main__":
    main()
