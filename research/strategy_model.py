"""Model-driven strategy for the backtester: every buy, hold, sell and cut is
decided from the trained models — no fixed stop-loss or fixed hold time.

Entry:  rank candidates by expected gp/hour per unit of scarce resource
        (gp or GE slot), where expected gp = P(fill) x E[return | fill] x size.
Size:   quarter-Kelly on the predicted edge vs. forecast spread, capped by the
        buy limit, what the market can fill, and 25% of deployable gp.
Exits:  each bar, for every position, compare selling now with the 4h
        forecast (10/50/90% quantiles) of the achievable sell price:
          - forecast falling and selling now is profitable -> sell at market
          - losing, and even the optimistic forecast doesn't reach breakeven -> cut
          - otherwise hold, asking max(market, breakeven)
Brake:  when the whole market fell >3% over 24h, new buys use half the gp.
"""
from __future__ import annotations

import math

import numpy as np

from backtest import Base, breakeven, tax


class ModelStrategy(Base):
    name = "model"
    buy_wait_h = 2.0
    max_hold_h = 48.0        # hard backstop; normally the model exits first
    kelly_frac = 0.25
    min_exp_ret = 0.002      # skip flips predicted to make <0.2% after tax

    # Tunables (variants override these).
    min_daily_volume = 0          # skip items trading less than this per day (both sides)
    max_item_frac = None          # cap per item (fraction of deployable); None = Config default
    fast_cut = False              # cut as soon as the median forecast says it falls further
    # Pump-and-dump guard: skip items whose price just spiked or whose margin is
    # implausibly wide — the pattern behind the biggest historical losses.
    guard = False
    guard_max_margin = 0.15       # net margin / bid
    guard_max_ret_24h = 0.25      # log return over 24h
    guard_max_z_168h = 3.0
    guard_min_active = 0.5        # share of the last 24h with trades on both sides

    def __init__(self, model, F, name=None, **kw):
        self.m, self.F = model, F
        if name:
            self.name = name
        for k, v in kw.items():
            if not hasattr(self, k):
                raise AttributeError(k)
            setattr(self, k, v)

    def prepare(self, P):
        super().prepare(P)
        self.cols = [self.F[k] for k in self.m.names]

    def _rows(self, t, idx):
        return np.column_stack([c[t, idx] for c in self.cols]).astype(np.float32)

    # ---------------------------------------------------------------- exits
    def manage(self, t, sim):
        P = self.P
        for o in list(sim.offers):
            if o.side == "buy" and t - o.placed >= self.bars(self.buy_wait_h):
                sim.cancel(o)
        items = [i for i in sim.pos]
        if not items:
            return
        pred = self.m.predict_rows(self._rows(t, np.array(items)))
        for k, i in enumerate(items):
            p = sim.pos[i]
            unit = p.cost / p.qty
            ah = P.ah[t, i]
            if not np.isfinite(ah):
                continue
            ask = float(ah) - 1
            net_now = ask - float(tax(ask))
            be = breakeven(unit)
            q10, q50, q90 = pred["fwd4_q10"][k], pred["fwd4_q50"][k], pred["fwd4_q90"][k]
            held = t - p.opened
            listed = [o for o in sim.offers if o.item == i and o.side == "sell"]

            if held >= self.bars(self.max_hold_h):
                sim.dump(t, i)
                continue
            if net_now < unit:
                optimistic = ask * math.exp(q90)
                give_up = optimistic - float(tax(optimistic)) < unit and q50 < 0
                if self.fast_cut and q50 < -0.005 and net_now < unit * 0.985:
                    give_up = True
                if give_up:
                    sim.dump(t, i)          # cut: even the good case doesn't get back to breakeven
                    continue
                target = max(ask, be)
            elif q50 < -0.002 or q10 < -0.03:
                target = ask                # forecast falling: take the profit at market now
            else:
                target = max(ask, min(be * 1.0, ask * math.exp(max(q50, 0))))
            target = round(target)
            if listed:
                o = listed[0]
                if abs(o.price - target) / target > 0.005 and t > o.placed:
                    sim.offers.remove(o)
                    sim.place_sell(t, i, target)
            else:
                sim.place_sell(t, i, target)

    # ---------------------------------------------------------------- entries
    def enter(self, t, sim):
        if sim.free_slots() <= 0 or sim.deployable() <= 0:
            return
        P, F = self.P, self.F
        cand = (np.isfinite(P.ah[t]) & np.isfinite(P.al[t]) & (F["net_margin_rel"][t] > 0)
                & (F["log_vol_buy_6h"][t] > math.log1p(2)) & (F["log_vol_sell_6h"][t] > math.log1p(2)))
        if self.guard:
            with np.errstate(invalid="ignore"):
                cand &= ~(F["net_margin_rel"][t] > self.guard_max_margin)
                cand &= ~(F["ret_24h"][t] > self.guard_max_ret_24h)
                cand &= ~(F["z_168h"][t] > self.guard_max_z_168h)
                cand &= F["active_24h"][t] >= self.guard_min_active
        if self.min_daily_volume:
            per_day = np.expm1(F["log_vol_buy_24h"][t]) * 24 + np.expm1(F["log_vol_sell_24h"][t]) * 24
            cand &= per_day >= self.min_daily_volume
        idx = np.nonzero(cand)[0]
        if not len(idx):
            return
        pr = self.m.predict_rows(self._rows(t, idx))
        exp_ret = pr["exp_ret"]
        hours = np.maximum(pr["hours"], 0.25)
        spread = np.maximum(pr["fwd4_q90"] - pr["fwd4_q10"], 1e-3) / 2.56     # ~1 sigma of the 4h move
        good = exp_ret > self.min_exp_ret
        if not good.any():
            return
        idx, exp_ret, hours, spread = idx[good], exp_ret[good], hours[good], spread[good]

        deploy = sim.deployable()
        if F["mkt_ret_24h"][t, 0] < -0.03:
            deploy *= 0.5
        buy = P.al[t, idx].astype(np.float64) + 1
        per_hour = 3600 / P.step
        rate = np.minimum(P.lv[t, idx], P.hv[t, idx]) * per_hour * sim.cfg.share
        cap = self.max_item_frac or sim.cfg.max_item_frac
        kelly = np.clip(self.kelly_frac * exp_ret / (spread ** 2), 0, cap)
        budget = np.minimum(kelly * deploy, deploy * cap)
        lim = np.where(P.limit[idx] > 0, P.limit[idx], 1e12)
        qty = np.floor(np.minimum.reduce([budget / buy, lim, rate * 4]))
        gp = qty * buy * exp_ret
        usage = np.maximum(qty * buy / max(deploy, 1), 1 / max(sim.free_slots(), 1))
        score = np.where(qty >= 1, gp / hours / usage, -1)
        for j in np.argsort(-score):
            if score[j] <= 0 or sim.free_slots() <= 0 or sim.deployable() < buy[j]:
                break
            sim.place_buy(t, int(idx[j]), buy[j], int(qty[j]))
