"""Resumable historical collector for the OSRS Wiki real-time prices API.

Pulls whole-market snapshots (/1h?timestamp=, /5m?timestamp=) and stores one
parquet file per UTC day:

    $OSF_DATA/{step}/YYYY-MM-DD.parquet   columns: ts, id, ah, hv, al, lv
    $OSF_DATA/mapping.json                item metadata (refreshed each run)

  ah/al = avgHighPrice/avgLowPrice (instant-buy / instant-sell averages)
  hv/lv = highPriceVolume/lowPriceVolume

Usage:
    python research/collect.py --step 1h --days 365
    python research/collect.py --step 5m --days 60
Re-running skips days already on disk (today is always refreshed).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

API = "https://prices.runescape.wiki/api/v1/osrs"
UA = "osrs-flipper research collector (github.com/andrewshooman/osrs-flipper)"
STEP_SECONDS = {"5m": 300, "1h": 3600}
DATA = Path(os.environ.get("OSF_DATA", "/home/user/osrs-data"))


def get_json(url: str, retries: int = 5) -> dict:
    delay = 2.0
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            if attempt == retries - 1:
                raise
            print(f"  retry {attempt + 1} after {e}", file=sys.stderr)
            time.sleep(delay)
            delay *= 2
    raise RuntimeError("unreachable")


def snapshot_rows(step: str, ts: int) -> list[tuple]:
    body = get_json(f"{API}/{step}?timestamp={ts}")
    rows = []
    for k, v in (body.get("data") or {}).items():
        rows.append((ts, int(k), v.get("avgHighPrice"), v.get("highPriceVolume") or 0,
                     v.get("avgLowPrice"), v.get("lowPriceVolume") or 0))
    return rows


def write_day(path: Path, rows: list[tuple]) -> None:
    import pandas as pd

    df = pd.DataFrame(rows, columns=["ts", "id", "ah", "hv", "al", "lv"])
    df = df.astype({"ts": "int64", "id": "int32", "ah": "float32", "hv": "int32", "al": "float32", "lv": "int32"})
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    df.to_parquet(tmp, index=False)
    tmp.replace(path)


def collect(step: str, days: int, rate: float) -> None:
    sec = STEP_SECONDS[step]
    DATA.mkdir(parents=True, exist_ok=True)
    (DATA / "mapping.json").write_text(json.dumps(get_json(f"{API}/mapping")))

    now = datetime.now(timezone.utc)
    today = now.date()
    for d in range(days, -1, -1):
        day = today - timedelta(days=d)
        out = DATA / step / f"{day.isoformat()}.parquet"
        if out.exists() and day != today:
            continue
        start = int(datetime(day.year, day.month, day.day, tzinfo=timezone.utc).timestamp())
        end = min(start + 86400, int(now.timestamp()) - sec)  # only completed buckets
        rows: list[tuple] = []
        t0 = time.time()
        for ts in range(start, end, sec):
            rows.extend(snapshot_rows(step, ts))
            time.sleep(max(0.0, 1.0 / rate))
        if rows:
            write_day(out, rows)
        print(f"{step} {day}: {len(rows):,} rows in {time.time() - t0:.0f}s", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--step", choices=STEP_SECONDS, default="1h")
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--rate", type=float, default=1.0, help="max requests per second (be polite)")
    a = ap.parse_args()
    collect(a.step, a.days, a.rate)


if __name__ == "__main__":
    main()
