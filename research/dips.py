"""Opportunity alerts: do sharp price drops on liquid items revert soon enough to
be worth an alert?

    python research/dips.py [--days-tune 30]

A "dip" at 5-minute bucket t on item i: the average instant-sell price (al) is
at least `drop` below its median over the previous 24 hours, on real volume.
The player gets an alert once bucket t is published and reacts within ~10
minutes, so a buy at the dip price p can only fill from bucket t+2 on, and
only against real instant-sells at or below p (up to `share` of their volume,
the same fill rule as hybrid.py). Once bought, a sell at the pre-dip median
instant-buy price (ah) fills against real instant-buys; whatever hasn't sold
after `hold_h` hours is dumped at the instant-sell price. Profit is after GE tax.

Controls: the same trade placed at random non-dip moments on the same items,
so a "win" isn't just the ordinary buy-low/sell-high margin.

Parameters are tuned on the first `--days-tune` days and reported, unchanged,
on the rest (walk-forward), with at most `slots` alert trades open at once and
`cap` gp per trade.
"""
from __future__ import annotations

import argparse
import itertools
import json
import time
from pathlib import Path

import numpy as np

from backtest import tax
from panel import panel_from_df, read_days

DAY = 288  # 5-minute buckets per day


def liquid(P, min_gp_day: float, min_frac: float):
    """Items that trade most buckets and turn over at least min_gp_day a day."""
    traded = np.isfinite(P.al) & (P.lv > 0)
    frac = traded.mean(0)
    gp_day = np.nan_to_num(P.al * P.lv).sum(0) / (P.T / DAY)
    return np.where((frac >= min_frac) & (gp_day >= min_gp_day))[0]


def rolling_median(x: np.ndarray, w: int) -> np.ndarray:
    """Median of the previous w values (excluding the current one), NaN-aware, per column."""
    from numpy.lib.stride_tricks import sliding_window_view
    out = np.full(x.shape, np.nan, dtype=np.float32)
    for j in range(x.shape[1]):  # one column at a time keeps memory small
        v = sliding_window_view(x[:, j].astype(np.float32), w)
        with np.errstate(all="ignore"):
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", RuntimeWarning)
                med = np.nanmedian(v, axis=1)
        out[w:, j] = med[:-1]
    return out


def simulate(P, items, ref_low, ref_high, signal, a, t0, t1, rng=None):
    """Trades for the signal (bool T x N) between buckets t0..t1, at most a.slots open at once."""
    open_until = []  # bucket index when each open trade ends
    trades = []
    ts_list = np.argwhere(signal[t0:t1])
    order = np.lexsort((ts_list[:, 1], ts_list[:, 0]))
    last_item_t = {}
    for k in order:
        t, j = int(ts_list[k, 0]) + t0, int(ts_list[k, 1])
        i = items[j]
        if t - last_item_t.get(i, -10 ** 9) < DAY // 4:
            continue  # one alert per item per 6 hours
        open_until = [u for u in open_until if u > t]
        if len(open_until) >= a.slots:
            continue
        p = float(P.al[t, i])
        if not np.isfinite(p) or p <= 0:
            continue
        target = float(ref_high[t, j]) * (1 - a.margin)
        if not np.isfinite(target) or target - tax(target) <= p * 1.002:
            continue
        lim = P.limit[i] if P.limit[i] > 0 else 10 ** 9
        want = int(min(lim, a.cap // p))
        if want < 1:
            continue
        # Buy: fills from t+2 for `fill_min` minutes.
        got, frac = 0, 0.0
        u = t + 2
        end_buy = min(t1, t + 2 + a.fill_min // 5)
        while u < end_buy and got < want:
            al, lv = P.al[u, i], P.lv[u, i]
            if np.isfinite(al) and al <= p:
                frac += lv * a.share
                q = int(min(want - got, frac))
                got += q
                frac -= q
            u += 1
        if got < 1:
            last_item_t[i] = t
            continue
        # Sell at the target; dump what's left at the instant-sell price after hold_h.
        left, frac, proceeds = got, 0.0, 0.0
        end_sell = min(P.T - 1, u + int(a.hold_h * 12))
        while u < end_sell and left > 0:
            ah, hv = P.ah[u, i], P.hv[u, i]
            if np.isfinite(ah) and ah >= target:
                frac += hv * a.share
                q = int(min(left, frac))
                proceeds += q * (target - tax(target))
                left -= q
                frac -= q
            u += 1
        if left > 0:
            dump = P.al[u, i]
            if not np.isfinite(dump):
                back = P.al[max(0, u - 12):u + 1, i]
                dump = np.nanmin(back) if np.isfinite(back).any() else p
            dump = float(dump) * 0.99
            proceeds += left * (dump - tax(dump))
        profit = proceeds - got * p
        trades.append({"t": t, "item": int(P.ids[i]), "name": P.names[i], "qty": got, "buy": p,
                       "target": round(target), "profit": round(profit), "dumped": left, "end": u})
        open_until.append(u)
        last_item_t[i] = t
    return trades


def summary(trades, days):
    if not trades:
        return {"trades": 0, "per_day": 0.0}
    prof = np.array([x["profit"] for x in trades], dtype=float)
    return {"trades": len(trades), "trades_per_day": round(len(trades) / days, 2),
            "per_day": round(prof.sum() / days), "per_trade": round(prof.mean()),
            "win_rate": round(float((prof > 0).mean()), 2),
            "dumped_share": round(float(np.mean([x["dumped"] > 0 for x in trades])), 2),
            "worst": round(prof.min()), "best": round(prof.max())}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days-tune", type=int, default=30)
    ap.add_argument("--min-gp-day", type=float, default=50e6, help="liquidity: gp of instant-sells a day")
    ap.add_argument("--out", default="dips-5m.json")
    a0 = ap.parse_args()
    t_start = time.time()
    df = read_days("5m")
    P = panel_from_df(df, "5m", min_trade_frac=0.5)
    items = liquid(P, a0.min_gp_day, 0.6)
    print(f"{P.T} buckets x {P.N} items; {len(items)} liquid ({time.time() - t_start:.0f}s)", flush=True)
    al = P.al[:, items]
    ref_low = rolling_median(al, DAY)
    ref_high = rolling_median(P.ah[:, items], DAY)
    vol_med = rolling_median(P.lv[:, items].astype(np.float32), DAY)
    print(f"reference prices ready ({time.time() - t_start:.0f}s)", flush=True)

    split = a0.days_tune * DAY
    days_tune = (split - DAY) / DAY
    days_test = (P.T - split) / DAY
    grid = {"drop": [0.03, 0.05, 0.08, 0.12], "vol_x": [1.0, 2.0], "margin": [0.0, 0.01],
            "hold_h": [2, 6, 24], "fill_min": [15, 60]}
    base = dict(share=0.10, cap=2_000_000, slots=2)
    rng = np.random.default_rng(0)
    results = []
    for combo in itertools.product(*grid.values()):
        a = argparse.Namespace(**base, **dict(zip(grid, combo)))
        sig = (al <= ref_low * (1 - a.drop)) & (P.lv[:, items] >= a.vol_x * vol_med) & np.isfinite(ref_low)
        tune = summary(simulate(P, items, ref_low, ref_high, sig, a, DAY, split), days_tune)
        results.append({**dict(zip(grid, combo)), "tune": tune})
    results.sort(key=lambda r: -r["tune"]["per_day"])
    best = results[0]
    a = argparse.Namespace(**base, **{k: best[k] for k in grid})
    sig = (al <= ref_low * (1 - a.drop)) & (P.lv[:, items] >= a.vol_x * vol_med) & np.isfinite(ref_low)
    test_trades = simulate(P, items, ref_low, ref_high, sig, a, split, P.T)
    test = summary(test_trades, days_test)
    # Control: same rules at random moments with no dip, matched to the test's alert count.
    n_ctrl = max(1, int(sig[split:].sum()))
    ctrl = np.zeros_like(sig)
    cand = np.argwhere(np.isfinite(ref_low[split:]) & ~sig[split:])
    pick = cand[rng.choice(len(cand), size=min(n_ctrl, len(cand)), replace=False)]
    ctrl[pick[:, 0] + split, pick[:, 1]] = True
    control = summary(simulate(P, items, ref_low, ref_high, ctrl, a, split, P.T), days_test)
    out = {"liquid_items": len(items), "days_tune": days_tune, "days_test": days_test, "base": base,
           "best_params": {k: best[k] for k in grid}, "tune": best["tune"], "test": test, "control_test": control,
           "top10_tune": results[:10],
           "test_worst": sorted(test_trades, key=lambda x: x["profit"])[:5],
           "test_best": sorted(test_trades, key=lambda x: -x["profit"])[:5]}
    print(json.dumps({k: out[k] for k in ("best_params", "tune", "test", "control_test")}, indent=1), flush=True)
    (Path(__file__).resolve().parent / "reports" / a0.out).write_text(json.dumps(out, indent=1, default=float))
    print(f"done ({time.time() - t_start:.0f}s)")


if __name__ == "__main__":
    main()
