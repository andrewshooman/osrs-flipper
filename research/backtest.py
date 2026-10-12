"""Event-driven GE backtester.

Rules (no look-ahead): at bar t the simulator first fills existing offers
against bar t, then the strategy decides using data up to and including t,
and any new offers can only fill from bar t+1.

Fill model (same idea as the app's practice mode):
  BUY at p fills when instant-sells traded at <= p in a bar: you get `share`
  of that bar's instant-sell volume (plus half the instant-buy volume if your
  bid crosses the instant-buy price). SELL is symmetric.
GE rules: 2% tax (floor, 5M cap) on sells, buy limit per 4h, 8 slots, gold
is locked in buy offers, cash reserve never used.

    python research/backtest.py --step 1h --days 120          # real data
    python research/backtest.py --synthetic                    # pipeline check
"""
from __future__ import annotations

import argparse
import math
from dataclasses import dataclass, field

import numpy as np

from panel import Panel, load_panel, synthetic_panel


def tax(p):
    return np.minimum(np.floor(np.asarray(p, dtype=np.float64) * 0.02), 5_000_000)


def breakeven(cost: float) -> float:
    p = max(1.0, math.floor(min((cost - 1) / 0.98, cost + 5e6)) - 1)
    while p - tax(p) < cost:
        p += 1
    return p


@dataclass
class Config:
    bankroll: float = 11_000_000
    reserve: float = 0.5
    slots: int = 8
    share: float = 0.10          # share of each bar's opposite-side volume you capture
    max_item_frac: float = 0.25  # of deployable capital
    dump_slippage: float = 0.005


@dataclass
class Offer:
    item: int
    side: str
    price: float
    qty: int
    placed: int
    filled: int = 0
    frac: float = 0.0
    tag: dict = field(default_factory=dict)


@dataclass
class Position:
    qty: int = 0
    cost: float = 0.0     # total cost of held units
    opened: int = -1


class Sim:
    def __init__(self, P: Panel, cfg: Config, strategy):
        self.P, self.cfg, self.s = P, cfg, strategy
        self.cash = cfg.bankroll
        self.offers: list[Offer] = []
        self.pos: dict[int, Position] = {}
        self.buys_log: dict[int, list[tuple[int, int]]] = {}
        self.trips: list[dict] = []
        self.equity: list[float] = []
        self.realised = 0.0
        self.tax_paid = 0.0
        self.last_al = np.nan_to_num(P.al[0], nan=0.0).astype(np.float64)
        self.window = max(1, int(4 * 3600 // P.step))

    # ---- capital ---------------------------------------------------------
    def in_offers(self) -> float:
        return sum((o.qty - o.filled) * o.price for o in self.offers if o.side == "buy")

    def holding_cost(self) -> float:
        return sum(p.cost for p in self.pos.values())

    def deployable(self) -> float:
        equity_at_cost = self.cfg.bankroll + self.realised
        used = self.holding_cost() + self.in_offers()
        return max(0.0, min(self.cash, equity_at_cost * (1 - self.cfg.reserve) - used))

    def free_slots(self) -> int:
        return self.cfg.slots - len(self.offers)

    def limit_left(self, i: int, t: int) -> float:
        lim = self.P.limit[i]
        if lim <= 0:
            return math.inf
        used = sum(q for (tt, q) in self.buys_log.get(i, []) if tt > t - self.window)
        pending = sum(o.qty - o.filled for o in self.offers if o.item == i and o.side == "buy")
        return max(0, lim - used - pending)

    # ---- actions -----------------------------------------------------------
    def place_buy(self, t: int, i: int, price: float, qty: int, tag=None) -> bool:
        qty = int(min(qty, self.limit_left(i, t), self.deployable() // price))
        if qty < 1 or self.free_slots() < 1 or any(o.item == i for o in self.offers):
            return False
        self.cash -= qty * price
        self.offers.append(Offer(i, "buy", float(price), qty, t, tag=tag or {}))
        return True

    def place_sell(self, t: int, i: int, price: float, tag=None) -> bool:
        p = self.pos.get(i)
        if not p or p.qty < 1 or self.free_slots() < 1 or any(o.item == i and o.side == "sell" for o in self.offers):
            return False
        self.offers.append(Offer(i, "sell", float(price), p.qty, t, tag=tag or {}))
        return True

    def cancel(self, o: Offer) -> None:
        if o.side == "buy":
            self.cash += (o.qty - o.filled) * o.price
        self.offers.remove(o)

    def dump(self, t: int, i: int) -> None:
        """Instant-sell everything held at the bar's instant-sell price."""
        for o in [o for o in self.offers if o.item == i and o.side == "sell"]:
            self.offers.remove(o)
        p = self.pos.get(i)
        if not p or p.qty < 1:
            return
        px = self.P.al[t, i]
        if not np.isfinite(px):
            px = self.last_al[i]
        self._sell_fill(t, i, p.qty, max(1.0, float(px) * (1 - self.cfg.dump_slippage)), reason="dump")

    def _sell_fill(self, t, i, q, price, reason="sell"):
        p = self.pos[i]
        unit_cost = p.cost / p.qty
        tx = float(tax(price)) * q
        proceeds = price * q - tx
        cost = unit_cost * q
        self.cash += proceeds
        self.realised += proceeds - cost
        self.tax_paid += tx
        self.trips.append(dict(item=i, qty=q, buy=unit_cost, sell=price, profit=proceeds - cost,
                               hold=(t - p.opened) * self.P.step / 3600, reason=reason, t=t))
        p.qty -= q
        p.cost -= cost
        if p.qty <= 0:
            del self.pos[i]

    # ---- one bar -----------------------------------------------------------
    def _fill(self, t: int) -> None:
        P, share = self.P, self.cfg.share
        for o in list(self.offers):
            if o.placed >= t:
                continue
            i = o.item
            ah, al, hv, lv = P.ah[t, i], P.al[t, i], P.hv[t, i], P.lv[t, i]
            avail = 0.0
            if o.side == "buy" and np.isfinite(al) and al <= o.price:
                avail = lv * share + (hv * 0.5 if np.isfinite(ah) and o.price >= ah else 0)
            elif o.side == "sell" and np.isfinite(ah) and ah >= o.price:
                avail = hv * share + (lv * 0.5 if np.isfinite(al) and o.price <= al else 0)
            if avail <= 0:
                continue
            o.frac += avail
            q = int(min(o.qty - o.filled, o.frac))
            if q <= 0:
                continue
            o.frac -= q
            o.filled += q
            if o.side == "buy":
                p = self.pos.setdefault(i, Position())
                if p.qty == 0:
                    p.opened = t
                p.qty += q
                p.cost += q * o.price
                self.buys_log.setdefault(i, []).append((t, q))
            else:
                self._sell_fill(t, i, q, o.price)
            if o.filled >= o.qty:
                self.offers.remove(o)

    def mark(self, t: int) -> float:
        al = self.P.al[t]
        ok = np.isfinite(al)
        self.last_al[ok] = al[ok]
        value = sum(p.qty * (self.last_al[i] - float(tax(self.last_al[i]))) for i, p in self.pos.items())
        return self.cash + self.in_offers() + value

    def run(self, start: int = 0, end: int | None = None) -> dict:
        end = end or self.P.T
        if hasattr(self.s, "prepare"):
            self.s.prepare(self.P)
        for t in range(start, end):
            self._fill(t)
            self.s.manage(t, self)
            self.s.enter(t, self)
            self.equity.append(self.mark(t))
        # Liquidate at the end so results are comparable.
        for i in list(self.pos):
            self.dump(end - 1, i)
        for o in list(self.offers):
            self.cancel(o)
        self.equity.append(self.cash)
        return self.report(start, end)

    def report(self, start, end) -> dict:
        eq = np.array(self.equity)
        peak = np.maximum.accumulate(eq)
        days = (end - start) * self.P.step / 86400
        profits = np.array([x["profit"] for x in self.trips]) if self.trips else np.zeros(0)
        reasons = {}
        for x in self.trips:
            reasons[x["reason"]] = reasons.get(x["reason"], 0) + 1
        daily = np.diff(eq[:: max(1, int(86400 // self.P.step))]) if len(eq) > 2 else np.zeros(0)
        return dict(
            strategy=getattr(self.s, "name", type(self.s).__name__),
            days=round(days, 1),
            profit=round(eq[-1] - self.cfg.bankroll),
            profit_per_day=round((eq[-1] - self.cfg.bankroll) / max(days, 1e-9)),
            return_on_deployable_pct=round(float(100 * (eq[-1] - self.cfg.bankroll) / (self.cfg.bankroll * (1 - self.cfg.reserve))), 2),
            max_drawdown=round(float((peak - eq).max())),
            sharpe_daily=round(float(daily.mean() / daily.std() * math.sqrt(365)), 2) if len(daily) > 2 and daily.std() > 0 else None,
            trips=len(self.trips),
            win_rate=round(float((profits > 0).mean()), 3) if len(profits) else None,
            avg_hold_h=round(float(np.mean([x["hold"] for x in self.trips])), 1) if self.trips else None,
            tax_paid=round(self.tax_paid),
            exits=reasons,
        )


# ============================================================================ strategies
class Base:
    """Shared plumbing: default offer management."""
    name = "base"
    buy_wait_h = 2.0     # cancel unfilled buy remainder after this long
    max_hold_h = 24.0
    stop_loss = None     # fraction below cost that forces a dump
    min_bar_volume = 5

    def prepare(self, P: Panel):
        self.P = P
        self.bars = lambda h: max(1, int(round(h * 3600 / P.step)))

    def sell_price(self, t, i, sim):
        return float(self.P.ah[t, i]) - 1 if np.isfinite(self.P.ah[t, i]) else None

    def manage(self, t, sim):
        P = self.P
        for o in list(sim.offers):
            if o.side == "buy" and t - o.placed >= self.bars(self.buy_wait_h):
                sim.cancel(o)
        for i, p in list(sim.pos.items()):
            held = t - p.opened
            unit = p.cost / p.qty
            # Stop-loss is judged on what a sell OFFER would get (instant-buy - 1),
            # same as the app; judging on the instant-sell price would stop out
            # every fresh flip immediately because of the spread + tax.
            ask = P.ah[t, i] - 1 if np.isfinite(P.ah[t, i]) else np.nan
            if held >= self.bars(self.max_hold_h) or (self.stop_loss and np.isfinite(ask) and ask - tax(ask) < unit * (1 - self.stop_loss)):
                sim.dump(t, i)
                continue
            if not any(o.item == i and o.side == "sell" for o in sim.offers):
                px = self.sell_price(t, i, sim)
                if px:
                    sim.place_sell(t, i, px)


class TopMargin(Base):
    """Baseline A - what most flipping tools surface: biggest current margin
    x tradeable quantity. Buy at instant-sell +1, sell at instant-buy -1."""
    name = "baseline_top_margin"

    def enter(self, t, sim):
        if sim.free_slots() <= 0 or sim.deployable() <= 0:
            return
        P = self.P
        ah, al = P.ah[t].astype(np.float64), P.al[t].astype(np.float64)
        buy, sell = al + 1, ah - 1
        margin = sell - tax(sell) - buy
        vol = np.minimum(P.hv[t], P.lv[t])
        qty = np.minimum(np.where(P.limit > 0, P.limit, 1e12), vol * sim.cfg.share * self.bars(self.buy_wait_h))
        score = np.where(np.isfinite(margin) & (margin > 0) & (vol >= self.min_bar_volume), margin * qty, -1)
        cap = sim.deployable() * sim.cfg.max_item_frac
        for i in np.argsort(-score)[: 3 * sim.cfg.slots]:
            if score[i] <= 0 or sim.free_slots() <= 0:
                break
            q = int(min(qty[i], cap // buy[i]))
            sim.place_buy(t, int(i), buy[i], q)


class V01(Base):
    """Baseline B - port of the app's v0.1 heuristic: conservative margins,
    persistence, volatility/trend risk charge, capital-efficiency ranking."""
    name = "baseline_v0_1"
    max_hold_h = 12.0
    stop_loss = 0.03
    persist_bars = 6
    horizon_h = 2.0
    risk_aversion = 1.0

    def prepare(self, P):
        super().prepare(P)
        mid = np.log(np.where(np.isfinite(P.ah) & np.isfinite(P.al), (P.ah + P.al) / 2, np.nan))
        import pandas as pd
        m = pd.DataFrame(mid).ffill()
        r = m.diff()
        per_hour = 3600 / P.step
        self.sigma = (r.rolling(self.bars(72), min_periods=10).std() * math.sqrt(per_hour)).values
        n = self.bars(24)
        x = np.arange(n) - (n - 1) / 2
        self.trend = (m.rolling(n).apply(lambda y: (x * (y - y.mean())).sum() / (x * x).sum(), raw=True) * per_hour).values \
            if P.T * P.N < 2e6 else (m - m.shift(n)).values / (n / per_hour)
        sell = P.ah.astype(np.float64) - 1
        net = sell - tax(sell) - (P.al + 1)
        prof = pd.DataFrame(np.where(np.isfinite(net), (net > 0).astype(float), np.nan))
        self.persist = prof.rolling(self.persist_bars, min_periods=3).mean().values
        self.typical = pd.DataFrame(net).rolling(self.persist_bars, min_periods=3).median().values

    def sell_price(self, t, i, sim):
        p = sim.pos[i]
        ah = self.P.ah[t, i]
        if not np.isfinite(ah):
            return None
        return max(float(ah) - 1, breakeven(p.cost / p.qty))

    def manage(self, t, sim):
        # Reprice sells that sit >1% above the market (v0.1 "reprice" advice).
        for o in list(sim.offers):
            ah = self.P.ah[t, o.item]
            if o.side == "sell" and np.isfinite(ah) and o.price > ah * 1.01 and t > o.placed:
                sim.offers.remove(o)
        super().manage(t, sim)

    def enter(self, t, sim):
        if sim.free_slots() <= 0 or sim.deployable() <= 0:
            return
        P = self.P
        buy = P.al[t].astype(np.float64) + 1
        sell = P.ah[t].astype(np.float64) - 1
        margin = sell - tax(sell) - buy
        typ = self.typical[t]
        margin = np.where(np.isfinite(typ), np.minimum(margin, 0.5 * margin + 0.5 * typ), margin)
        p = np.nan_to_num(self.persist[t], nan=0.6)
        per_hour = 3600 / P.step
        buy_rate, sell_rate = P.lv[t] * per_hour * sim.cfg.share, P.hv[t] * per_hour * sim.cfg.share
        with np.errstate(divide="ignore", invalid="ignore"):
            liq = np.floor(self.horizon_h * buy_rate * sell_rate / (buy_rate + sell_rate))
            cap = sim.deployable() * sim.cfg.max_item_frac
            qty = np.minimum(np.minimum(np.where(P.limit > 0, P.limit, 1e12), liq), np.floor(cap / buy))
            hours = np.maximum(1 / 12, qty / buy_rate + qty / sell_rate)
            sig = np.nan_to_num(self.sigma[t], nan=0.01)
            risk = 0.5 * self.risk_aversion * qty * buy * sig * np.sqrt(hours)
            drift_raw = qty * buy * np.nan_to_num(self.trend[t]) / per_hour * hours
            drift = np.minimum(drift_raw, 0) + 0.25 * np.maximum(drift_raw, 0)
            exp = qty * margin * (0.4 + 0.6 * p) + drift - risk
            usage = np.maximum(qty * buy / max(sim.deployable(), 1), 1 / max(sim.free_slots(), 1))
            score = np.where(np.isfinite(exp) & (exp > 0) & (qty >= 1) & (margin > 0), exp / hours / usage, -1)
        for i in np.argsort(-score)[: 3 * sim.cfg.slots]:
            if score[i] <= 0 or sim.free_slots() <= 0:
                break
            sim.place_buy(t, int(i), buy[i], int(qty[i]))


STRATEGIES = {"top_margin": TopMargin, "v0_1": V01}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--step", default="1h")
    ap.add_argument("--days", type=int, default=None)
    ap.add_argument("--synthetic", action="store_true")
    ap.add_argument("--share", type=float, default=0.10)
    ap.add_argument("--strategies", default=",".join(STRATEGIES))
    a = ap.parse_args()
    P = synthetic_panel() if a.synthetic else load_panel(a.step, a.days)
    print(f"panel: {P.T} bars x {P.N} items, step {P.step}s")
    for k in a.strategies.split(","):
        r = Sim(P, Config(share=a.share), STRATEGIES[k]()).run()
        print(r)


if __name__ == "__main__":
    main()
