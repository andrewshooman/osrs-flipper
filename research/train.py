"""Train the flip models and evaluate them walk-forward against the baselines.

Models (LightGBM, trained only on rows that pass a candidate screen):
  fill   — P(the standard bid fills within the wait window)
  ret    — E[net return on cost | filled]   (after tax, includes dumps)
  hours  — E[hours from bid to exit | filled]
  fwd_q  — quantiles of the 4h/12h forward change in the achievable sell price
           (drives hold / sell / cut decisions)

    python research/train.py --step 1h                 # real data
    python research/train.py --synthetic               # pipeline check
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np

from features import features, flip_labels, forward_returns
from panel import Panel, load_panel, synthetic_panel

ROOT = Path(__file__).resolve().parent
LGB_PARAMS = dict(learning_rate=0.05, num_leaves=63, min_data_in_leaf=200, feature_fraction=0.8,
                  bagging_fraction=0.8, bagging_freq=1, lambda_l2=1.0, verbose=-1, num_threads=4)


def candidate_mask(P: Panel, F: dict) -> np.ndarray:
    """Rows worth modelling: both sides trading, some volume, a positive post-tax margin."""
    return (np.isfinite(P.ah) & np.isfinite(P.al) & (F["net_margin_rel"] > 0)
            & (F["log_vol_buy_6h"] > np.log1p(2)) & (F["log_vol_sell_6h"] > np.log1p(2)))


def to_rows(F: dict, mask: np.ndarray, names: list[str]):
    t_idx, i_idx = np.nonzero(mask)
    X = np.column_stack([F[k][t_idx, i_idx] for k in names]).astype(np.float32)
    return X, t_idx, i_idx


class FlipModel:
    """Bundle of boosters with a predict() over a whole bar."""

    def __init__(self, names, boosters, meta):
        self.names, self.b, self.meta = names, boosters, meta

    def predict_rows(self, X):
        out = {k: b.predict(X, num_threads=4) for k, b in self.b.items()}
        p = np.clip(out["fill"], 0, 1)
        out["exp_ret"] = p * out["ret"]
        return out

    def save(self, path: Path):
        path.mkdir(parents=True, exist_ok=True)
        for k, b in self.b.items():
            b.save_model(str(path / f"{k}.txt"))
            (path / f"{k}.json").write_text(json.dumps(b.dump_model()))  # for JS inference
        (path / "meta.json").write_text(json.dumps({"features": self.names, **self.meta}, indent=1))


def fit(F, Y, Q, mask, names, rounds=400, seed=0):
    import lightgbm as lgb

    X, t, i = to_rows(F, mask, names)
    rng = np.random.default_rng(seed)
    if len(X) > 3_000_000:
        keep = rng.choice(len(X), 3_000_000, replace=False)
        X, t, i = X[keep], t[keep], i[keep]
    b = {}
    yf = Y["filled"][t, i]
    ok = np.isfinite(yf)
    b["fill"] = lgb.train({**LGB_PARAMS, "objective": "binary", "seed": seed}, lgb.Dataset(X[ok], yf[ok]), rounds)
    yr, yh = Y["ret"][t, i], Y["hours"][t, i]
    ok = np.isfinite(yr)
    # Clip extreme returns so a few crashes/spikes don't dominate the fit.
    b["ret"] = lgb.train({**LGB_PARAMS, "objective": "huber", "alpha": 0.05, "seed": seed},
                         lgb.Dataset(X[ok], np.clip(yr[ok], -0.3, 0.3)), rounds)
    b["hours"] = lgb.train({**LGB_PARAMS, "objective": "regression", "seed": seed}, lgb.Dataset(X[ok], yh[ok]), rounds // 2)
    for h in (4, 12):
        y = Q[f"fwd_{h}h"][t, i]
        ok = np.isfinite(y)
        for q in (0.1, 0.5, 0.9):
            b[f"fwd{h}_q{int(q * 100)}"] = lgb.train({**LGB_PARAMS, "objective": "quantile", "alpha": q, "seed": seed},
                                                     lgb.Dataset(X[ok], np.clip(y[ok], -0.5, 0.5)), rounds // 2)
    return FlipModel(names, b, {"rows": int(len(X))})


def evaluate_rows(model: FlipModel, F, Y, mask, names):
    """How well does the model rank flips? Compare top-decile realised return."""
    X, t, i = to_rows(F, mask, names)
    yr = Y["ret"][t, i]
    yf = Y["filled"][t, i]
    pr = model.predict_rows(X)
    ok = np.isfinite(yr)
    realised = np.where(yf == 1, yr, 0.0)
    okf = np.isfinite(realised) & np.isfinite(yf)
    res = {}
    for name, score in [("model", pr["exp_ret"]), ("margin", F["net_margin_rel"][t, i])]:
        s = score[okf]
        cut = np.quantile(s, 0.9)
        res[f"top10_realised_ret_{name}"] = float(np.nanmean(realised[okf][s >= cut]))
    res["all_realised_ret"] = float(np.nanmean(realised[okf]))
    from numpy import corrcoef
    res["corr_pred_ret"] = float(corrcoef(pr["ret"][ok], yr[ok])[0, 1]) if ok.sum() > 10 else None
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--step", default="1h")
    ap.add_argument("--days", type=int, default=None)
    ap.add_argument("--synthetic", action="store_true")
    ap.add_argument("--test-days", type=int, default=30, help="final out-of-sample window")
    ap.add_argument("--rounds", type=int, default=400)
    ap.add_argument("--out", default=str(ROOT / "models" / "latest"))
    a = ap.parse_args()

    t0 = time.time()
    P = synthetic_panel() if a.synthetic else load_panel(a.step, a.days)
    print(f"panel {P.T} x {P.N} ({time.time() - t0:.0f}s)", flush=True)
    F = features(P)
    Y = flip_labels(P)
    Q = forward_returns(P)
    names = sorted(F)
    mask = candidate_mask(P, F)
    print(f"features {len(names)}, candidate rows {mask.sum():,} ({time.time() - t0:.0f}s)", flush=True)

    # Out-of-sample split with an embargo so labels don't leak across it.
    bars_day = int(86400 // P.step)
    test_start = P.T - a.test_days * bars_day
    embargo = int((2 + 24) * 3600 // P.step) + 1
    train_mask = mask.copy()
    train_mask[test_start - embargo:] = False
    test_mask = mask.copy()
    test_mask[:test_start] = False

    model = fit(F, Y, Q, train_mask, names, rounds=a.rounds)
    print(f"trained on {model.meta['rows']:,} rows ({time.time() - t0:.0f}s)", flush=True)
    rep = {"rows": evaluate_rows(model, F, Y, test_mask, names)}
    print("row-level OOS:", json.dumps(rep["rows"], indent=1), flush=True)

    from backtest import STRATEGIES, Config, Sim
    from strategy_model import ModelStrategy
    test = P.slice(test_start - 24 * 8 * bars_day // 24, P.T)  # a week of warm-up for features
    warm = P.T - test.T
    Ft = {k: v[warm:] for k, v in F.items()}
    rep["backtest"] = []
    for S in [STRATEGIES["top_margin"](), STRATEGIES["v0_1"](), ModelStrategy(model, Ft)]:
        r = Sim(test, Config(), S).run(start=test_start - warm)
        rep["backtest"].append(r)
        print(r, flush=True)
    model.meta["report"] = rep
    model.meta.update({"step": P.step, "trained_until_ts": int(P.ts[test_start - embargo]), "panel": [P.T, P.N]})
    model.save(Path(a.out))
    print(f"saved to {a.out} ({time.time() - t0:.0f}s)")


if __name__ == "__main__":
    main()
