"""Paper trading with realistic fills: decide on hourly bars, fill on real
5-minute trades.

Every decision (buy / hold / sell / cut) is made once per completed hour from
the hourly panel. Offers then fill only against 5-minute buckets that start
after the decision, and only when real trades happened at the offer's price:
  BUY at p fills when instant-sells averaged <= p in a bucket, up to `share`
  of that bucket's real instant-sell volume (more if the bid crosses the
  spread). SELL is symmetric. A dump is an aggressive sell at the instant-sell
  price — it still needs real buyers, so it can take several buckets.
So the bot can never sell 1,000 of an item that trades 50 a day.

    python research/hybrid.py replay --train-until 2026-08-01   # history replay
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np

from backtest import Config, Offer, Sim
from panel import Panel


class HybridSim(Sim):
    def __init__(self, P1: Panel, cfg: Config, strategy):
        super().__init__(P1, cfg, strategy)
        self.now_ts = int(P1.ts[0])
        self.id_index = {int(x): n for n, x in enumerate(P1.ids)}
        self.events: list[dict] = []   # human-readable decision log

    # ---- offers carry the time they were placed; fills need later buckets
    def _stamp(self, ok: bool, kind: str, i: int, reason: str = ""):
        if ok:
            o = self.offers[-1]
            o.tag["ts"] = self.now_ts
            o.tag.setdefault("reason", kind)
            self.events.append({"ts": self.now_ts, "action": kind, "item": int(self.P.ids[i]),
                                "price": o.price, "qty": o.qty, "why": reason})
        return ok

    def place_buy(self, t, i, price, qty, tag=None):
        return self._stamp(super().place_buy(t, i, price, qty, tag), "buy", i, (tag or {}).get("why", ""))

    def place_sell(self, t, i, price, tag=None):
        return self._stamp(super().place_sell(t, i, price, tag), "sell", i, (tag or {}).get("why", ""))

    def _fill(self, t):  # hourly fills are replaced by fill_bucket()
        return

    def dump(self, t, i, why="cut"):
        """Aggressive sell at the instant-sell price; still limited by real volume."""
        for o in [o for o in self.offers if o.item == i and o.side == "sell"]:
            self.offers.remove(o)
        p = self.pos.get(i)
        if not p or p.qty < 1:
            return
        px = self.P.al[t, i]
        if not np.isfinite(px):
            px = self.last_al[i]
        if self.free_slots() < 1:  # free a slot: cancel the newest buy offer
            buys = [o for o in self.offers if o.side == "buy"]
            if buys:
                self.cancel(buys[-1])
        o = Offer(i, "sell", max(1.0, float(px) * (1 - self.cfg.dump_slippage)), p.qty, t,
                  tag={"ts": self.now_ts, "reason": "dump"})
        self.offers.append(o)
        self.events.append({"ts": self.now_ts, "action": "cut", "item": int(self.P.ids[i]),
                            "price": o.price, "qty": o.qty, "why": why})

    def fill_bucket(self, ts: int, data: dict) -> list[dict]:
        """Apply one real 5-minute bucket. data: {item_id: (ah, hv, al, lv)}."""
        share, fills = self.cfg.share, []
        for o in list(self.offers):
            if o.tag.get("ts", 0) > ts:
                continue
            d = data.get(int(self.P.ids[o.item]))
            if not d:
                continue
            ah, hv, al, lv = d
            avail = 0.0
            if o.side == "buy" and al and al <= o.price:
                avail = lv * share + (hv * 0.5 if ah and o.price >= ah else 0)
            elif o.side == "sell" and ah and ah >= o.price:
                avail = hv * share + (lv * 0.5 if al and o.price <= al else 0)
            elif o.side == "sell" and o.tag.get("reason") == "dump" and al and o.price <= al:
                avail = lv * 0.5 + hv * share
            if avail <= 0:
                continue
            o.frac += avail
            q = int(min(o.qty - o.filled, o.frac))
            if q <= 0:
                continue
            o.frac -= q
            o.filled += q
            t_now = int((ts - self.P.ts[0]) // self.P.step)
            if o.side == "buy":
                p = self.pos.setdefault(o.item, __import__("backtest").Position())
                if p.qty == 0:
                    p.opened = t_now
                p.qty += q
                p.cost += q * o.price
                self.buys_log.setdefault(o.item, []).append((t_now, q))
            else:
                self._sell_fill(t_now, o.item, q, o.price, reason=o.tag.get("reason", "sell"))
            fills.append({"ts": ts, "side": o.side, "item": int(self.P.ids[o.item]), "qty": q, "price": o.price})
            if o.filled >= o.qty:
                self.offers.remove(o)
        return fills

    def decide(self, t: int):
        """Decision at the close of hourly bar t."""
        self.now_ts = int(self.P.ts[t]) + self.P.step
        self.s.manage(t, self)
        self.s.enter(t, self)


def robust_dd(sim, P1, t_start):
    """Max drawdown of equity re-marked with the median instant-sell price of the
    previous 6 hours (one thin hour can't fake a crash). Uses the recorded marks."""
    return float((np.maximum.accumulate(np.array(sim.robust_equity)) - np.array(sim.robust_equity)).max()) if getattr(sim, "robust_equity", None) else float("nan")


def buckets_from_panel(P5: Panel):
    """Yield (ts, {id: (ah, hv, al, lv)}) for each 5-minute bucket."""
    ids = P5.ids
    for k in range(P5.T):
        ah, al, hv, lv = P5.ah[k], P5.al[k], P5.hv[k], P5.lv[k]
        live = np.nonzero((hv > 0) | (lv > 0))[0]
        yield int(P5.ts[k]), {int(ids[j]): (float(ah[j]) if np.isfinite(ah[j]) else 0.0, float(hv[j]),
                                            float(al[j]) if np.isfinite(al[j]) else 0.0, float(lv[j])) for j in live}


def replay(P1: Panel, P5: Panel, strategy, cfg: Config, t_start: int, t_end: int) -> tuple[HybridSim, dict]:
    sim = HybridSim(P1, cfg, strategy)
    strategy.prepare(P1)
    feed = buckets_from_panel(P5)
    pending = next(feed, None)
    for t in range(t_start, t_end):
        sim.decide(t)
        horizon = sim.now_ts + P1.step
        while pending and pending[0] < horizon:
            if pending[0] >= sim.now_ts:
                sim.fill_bucket(*pending)
            pending = next(feed, None)
        tm = min(t + 1, P1.T - 1)
        sim.equity.append(sim.mark(tm))
        # Robust mark: median instant-sell over the last 6 hours, after tax.
        if sim.pos:
            idx = np.array(list(sim.pos))
            with np.errstate(all="ignore"):
                med = np.nanmedian(P1.al[max(0, tm - 5):tm + 1, idx], axis=0)
            med = np.where(np.isfinite(med), med, sim.last_al[idx])
            val = sum(p.qty * (m - float(min(np.floor(m * 0.02), 5e6))) for p, m in zip(sim.pos.values(), med))
        else:
            val = 0.0
        sim.robust_equity = getattr(sim, "robust_equity", []) + [sim.cash + sim.in_offers() + val]
    # Value what's still held at the instant-sell price after tax (no forced dump).
    end = min(t_end, P1.T - 1)
    for o in list(sim.offers):
        sim.cancel(o)
    final = sim.mark(end)
    sim.equity.append(final)
    rep = sim.report(t_start, t_end)
    rep["final_equity"] = round(final)
    rep["profit"] = round(final - cfg.bankroll)
    rep["profit_per_day"] = round(rep["profit"] / max(rep["days"], 1e-9))
    rep["still_holding"] = len(sim.pos)
    return sim, rep


def main():
    from backtest import STRATEGIES
    from features import features_chunked, labels_chunked
    from panel import load_panel, read_days, panel_from_df
    from strategy_model import PRESETS, ModelStrategy
    from train import candidate_mask, fit

    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["replay"])
    ap.add_argument("--train-days", type=int, default=120)
    ap.add_argument("--share", type=float, default=0.10)
    ap.add_argument("--rounds", type=int, default=200)
    ap.add_argument("--out", default="replay-5m.json", help="file name under research/reports/")
    ap.add_argument("--skip-baselines", action="store_true", help="only the model strategies")
    a = ap.parse_args()

    t0 = time.time()
    d5 = read_days("5m")
    ts5_from, ts5_to = int(d5.ts.min()), int(d5.ts.max())
    d1 = read_days("1h")
    d1 = d1[d1.ts <= ts5_to]
    P1 = panel_from_df(d1, "1h", ts_from=ts5_from - a.train_days * 86400, ts_to=ts5_to, min_trade_frac=0.2)
    P5 = panel_from_df(d5, "5m", ids=P1.ids)
    print(f"hourly {P1.T}x{P1.N}, 5-minute {P5.T} buckets ({time.time() - t0:.0f}s)", flush=True)
    F = features_chunked(P1)
    Y, Q = labels_chunked(P1)
    names = sorted(F)
    t_start = int((ts5_from - P1.ts[0]) // 3600)
    embargo = 27
    train = candidate_mask(P1, F)
    train[t_start - embargo:] = False
    from features import FEATURE_SET
    fs = "" if FEATURE_SET == "v1" else f"-f{FEATURE_SET}"
    cache = Path(__file__).resolve().parent / "models" / "replay-cache" / f"{a.train_days}-{a.rounds}-{ts5_from}{fs}"
    if (cache / "meta.json").exists():
        from train import FlipModel
        model = FlipModel.load(cache)
    else:
        model = fit(F, Y, Q, train, names, rounds=a.rounds)
        model.save(cache)
    print(f"model trained on {model.meta['rows']:,} rows before the replay window ({time.time() - t0:.0f}s)", flush=True)
    out = {}
    no_prio = {**PRESETS["production"], "sell_priority": False}
    runs = [] if a.skip_baselines else [(STRATEGIES["top_margin"](), a.share), (STRATEGIES["v0_1"](), a.share)]
    runs += [(ModelStrategy(model, F, name="model-production", **PRESETS["production"]), a.share),
             (ModelStrategy(model, F, name="model-production@5%", **PRESETS["production"]), 0.05),
             (ModelStrategy(model, F, name="model-production-no-sell-priority", **no_prio), a.share),
             (ModelStrategy(model, F, name="model-production-no-sell-priority@5%", **no_prio), 0.05)]
    for S, share in runs:
        sim, rep = replay(P1, P5, S, Config(share=share), t_start, P1.T)
        prof = np.array([x["profit"] for x in sim.trips]) if sim.trips else np.zeros(0)
        rep["top3_share_of_profit"] = round(float(np.sort(prof)[-3:].sum() / prof.sum()), 2) if prof.sum() > 0 else None
        rep["worst_trades"] = [{"item": P1.names[x["item"]], "qty": x["qty"], "buy": round(x["buy"]), "sell": x["sell"],
                                "profit": round(x["profit"]), "hold_h": round(x["hold"], 1), "reason": x["reason"]}
                               for x in sorted(sim.trips, key=lambda x: x["profit"])[:5]]
        # Drawdown on realised profit only (closed trades), and on equity marked at a
        # steadier price: median instant-sell of the last 6 hours instead of one hour.
        trips = sorted(sim.trips, key=lambda x: x["t"])
        cum = np.cumsum([x["profit"] for x in trips]) if trips else np.zeros(1)
        rep["max_drawdown_realised"] = round(float((np.maximum.accumulate(cum) - cum).max()))
        rep["max_drawdown_robust_mark"] = round(float(robust_dd(sim, P1, t_start)))
        daily = np.diff(np.array(sim.equity)[::24]) if len(sim.equity) > 48 else np.zeros(0)
        rep["days_positive"] = f"{int((daily > 0).sum())}/{len(daily)}"
        out[rep["strategy"]] = rep
        print(json.dumps(rep), flush=True)
    (Path(__file__).resolve().parent / "reports" / a.out).write_text(json.dumps(out, indent=1, default=float))
    return out


if __name__ == "__main__":
    main()
