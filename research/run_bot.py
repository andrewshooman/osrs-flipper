"""Run GE Flipper on your own computer: the paper-trading bot + the app.

    python research/run_bot.py          (Windows: double-click "Start GE Flipper.bat")

- First run downloads the validated model from this repo's `live` branch.
- The bot trades a mock 11M stack against the live GE every 5 minutes.
- The app is served at http://localhost:8765. Its Bot tab shows this bot.
- Data lives in ./bot-data next to the repo (or set OSF_DATA to put it
  elsewhere, e.g. another drive). Close the window to stop; run again to resume.
No real gold is traded and nothing touches the game client.
"""
from __future__ import annotations

import http.server
import io
import json
import os
import shutil
import sys
import threading
import time
import traceback
import urllib.request
import webbrowser
import zipfile
from functools import partial
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = Path(os.environ.get("OSF_DATA") or ROOT / "bot-data")
os.environ["OSF_DATA"] = str(DATA)  # must be set before importing the research modules
sys.path.insert(0, str(ROOT / "research"))

BUNDLE = "https://github.com/andrewshooman/osrs-flipper/archive/refs/heads/live.zip"
PORT = 8765
UA = "osrs-flipper local bot (github.com/andrewshooman/osrs-flipper)"


def setup() -> None:
    """Fetch the model (+ hourly cache) once; start a fresh paper portfolio."""
    if (DATA / "model" / "meta.json").exists():
        return
    print("First run: downloading the model from GitHub ...", flush=True)
    req = urllib.request.Request(BUNDLE, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120) as r:
        z = zipfile.ZipFile(io.BytesIO(r.read()))
    (DATA / "live").mkdir(parents=True, exist_ok=True)
    for name in z.namelist():
        rel = name.split("/", 1)[1] if "/" in name else ""
        if rel.startswith("data/model/") and not name.endswith("/"):
            dest = DATA / "model" / Path(rel).name
        elif rel in ("data/mapping.json", "data/live/hours.parquet"):
            dest = DATA / rel[len("data/"):]
        else:
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        with z.open(name) as src, open(dest, "wb") as out:
            shutil.copyfileobj(src, out)
    print(f"Model saved to {DATA / 'model'}", flush=True)


def refresh_mapping() -> None:
    req = urllib.request.Request("https://prices.runescape.wiki/api/v1/osrs/mapping", headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            (DATA / "mapping.json").write_bytes(r.read())
    except Exception as e:  # keep the bundled copy
        print(f"(could not refresh item list: {e})", flush=True)


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


def serve() -> None:
    handler = partial(QuietHandler, directory=str(ROOT))
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def main() -> None:
    setup()
    refresh_mapping()
    from backtest import Config
    from live import Live

    bot = Live(DATA / "model", Config())
    serve()
    url = f"http://localhost:{PORT}/"
    print(f"\nGE Flipper is running. App: {url}  (Bot tab = this bot)\nLeave this window open; close it to stop.\n", flush=True)
    try:
        webbrowser.open(url)
    except Exception:
        pass
    while True:
        try:
            bot.step()
            st = json.loads((DATA / "live" / "state.json").read_text())
            print(f"{time.strftime('%H:%M')}  profit {st['profit']:+,}  realised {st['realised']:+,}  "
                  f"held {len(st['positions'])}  offers {len(st['offers'])}  closed {len(st['trades'])}", flush=True)
        except Exception:
            print("step failed (will retry in 5 min):", flush=True)
            traceback.print_exc()
        time.sleep(300)


if __name__ == "__main__":
    main()
