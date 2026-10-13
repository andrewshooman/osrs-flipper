"""Causal features and forward-looking labels on a Panel.

Every feature at bar t uses data from bars <= t only. Labels look forward
from t and are only used as training targets, never as model inputs.

    F = features(P)           # dict name -> float32 [T, N]
    Y = flip_labels(P, ...)    # dict name -> float32 [T, N]
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from panel import Panel

NATURE_RUNE_PRICE = 110.0  # used for the alch floor feature when no live price is in the panel


# Bump when the feature set changes, so cached walk-forward models aren't reused.
FEATURE_SET = "v2"  # v2: + trk_fill_7d, trk_exp_7d (item track record)


def _tax(p):
    return np.minimum(np.floor(p * 0.02), 5_000_000)


def _df(a):
    return pd.DataFrame(a, copy=False)


def _bars(P: Panel, hours: float) -> int:
    return max(1, int(round(hours * 3600 / P.step)))


def _cols(P: Panel, a: int, b: int) -> Panel:
    """Panel restricted to items a:b (all bars)."""
    return Panel(P.step, P.ts, P.ids[a:b], P.ah[:, a:b], P.al[:, a:b], P.hv[:, a:b], P.lv[:, a:b],
                 P.limit[a:b], P.highalch[a:b], P.names[a:b])


def market_returns(P: Panel) -> dict[str, np.ndarray]:
    """Median h-hour return across liquid items, per bar (needs all items at once)."""
    ah = _df(P.ah).ffill(limit=_bars(P, 6))
    al = _df(P.al).ffill(limit=_bars(P, 6))
    lmid = np.log((ah + al) / 2)
    liquid = (_df(P.lv).rolling(_bars(P, 24), min_periods=1).mean() > 5).values
    out = {}
    import warnings
    for h in (1, 24):
        r = np.where(liquid, (lmid - lmid.shift(_bars(P, h))).values, np.nan)
        with np.errstate(all="ignore"), warnings.catch_warnings():
            warnings.simplefilter("ignore")
            out[h] = np.nan_to_num(np.nanmedian(r, axis=1)).astype(np.float32)
    return out


def features_chunked(P: Panel, chunk: int = 400) -> dict[str, np.ndarray]:
    """Same result as features(P), computed a few hundred items at a time so a
    full year x thousands of items fits in memory."""
    mkt = market_returns(P)
    out = None
    for a in range(0, P.N, chunk):
        b = min(P.N, a + chunk)
        Fc = features(_cols(P, a, b), mkt=mkt)
        if out is None:
            out = {k: np.empty((P.T, P.N), dtype=np.float32) for k in Fc}
        for k, v in Fc.items():
            out[k][:, a:b] = v
        del Fc
    return out


def labels_chunked(P: Panel, chunk: int = 400, **kw):
    Y = Q = None
    for a in range(0, P.N, chunk):
        b = min(P.N, a + chunk)
        Pc = _cols(P, a, b)
        y, q = flip_labels(Pc, **kw), forward_returns(Pc)
        if Y is None:
            Y = {k: np.empty((P.T, P.N), dtype=np.float32) for k in y}
            Q = {k: np.empty((P.T, P.N), dtype=np.float32) for k in q}
        for k, v in y.items():
            Y[k][:, a:b] = v
        for k, v in q.items():
            Q[k][:, a:b] = v
    return Y, Q


def features(P: Panel, mkt: dict | None = None) -> dict[str, np.ndarray]:
    f32 = np.float32
    ah = _df(P.ah).ffill(limit=_bars(P, 6))
    al = _df(P.al).ffill(limit=_bars(P, 6))
    both_now = np.isfinite(P.ah) & np.isfinite(P.al)
    mid = (ah + al) / 2
    lmid = np.log(mid)
    ret1 = lmid.diff()
    per_hour = 3600 / P.step
    F: dict[str, np.ndarray] = {}

    bid, ask = al + 1, ah - 1
    net = ask - _tax(ask) - bid
    F["spread_rel"] = ((ah - al) / mid).values
    F["net_margin_rel"] = (net / bid).values
    F["net_margin_gp_log"] = np.sign(net.values) * np.log1p(np.abs(net.values))
    F["log_price"] = np.log(mid.values)
    lim = P.limit.astype(np.float64)
    F["log_limit"] = np.broadcast_to(np.log1p(lim), P.ah.shape)
    F["log_limit_value"] = np.log1p(lim * mid.values)

    hv, lv = _df(P.hv), _df(P.lv)
    for h in (1, 6, 24):
        n = _bars(P, h)
        sh, sl = hv.rolling(n, min_periods=1).sum(), lv.rolling(n, min_periods=1).sum()
        F[f"log_vol_buy_{h}h"] = np.log1p(sl.values / h)    # instant sells/hour -> fills your bids
        F[f"log_vol_sell_{h}h"] = np.log1p(sh.values / h)   # instant buys/hour -> fills your asks
        F[f"imbalance_{h}h"] = ((sh - sl) / (sh + sl + 1)).values
    v24 = (hv + lv).rolling(_bars(P, 24), min_periods=1).mean()
    F["vol_surge"] = ((hv + lv) / (v24 + 1)).values
    F["log_limit_over_vol"] = np.log1p(lim / (lv.rolling(_bars(P, 4), min_periods=1).sum().values + 1))

    for h in (1, 3, 6, 24, 72, 168):
        F[f"ret_{h}h"] = (lmid - lmid.shift(_bars(P, h))).values
    for h in (24, 168):
        n = _bars(P, h)
        F[f"vol_{h}h"] = (ret1.rolling(n, min_periods=max(3, n // 4)).std() * np.sqrt(per_hour)).values
        m, s = lmid.rolling(n, min_periods=max(3, n // 4)).mean(), lmid.rolling(n, min_periods=max(3, n // 4)).std()
        F[f"z_{h}h"] = ((lmid - m) / s).values
        F[f"range_pos_{h}h"] = ((mid - mid.rolling(n, min_periods=3).min()) /
                               (mid.rolling(n, min_periods=3).max() - mid.rolling(n, min_periods=3).min())).values
    # Mean reversion: lag-1 autocorrelation of returns over the last week.
    n = _bars(P, 168)
    F["autocorr_168h"] = ret1.rolling(n, min_periods=n // 4).corr(ret1.shift(1)).values

    prof = _df(np.where(np.isfinite(net.values), (net.values > 0).astype(f32), np.nan))
    for h in (6, 24):
        F[f"margin_persist_{h}h"] = prof.rolling(_bars(P, h), min_periods=2).mean().values
    F["margin_median_rel_24h"] = (net / bid).rolling(_bars(P, 24), min_periods=3).median().values
    F["margin_vs_median"] = F["net_margin_rel"] - F["margin_median_rel_24h"]
    F["active_24h"] = _df(both_now.astype(f32)).rolling(_bars(P, 24), min_periods=1).mean().values

    # Market-wide context: median h-hour return across liquid items (the app
    # computes the same thing from two /1h snapshots).
    if mkt is None:
        mkt = market_returns(P)
    for h in (1, 24):
        F[f"mkt_ret_{h}h"] = np.broadcast_to(mkt[h][:, None], P.ah.shape)

    hours = (P.ts // 3600) % 24
    dow = (P.ts // 86400 + 4) % 7  # 1970-01-01 was a Thursday
    how = 2 * np.pi * (dow * 24 + hours) / 168
    F["how_sin"] = np.broadcast_to(np.sin(how)[:, None], P.ah.shape)
    F["how_cos"] = np.broadcast_to(np.cos(how)[:, None], P.ah.shape)
    F["hour_sin"] = np.broadcast_to(np.sin(2 * np.pi * hours / 24)[:, None], P.ah.shape)
    F["hour_cos"] = np.broadcast_to(np.cos(2 * np.pi * hours / 24)[:, None], P.ah.shape)

    alch = (P.highalch - NATURE_RUNE_PRICE)[None, :]
    F["alch_floor_rel"] = np.where(alch > 0, alch / bid.values, 0)
    F.update(track_record(P))

    return {k: np.asarray(v, dtype=f32) for k, v in F.items()}


def track_record(P: Panel, wait_h: float = 2, hold_h: float = 24, days: int = 7) -> dict[str, np.ndarray]:
    """The item's own recent track record: of the standard flips that could be
    started in the last `days` (bid al+1 for `wait_h`, ask ah-1 for `hold_h`),
    how often the bid filled and the average return per attempt (0 when it
    didn't fill). Only flips whose outcome was already known count, so this
    looks back, never ahead."""
    y = flip_labels(P, wait_h=wait_h, hold_h=hold_h)
    attempt = np.isfinite(P.al) & np.isfinite(P.ah)
    filled = np.nan_to_num(y["filled"]) == 1
    fill = np.where(attempt, filled.astype(np.float64), np.nan)
    gain = np.where(attempt, np.where(filled, np.nan_to_num(y["ret"].astype(np.float64)), 0.0), np.nan)
    lag = _bars(P, wait_h) + _bars(P, hold_h) + 1
    n = _bars(P, 24 * days)
    return {"trk_fill_7d": _df(fill).shift(lag).rolling(n, min_periods=6).mean().values,
            "trk_exp_7d": _df(gain).shift(lag).rolling(n, min_periods=6).mean().values}


def flip_labels(P: Panel, wait_h: float = 2, hold_h: float = 24, dump_slip: float = 0.005,
                stop_loss: float | None = None) -> dict[str, np.ndarray]:
    """Outcome of the standard flip started at bar t:
    bid al_t+1, cancel after `wait_h`; once filled, ask ah_t-1 for up to
    `hold_h`, else dump at the instant-sell price. Optionally stop out.

    Returns filled (0/1), ret (net return on cost, NaN if not filled),
    hours (time from t to exit)."""
    T, N = P.ah.shape
    W, H = _bars(P, wait_h), _bars(P, hold_h)
    ah = np.where(np.isfinite(P.ah), P.ah, np.nan).astype(np.float64)
    al = np.where(np.isfinite(P.al), P.al, np.nan).astype(np.float64)
    al_ff = _df(al).ffill().values
    bid, ask = al + 1, ah - 1
    ask_net = ask - _tax(ask)

    fill_at = np.zeros((T, N), dtype=np.int16)              # bars after t when the bid filled (0 = never)
    for w in range(W, 0, -1):
        nxt = np.full((T, N), np.nan)
        nxt[:-w] = al[w:]
        hit = np.isfinite(nxt) & (nxt <= bid)
        fill_at[hit] = w
    filled = (fill_at > 0) & np.isfinite(ask) & np.isfinite(bid)

    ret = np.full((T, N), np.nan)
    hours = np.full((T, N), np.nan)
    for w in range(1, W + 1):
        rows = filled & (fill_at == w)
        if not rows.any():
            continue
        done = np.zeros((T, N), dtype=bool)
        out = np.full((T, N), np.nan)
        exit_bar = np.full((T, N), np.nan)
        for h in range(1, H + 1):
            k = w + h
            if k >= T:
                break
            ah_k = np.full((T, N), np.nan)
            ah_k[:-k] = ah[k:]
            sold = rows & ~done & np.isfinite(ah_k) & (ah_k >= ask)
            out[sold] = ask_net[sold]
            exit_bar[sold] = k
            done |= sold
            if stop_loss is not None:
                al_k = np.full((T, N), np.nan)
                al_k[:-k] = al_ff[k:]
                ask_now = ah_k - 1
                stop = rows & ~done & np.isfinite(ask_now) & (ask_now - _tax(ask_now) < bid * (1 - stop_loss))
                px = al_k * (1 - dump_slip)
                out[stop] = (px - _tax(px))[stop]
                exit_bar[stop] = k
                done |= stop
        k = min(w + H, T - 1)
        al_k = np.full((T, N), np.nan)
        al_k[: T - k] = al_ff[k:]
        px = al_k * (1 - dump_slip)
        left = rows & ~done & np.isfinite(px)
        out[left] = (px - _tax(px))[left]
        exit_bar[left] = k
        ret[rows] = (out[rows] - bid[rows]) / bid[rows]
        hours[rows] = exit_bar[rows] * P.step / 3600
    # The last bars can't see a full outcome.
    ret[-(W + H):] = np.nan
    filled_f = filled.astype(np.float32)
    filled_f[-(W + H):] = np.nan
    return {"filled": filled_f, "ret": ret.astype(np.float32), "hours": hours.astype(np.float32)}


def forward_returns(P: Panel, horizons=(1, 4, 12, 24)) -> dict[str, np.ndarray]:
    """Log change of the achievable sell price (instant-buy) over each horizon."""
    ah = np.log(_df(P.ah).ffill().values)
    out = {}
    for h in horizons:
        k = _bars(P, h)
        f = np.full_like(ah, np.nan)
        f[:-k] = ah[k:] - ah[:-k]
        out[f"fwd_{h}h"] = f.astype(np.float32)
    return out
