"""Live paper trading against the real Grand Exchange — no real gold.

Every 5 minutes: pull the newest real 5-minute trade buckets and fill the
bot's open offers against them (real prices, a share of real volume).
Every hour: rebuild the hourly market panel, run the model, and let it
decide buys / holds / sells / cuts. Writes a report the app's Bot tab shows.

    python research/live.py --model research/models/latest      # run until stopped
    python research/live.py --once                               # one step (cron)
State lives in $OSF_DATA/live/ (state.pkl + state.json).
"""
from __future__ import annotations

import argparse
import json
import math
import pickle
import time
from pathlib import Path

import numpy as np
import pandas as pd

import collect
from backtest import Config, tax
from features import features
from hybrid import HybridSim
from panel import DATA, panel_from_df
from strategy_model import PRESETS, ModelStrategy
from train import FlipModel

LIVE = DATA / "live"
HOURS_FILE = LIVE / "hours.parquet"
LOOKBACK_H = 240


def now() -> int:
    return int(time.time())


def last_completed(step: int, lag: int) -> int:
    return (now() - lag) // step * step - step


class Live:
    def __init__(self, model_dir: Path, cfg: Config, variant: str = "production"):
        LIVE.mkdir(parents=True, exist_ok=True)
        self.variant = variant
        self.model = FlipModel.load(model_dir)
        self.model_dir = str(model_dir)
        self.mapping = {m["id"]: m for m in json.loads((DATA / "mapping.json").read_text())}
        self.cfg = cfg
        st = LIVE / "state.pkl"
        if st.exists():
            self.s = pickle.loads(st.read_bytes())
            print(f"resumed: started {time.ctime(self.s['started'])}", flush=True)
        else:
            bar = last_completed(3600, 120)
            self.s = {"started": now(), "base_ts": bar - LOOKBACK_H * 3600, "last_bar": None,
                      "last5m": last_completed(300, 30), "equity": [], "fills": [],
                      "ids": np.array(sorted(self.mapping), dtype=np.int32), "sim": None}

    # ------------------------------------------------------------------ data
    def hours(self, upto: int) -> pd.DataFrame:
        """Hourly snapshots from base_ts..upto: collected day files + live cache + API for gaps."""
        base = self.s["base_ts"]
        have = pd.read_parquet(HOURS_FILE) if HOURS_FILE.exists() else pd.DataFrame(columns=["ts", "id", "ah", "hv", "al", "lv"])
        days = sorted((DATA / "1h").glob("*.parquet"))
        first_day = time.strftime("%Y-%m-%d", time.gmtime(base))
        disk = [pd.read_parquet(f) for f in days if f.stem >= first_day]
        known = set(have.ts.unique())
        for d in disk:
            known |= set(d.ts.unique())
        new = []
        for ts in range(base, upto + 1, 3600):
            if ts not in known:
                new += collect.snapshot_rows("1h", ts)
                time.sleep(0.4)
        if new:
            add = pd.DataFrame(new, columns=["ts", "id", "ah", "hv", "al", "lv"])
            have = pd.concat([have, add], ignore_index=True)
            have[have.ts >= base - 3600].to_parquet(HOURS_FILE, index=False)
        df = pd.concat(disk + [have], ignore_index=True).drop_duplicates(["ts", "id"], keep="last")
        return df[(df.ts >= base) & (df.ts <= upto)]

    # ------------------------------------------------------------------ steps
    def fill_new_buckets(self):
        sim = self.s["sim"]
        target = last_completed(300, 30)
        k = self.s["last5m"] + 300
        while k <= target:
            rows = collect.snapshot_rows("5m", k)
            data = {r[1]: (r[2] or 0.0, float(r[3]), r[4] or 0.0, float(r[5])) for r in rows}
            if sim is not None:
                for f in sim.fill_bucket(k, data):
                    f["name"] = self.mapping.get(f["item"], {}).get("name", str(f["item"]))
                    self.s["fills"].append(f)
                    print(f"  fill {f['side']} {f['qty']} x {f['name']} @ {f['price']:.0f}", flush=True)
            self.s["last5m"] = k
            k += 300
            time.sleep(0.3)

    def decide_if_new_bar(self):
        bar = last_completed(3600, 120)
        if self.s["last_bar"] is not None and bar <= self.s["last_bar"]:
            return False
        t0 = time.time()
        df = self.hours(bar)
        P = panel_from_df(df, "1h", ids=self.s["ids"], ts_from=self.s["base_ts"], ts_to=bar)
        meta = self.mapping
        P.limit = np.array([meta.get(int(i), {}).get("limit", 0) or 0 for i in P.ids], dtype=np.int64)
        P.highalch = np.array([meta.get(int(i), {}).get("highalch", 0) or 0 for i in P.ids], dtype=np.float64)
        P.names = [meta.get(int(i), {}).get("name", str(i)) for i in P.ids]
        F = features(P)
        strat = ModelStrategy(self.model, F, name=f"model-{self.variant}", **PRESETS[self.variant])
        sim = self.s["sim"]
        if sim is None:
            sim = HybridSim(P, self.cfg, strat)
        sim.P, sim.s = P, strat
        strat.prepare(P)
        sim.decide(P.T - 1)
        self.s["sim"] = sim
        self.s["last_bar"] = bar
        eq = sim.mark(P.T - 1)
        self.s["equity"].append([bar + 3600, round(eq)])
        print(f"{time.strftime('%H:%M', time.gmtime(bar + 3600))} decided: equity {eq:,.0f}, "
              f"{len(sim.pos)} held, {len(sim.offers)} offers ({time.time() - t0:.0f}s)", flush=True)
        return True

    def report(self):
        sim = self.s["sim"]
        if sim is None:
            return
        name = lambda i: self.mapping.get(int(sim.P.ids[i]), {}).get("name", str(sim.P.ids[i]))
        latest = collect.get_json(f"{collect.API}/latest")["data"]
        held, unreal = [], 0.0
        for i, p in sim.pos.items():
            L = latest.get(str(int(sim.P.ids[i])), {})
            ask = (L.get("high") or 0) - 1
            net = ask - float(tax(ask)) if ask > 0 else 0
            pnl = net * p.qty - p.cost
            unreal += pnl
            plan = next((e for e in reversed(sim.events) if e["item"] == int(sim.P.ids[i])), None)
            held.append({"id": int(sim.P.ids[i]), "name": name(i), "qty": p.qty, "avg_cost": round(p.cost / p.qty, 1),
                         "market_sell": ask, "pnl_if_sold": round(pnl),
                         "held_h": round((now() - (self.s["base_ts"] + p.opened * 3600)) / 3600, 1),
                         "plan": plan})
        offers = [{"id": int(sim.P.ids[o.item]), "name": name(o.item), "side": o.side, "price": o.price, "qty": o.qty,
                   "filled": o.filled, "placed": o.tag.get("ts"), "reason": o.tag.get("reason")} for o in sim.offers]
        trips = [{**t, "id": int(sim.P.ids[t["item"]]), "name": name(t["item"]),
                  "ts": self.s["base_ts"] + t["t"] * 3600} for t in sim.trips[-200:]]
        for t in trips:
            t.pop("item", None)
        for e in sim.events:
            e.setdefault("name", self.mapping.get(e["item"], {}).get("name", str(e["item"])))
        cash = sim.cash + sim.in_offers()
        equity_cost = cash + sum(p.cost for p in sim.pos.values())
        rep = {
            "updated": now(), "started": self.s["started"], "bankroll": self.cfg.bankroll,
            "reserve_pct": self.cfg.reserve, "share_of_volume": self.cfg.share, "model": self.model_dir,
            "variant": self.variant,
            "realised": round(sim.realised), "unrealised": round(unreal), "tax_paid": round(sim.tax_paid),
            "profit": round(sim.realised + unreal), "equity_at_cost": round(equity_cost), "cash": round(sim.cash),
            "in_buy_offers": round(sim.in_offers()), "positions": held, "offers": offers, "trades": trips,
            "fills": self.s["fills"][-300:], "events": sim.events[-300:], "equity": self.s["equity"][-2000:],
        }
        (LIVE / "state.json").write_text(json.dumps(rep, default=float))

    def save(self):
        sim = self.s["sim"]
        strat = sim.s if sim else None
        if sim:
            sim.s = None
            P = sim.P
            sim.P = None
        (LIVE / "state.pkl").write_bytes(pickle.dumps(self.s))
        if sim:
            sim.s, sim.P = strat, P

    def step(self):
        if self.s["sim"] is not None and self.s["sim"].P is None:
            self.s["last_bar"] = None  # resumed: rebuild the panel before filling
        self.decide_if_new_bar()
        self.fill_new_buckets()
        self.report()
        self.save()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=str(Path(__file__).resolve().parent / "models" / "latest"))
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--bankroll", type=float, default=11_000_000)
    ap.add_argument("--share", type=float, default=0.10)
    ap.add_argument("--variant", default="production", choices=sorted(PRESETS))
    a = ap.parse_args()
    bot = Live(Path(a.model), Config(bankroll=a.bankroll, share=a.share), variant=a.variant)
    while True:
        try:
            bot.step()
        except (OSError, json.JSONDecodeError) as e:  # network trouble (URLError, timeouts): next run catches up
            print(f"step failed: {e!r}", flush=True)
        except Exception as e:
            print(f"step failed: {e!r}", flush=True)
            if a.once:
                raise  # a bug, not the network: fail the scheduled run so it shows up red
        if a.once:
            break
        time.sleep(60)


if __name__ == "__main__":
    main()
