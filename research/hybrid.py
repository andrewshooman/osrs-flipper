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
        sim.equity.append(sim.mark(min(t + 1, P1.T - 1)))
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
    from features import features, flip_labels, forward_returns
    from panel import load_panel, read_days, panel_from_df
    from strategy_model import ModelStrategy
    from train import candidate_mask, fit

    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["replay"])
    ap.add_argument("--train-days", type=int, default=120)
    ap.add_argument("--share", type=float, default=0.10)
    ap.add_argument("--rounds", type=int, default=200)
    a = ap.parse_args()

    t0 = time.time()
    d5 = read_days("5m")
    ts5_from, ts5_to = int(d5.ts.min()), int(d5.ts.max())
    d1 = read_days("1h")
    d1 = d1[d1.ts <= ts5_to]
    P1 = panel_from_df(d1, "1h", ts_from=ts5_from - a.train_days * 86400, ts_to=ts5_to, min_trade_frac=0.2)
    P5 = panel_from_df(d5, "5m", ids=P1.ids)
    print(f"hourly {P1.T}x{P1.N}, 5-minute {P5.T} buckets ({time.time() - t0:.0f}s)", flush=True)
    F, Y, Q = features(P1), flip_labels(P1), forward_returns(P1)
    names = sorted(F)
    t_start = int((ts5_from - P1.ts[0]) // 3600)
    embargo = 27
    train = candidate_mask(P1, F)
    train[t_start - embargo:] = False
    model = fit(F, Y, Q, train, names, rounds=a.rounds)
    print(f"model trained on {model.meta['rows']:,} rows before the replay window ({time.time() - t0:.0f}s)", flush=True)
    out = {}
    for S in [STRATEGIES["top_margin"](), STRATEGIES["v0_1"](), ModelStrategy(model, F)]:
        sim, rep = replay(P1, P5, S, Config(share=a.share), t_start, P1.T)
        out[rep["strategy"]] = rep
        print(json.dumps(rep), flush=True)
    return out


if __name__ == "__main__":
    main()
