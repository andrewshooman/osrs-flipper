"""Processing & decanting arbitrage from OSRS Wiki recipe data.

    python research/recipes.py fetch        # download recipes -> research/data/recipes.json
    python research/recipes.py scan         # live margins now (prices API)
    python research/recipes.py history      # how often each margin held over the hourly panel

A recipe is kept when every material and the output trade on the GE. Margin
per craft = output_qty x net sell (after 2% tax) - sum(material_qty x buy).
Two price modes: "patient" (bid instant-sell+1, ask instant-buy-1) and
"instant" (buy at instant-buy, sell at instant-sell). Decanting (combine
doses of a potion into 4-dose, free at Bob Barter) is added as recipes too.
"""
from __future__ import annotations

import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "data" / "recipes.json"
WIKI = "https://oldschool.runescape.wiki/api.php"
PRICES = "https://prices.runescape.wiki/api/v1/osrs"
UA = "osrs-flipper research (github.com/andrewshooman/osrs-flipper)"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def tax(p):
    return np.minimum(np.floor(np.asarray(p, dtype=float) * 0.02), 5e6)


def num(x):
    try:
        return float(str(x).replace(",", ""))
    except ValueError:
        return None


def fetch():
    mapping = get(f"{PRICES}/mapping")
    by_name = {m["name"].lower(): m for m in mapping}
    rows, off = [], 0
    while True:
        q = f"bucket('recipe').select('page_name','production_json').limit(5000).offset({off}).run()"
        d = get(f"{WIKI}?action=bucket&format=json&query={urllib.parse.quote(q)}")
        b = d.get("bucket", [])
        rows += b
        print(f"  fetched {len(rows)} recipes", flush=True)
        if len(b) < 5000:
            break
        off += 5000
        time.sleep(1)

    recipes, seen = [], set()
    for r in rows:
        try:
            p = json.loads(r["production_json"])
        except (TypeError, json.JSONDecodeError):
            continue
        out = p.get("output") or {}
        o = by_name.get(str(out.get("name", "")).lower())
        oq = num(out.get("quantity"))
        mats = []
        for m in p.get("materials") or []:
            it, q = by_name.get(str(m.get("name", "")).lower()), num(m.get("quantity"))
            if not it or not q:
                mats = None
                break
            mats.append({"id": it["id"], "qty": q})
        if not o or not oq or not mats:
            continue
        key = (o["id"], tuple(sorted((m["id"], m["qty"]) for m in mats)))
        if key in seen:
            continue
        seen.add(key)
        recipes.append({
            "name": p.get("name") or out.get("name"), "out": o["id"], "out_qty": oq, "mats": mats,
            "skills": [{"skill": s.get("name"), "level": num(s.get("level"))} for s in p.get("skills") or []],
            "ticks": num(p.get("ticks")), "members": bool(p.get("members")),
            "tools": p.get("tools") or "", "facilities": p.get("facilities") or "",
            # "skill" recipes cost playing time (money makers); "combine" ones are near-instant conversions
            "kind": "skill" if any(num(s.get("level")) for s in p.get("skills") or []) else "combine",
        })

    # Decanting: X(a) doses -> X(4). k potions of a doses make k*a/4 four-dose potions.
    fam: dict[str, dict[int, int]] = {}
    for m in mapping:
        mm = re.match(r"^(.*)\(([1-4])\)$", m["name"])
        if mm:
            fam.setdefault(mm.group(1), {})[int(mm.group(2))] = m["id"]
    for base, doses in fam.items():
        if 4 not in doses:
            continue
        for a in (1, 2, 3):
            if a in doses:
                recipes.append({"name": f"Decant {base}({a}) -> ({4})", "out": doses[4], "out_qty": a,
                                "mats": [{"id": doses[a], "qty": 4}], "skills": [], "ticks": None,
                                "members": False, "tools": "", "facilities": "Bob Barter (decanting)", "kind": "decant"})
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"fetched": int(time.time()), "recipes": recipes}, separators=(",", ":")))
    print(f"kept {len(recipes)} tradeable recipes -> {OUT}")


def margins(recipes, ah, al, idx):
    """ah/al: arrays indexed via idx[item_id]. Returns patient & instant margins per recipe."""
    res = []
    for r in recipes:
        ids = [m["id"] for m in r["mats"]] + [r["out"]]
        if any(i not in idx for i in ids):
            res.append((np.nan, np.nan))
            continue
        o = idx[r["out"]]
        sell_p, sell_i = ah[..., o] - 1, al[..., o]
        cost_p = sum(m["qty"] * (al[..., idx[m["id"]]] + 1) for m in r["mats"])
        cost_i = sum(m["qty"] * ah[..., idx[m["id"]]] for m in r["mats"])
        res.append((r["out_qty"] * (sell_p - tax(sell_p)) - cost_p, r["out_qty"] * (sell_i - tax(sell_i)) - cost_i))
    return res


def scan(top=40):
    recipes = json.loads(OUT.read_text())["recipes"]
    latest = get(f"{PRICES}/latest")["data"]
    vol = get(f"{PRICES}/24h")["data"]
    ids = sorted(int(k) for k in latest)
    idx = {i: n for n, i in enumerate(ids)}
    ah = np.array([latest[str(i)].get("high") or np.nan for i in ids], dtype=float)
    al = np.array([latest[str(i)].get("low") or np.nan for i in ids], dtype=float)
    names = {m["id"]: m["name"] for m in get(f"{PRICES}/mapping")}
    now = time.time()
    fresh = {i for i in ids if now - min(latest[str(i)].get("highTime") or 0, latest[str(i)].get("lowTime") or 0) < 3600}
    dvol = {int(k): (v.get("highPriceVolume") or 0) + (v.get("lowPriceVolume") or 0) for k, v in vol.items()}
    rows = []
    for r, (mp, mi) in zip(recipes, margins(recipes, ah, al, idx)):
        legs = [m["id"] for m in r["mats"]] + [r["out"]]
        # Every leg must have traded in the last hour and trade >= 1,000/day, or the margin is a mirage.
        if not np.isfinite(mp) or any(i not in fresh or dvol.get(i, 0) < 1000 for i in legs):
            continue
        daily = dvol.get(r["out"], 0)
        cost = sum(m["qty"] * al[idx[m["id"]]] for m in r["mats"])
        rows.append((mp / max(cost, 1), mp, mi, daily, r, cost))
    rows.sort(key=lambda x: -x[1] * min(x[3], 10000) / max(x[5], 1))
    for kind in ("decant", "combine", "skill"):
        print(f"\n== {kind} ==  {'recipe':40s} {'patient':>9s} {'instant':>9s} {'ROI':>7s} {'vol/day':>10s}")
        for roi, mp, mi, daily, r, cost in [x for x in rows if x[4]["kind"] == kind and x[1] > 0][:top]:
            print(f"   {r['name'][:50]:50s} {mp:9.0f} {mi:9.0f} {roi * 100:6.1f}% {daily:10.0f}")


def history():
    """Fraction of hours each recipe was profitable (patient & instant) over the panel."""
    from panel import load_panel
    P = load_panel("1h")
    recipes = json.loads(OUT.read_text())["recipes"]
    idx = {int(i): n for n, i in enumerate(P.ids)}
    ah = np.asarray(P.ah, dtype=float)
    al = np.asarray(P.al, dtype=float)
    out = []
    for r, (mp, mi) in zip(recipes, margins(recipes, ah, al, idx)):
        if not isinstance(mp, np.ndarray):
            continue
        ok = np.isfinite(mp)
        if ok.sum() < 24 * 7:
            continue
        out.append({"name": r["name"], "kind": r["kind"], "out": r["out"], "hours": int(ok.sum()),
                    "patient_profitable": float((mp[ok] > 0).mean()), "patient_median": float(np.median(mp[ok])),
                    "instant_profitable": float((mi[np.isfinite(mi)] > 0).mean()) if np.isfinite(mi).any() else None,
                    "instant_median": float(np.nanmedian(mi)) if np.isfinite(mi).any() else None})
    out.sort(key=lambda x: -x["patient_median"])
    (ROOT / "data" / "recipe_history.json").write_text(json.dumps(out, indent=0))
    print(f"{len(out)} recipes with history; most reliable (patient margin positive >80% of hours):")
    for x in [x for x in out if x["patient_profitable"] > 0.8][:30]:
        print(f"  {x['name'][:50]:50s} median {x['patient_median']:8.0f}  profitable {x['patient_profitable']:.0%}"
              f"  instant median {x['instant_median'] or 0:8.0f}")


if __name__ == "__main__":
    {"fetch": fetch, "scan": scan, "history": history}[sys.argv[1] if len(sys.argv) > 1 else "scan"]()
