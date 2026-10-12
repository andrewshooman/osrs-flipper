"""Market panel: dense [time x item] arrays built from collected snapshots.

    P = load_panel("1h")            # from $OSF_DATA/1h/*.parquet
    P = synthetic_panel(...)        # for tests / pipeline checks

P.ah, P.al  float32 [T, N]  instant-buy / instant-sell average price (NaN = no trades)
P.hv, P.lv  float32 [T, N]  instant-buy / instant-sell volume (0 = none)
P.ts        int64   [T]     bucket start (unix seconds)
P.ids       int32   [N]     item ids;  P.limit [N] buy limits (0 = unknown);  P.highalch [N]
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path

import numpy as np

DATA = Path(os.environ.get("OSF_DATA", "/home/user/osrs-data"))


@dataclass
class Panel:
    step: int
    ts: np.ndarray
    ids: np.ndarray
    ah: np.ndarray
    al: np.ndarray
    hv: np.ndarray
    lv: np.ndarray
    limit: np.ndarray
    highalch: np.ndarray
    names: list

    @property
    def T(self) -> int:
        return len(self.ts)

    @property
    def N(self) -> int:
        return len(self.ids)

    def slice(self, a: int, b: int) -> "Panel":
        return Panel(self.step, self.ts[a:b], self.ids, self.ah[a:b], self.al[a:b], self.hv[a:b], self.lv[a:b],
                     self.limit, self.highalch, self.names)


def read_days(step: str = "1h", days: int | None = None, since: str | None = None):
    import pandas as pd

    files = sorted((DATA / step).glob("*.parquet"))
    if since:
        files = [f for f in files if f.stem >= since]
    if days:
        files = files[-days:]
    if not files:
        raise FileNotFoundError(f"no data in {DATA / step} - run research/collect.py first")
    return pd.concat([pd.read_parquet(f) for f in files], ignore_index=True)


def load_panel(step: str = "1h", days: int | None = None, min_trade_frac: float = 0.05, since: str | None = None) -> Panel:
    return panel_from_df(read_days(step, days, since), step, min_trade_frac=min_trade_frac)


def panel_from_df(df, step: str = "1h", ids=None, ts_from: int | None = None, ts_to: int | None = None,
                  min_trade_frac: float = 0.05) -> Panel:
    """Dense panel from snapshot rows. Pass `ids` (and a ts range) to get a
    panel whose item order and time index are fixed — the live runner needs
    that so positions keep their indices as the panel grows."""
    sec = {"5m": 300, "1h": 3600}[step]
    t0 = int(df.ts.min()) if ts_from is None else ts_from
    t1 = int(df.ts.max()) if ts_to is None else ts_to
    ts = np.arange(t0, t1 + sec, sec, dtype=np.int64)
    df = df[(df.ts >= t0) & (df.ts <= t1)]
    if ids is None:
        # Keep items that trade in a reasonable share of buckets.
        counts = df.groupby("id").size()
        ids = counts[counts >= min_trade_frac * len(ts)].index.values
    ids = np.sort(np.asarray(ids).astype(np.int32))
    df = df[df.id.isin(ids)]
    ti = ((df.ts.values - ts[0]) // sec).astype(np.int64)
    ii = np.searchsorted(ids, df.id.values)
    shape = (len(ts), len(ids))

    def dense(col, fill):
        a = np.full(shape, fill, dtype=np.float32)
        a[ti, ii] = df[col].values
        return a

    meta = {}
    mp = DATA / "mapping.json"
    if mp.exists():
        meta = {m["id"]: m for m in json.loads(mp.read_text())}
    limit = np.array([meta.get(int(i), {}).get("limit", 0) or 0 for i in ids], dtype=np.int64)
    highalch = np.array([meta.get(int(i), {}).get("highalch", 0) or 0 for i in ids], dtype=np.float64)
    names = [meta.get(int(i), {}).get("name", str(i)) for i in ids]
    ah, al = dense("ah", np.nan), dense("al", np.nan)
    ah[ah <= 0] = np.nan
    al[al <= 0] = np.nan
    return Panel(sec, ts, ids, ah, al, dense("hv", 0), dense("lv", 0), limit, highalch, names)


def synthetic_panel(T: int = 24 * 60, N: int = 120, seed: int = 0, step: int = 3600) -> Panel:
    """Random-walk / mean-reverting items with spreads, volume and the odd crash.
    Only for exercising the pipeline - says nothing about the real market."""
    rng = np.random.default_rng(seed)
    price0 = np.exp(rng.uniform(np.log(50), np.log(2e6), N))
    sigma = rng.uniform(0.002, 0.03, N)
    revert = rng.uniform(0, 0.2, N) * (rng.random(N) < 0.5)
    spread = rng.uniform(0.005, 0.06, N)
    vol = np.exp(rng.uniform(np.log(5), np.log(5000), N))
    logp = np.zeros((T, N))
    x = np.zeros(N)
    for t in range(1, T):
        x = x * (1 - revert) + rng.normal(0, sigma)
        crash = rng.random(N) < 0.0005
        x[crash] -= rng.uniform(0.1, 0.4, crash.sum())
        logp[t] = x
    mid = price0 * np.exp(logp)
    hour = (np.arange(T) * step // 3600) % 24
    season = 1 + 0.5 * np.sin(2 * np.pi * hour / 24)[:, None]
    s = spread * np.exp(rng.normal(0, 0.3, (T, N)))
    ah = (mid * (1 + s / 2)).astype(np.float32)
    al = (mid * (1 - s / 2)).astype(np.float32)
    hv = rng.poisson(vol * season).astype(np.float32)
    lv = rng.poisson(vol * season).astype(np.float32)
    ah[hv == 0] = np.nan
    al[lv == 0] = np.nan
    limit = rng.choice([100, 1000, 5000, 10000, 25000], N).astype(np.int64)
    ts = 1_700_000_000 // step * step + np.arange(T, dtype=np.int64) * step
    return Panel(step, ts, np.arange(1, N + 1, dtype=np.int32), ah, al, hv, lv, limit,
                 (price0 * rng.uniform(0.2, 1.1, N)).round(), [f"item{i}" for i in range(1, N + 1)])
