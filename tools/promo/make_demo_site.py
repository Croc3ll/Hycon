"""Builds the live demo: the real panel with made-up players (demo.js), as plain files any static host
serves (GitHub Pages, Cloudflare Pages, a web server). Nothing in it talks to a game server, and every
button that would change something only says so.

    py tools/promo/make_demo_site.py      # writes build/demo-site/

Live at https://demo.crocellsworkshop.dev: the VPS (root@107.173.85.141) serves the files from
/var/www/demo.crocellsworkshop.dev through Apache site demo.crocellsworkshop.dev.conf, behind Cloudflare.
To update it, rebuild and copy build/demo-site/ over that folder; Cloudflare caches .js and .css, so purge
them there (or wait) when they change.
"""
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WEB = ROOT / "src" / "main" / "resources" / "web"
HERE = Path(__file__).resolve().parent
OUT = ROOT / "build" / "demo-site"
REPO = "https://github.com/Croc3ll/Hycon"

RIBBON = (f'<a class="demo-ribbon" href="{REPO}" target="_blank" rel="noopener">'
          'Demo with made-up players: nothing here changes anything. <b>Get Hycon</b></a>')
META = ('<meta name="description" content="Try Hycon, the web admin panel for Hytale servers: moderation, player profiles, '
        'analytics and performance, with made-up demo data.">\n<link rel="stylesheet" href="demo-site.css">\n')


def main() -> None:
    shutil.rmtree(OUT, ignore_errors=True)
    shutil.copytree(WEB, OUT)
    shutil.copy(HERE / "demo.js", OUT / "demo.js")
    shutil.copy(HERE / "demo-site.css", OUT / "demo-site.css")
    page = (WEB / "index.html").read_text(encoding="utf-8")
    # demo.js has to swap fetch before app.js (deferred) asks for anything.
    page = page.replace('<script src="app.js" defer></script>', '<script src="demo.js"></script>\n<script src="app.js" defer></script>')
    page = page.replace("</head>", META + "</head>", 1)
    page = page.replace("<body>", "<body>\n" + RIBBON, 1)
    (OUT / "index.html").write_text(page, encoding="utf-8")
    (OUT / ".nojekyll").write_text("", encoding="utf-8")  # GitHub Pages: serve the files as they are
    print(f"Demo site in {OUT}")


if __name__ == "__main__":
    main()
