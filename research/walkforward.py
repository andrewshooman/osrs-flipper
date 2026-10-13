"""Walk-forward evaluation: for each fold, train on everything before it
(with an embargo so labels can't leak), then backtest the fold with the
model and both baselines. Results across many folds are what count — one
lucky week proves nothing.

    python research/walkforward.py --min-train-days 60 --test-days 14 --every-days 28
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np

from backtest import STRATEGIES, Config, Sim
from features import features_chunked, labels_chunked
from panel import load_panel, synthetic_panel
from strategy_model import ModelStrategy
from train import candidate_mask, fit

ROOT = Path(__file__).resolve().parent

# Strategy variants evaluated side by side on the same folds with the same trained model.
LOWRISK = {"kelly_frac": 0.15, "max_item_frac": 0.15, "min_exp_ret": 0.005}
VARIANTS = {
    "model": {},
    "model_lowrisk": LOWRISK,
    "model_lowrisk_guard": {**LOWRISK, "guard": True},
    "model_lowrisk_guard_patient": {**LOWRISK, "guard": True, "buy_wait_h": 6.0},
    "model_patient": {"buy_wait_h": 6.0},
}
# Variants also run at a cautious 5% share of real volume.
AT_5PCT = ["model", "model_lowrisk", "model_lowrisk_guard"]

# Round 3: the production preset as a sporadic player would use it.
PROD = {**LOWRISK, "guard": True, "buy_wait_h": 6.0}
CHECKINS = [12, 17, 23]  # UTC = about 8am, 1pm, 7pm US Eastern
ROUNDS = {
    "r2": (VARIANTS, AT_5PCT),
    "r3": ({
        "prod_hourly": PROD,
        "prod_checkin3": {**PROD, "checkin_hours": CHECKINS},
        "prod_checkin3_cap10": {**PROD, "checkin_hours": CHECKINS, "max_item_frac": 0.10},
        "prod_checkin3_minret8": {**PROD, "checkin_hours": CHECKINS, "min_exp_ret": 0.008},
        "prod_checkin2": {**PROD, "checkin_hours": [12, 23]},
    }, ["prod_checkin3"]),
    # Round 4: same strategies, model retrained on labels that match them (see --wait-h).
    "r4": ({
        "prod_hourly": PROD,
        "prod_checkin3_cap10": {**PROD, "checkin_hours": CHECKINS, "max_item_frac": 0.10},
    }, ["prod_hourly", "prod_checkin3_cap10"]),
    # Round 5: free a slot for sells by cancelling the least-filled buy offer;
    # also try listing above market when the forecast is rising.
    "r5": ({
        "prod_hourly": PROD,
        "prod_hourly_sellprio": {**PROD, "sell_priority": True},
        "prod_checkin3_cap10": {**PROD, "checkin_hours": CHECKINS, "max_item_frac": 0.10},
        "prod_checkin3_cap10_sellprio": {**PROD, "checkin_hours": CHECKINS, "max_item_frac": 0.10,
                                         "sell_priority": True},
        "prod_hourly_sellprio_uplift": {**PROD, "sell_priority": True, "sell_uplift": True},
        "prod_checkin3_cap10_sellprio_uplift": {**PROD, "checkin_hours": CHECKINS, "max_item_frac": 0.10,
                                                "sell_priority": True, "sell_uplift": True},
    }, ["prod_hourly_sellprio", "prod_checkin3_cap10_sellprio"]),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--synthetic", action="store_true")
    ap.add_argument("--min-train-days", type=int, default=60)
    ap.add_argument("--test-days", type=int, default=14)
    ap.add_argument("--every-days", type=int, default=28)
    ap.add_argument("--rounds", type=int, default=200)
    ap.add_argument("--min-trade-frac", type=float, default=0.2)
    ap.add_argument("--share", type=float, default=0.10)
    ap.add_argument("--round", default="r2", choices=sorted(ROUNDS))
    ap.add_argument("--leaves", type=int, default=63, help="LightGBM num_leaves")
    ap.add_argument("--lr", type=float, default=0.05, help="LightGBM learning rate")
    ap.add_argument("--wait-h", type=float, default=2.0, help="label: how long the bid may wait to fill")
    ap.add_argument("--hold-h", type=float, default=24.0, help="label: max hold before dumping")
    ap.add_argument("--before", default=None, help="only use hourly data before this date (YYYY-MM-DD)")
    ap.add_argument("--out", default=str(ROOT / "reports" / "walkforward.json"))
    a = ap.parse_args()

    t0 = time.time()
    if a.synthetic:
        P = synthetic_panel(T=24 * 150)
    else:
        from panel import panel_from_df, read_days
        df = read_days("1h")
        if a.before:
            df = df[df.ts < int(time.mktime(time.strptime(a.before, "%Y-%m-%d"))) - time.timezone]
        P = panel_from_df(df, "1h", min_trade_frac=a.min_trade_frac)
    bars_day = int(86400 // P.step)
    print(f"panel {P.T} bars x {P.N} items", flush=True)
    F = features_chunked(P)
    Y, Q = labels_chunked(P, wait_h=a.wait_h, hold_h=a.hold_h)
    names = sorted(F)
    mask = candidate_mask(P, F)
    embargo = int((2 + 24) * 3600 // P.step) + 1
    print(f"features ready ({time.time() - t0:.0f}s)", flush=True)

    folds = []
    start = a.min_train_days * bars_day
    while start + a.test_days * bars_day <= P.T:
        end = start + a.test_days * bars_day
        tm = mask.copy()
        tm[start - embargo:] = False
        # Trained fold models are cached: variant re-runs only re-simulate.
        lab = "" if (a.wait_h, a.hold_h) == (2.0, 24.0) else f"-w{a.wait_h:g}h{a.hold_h:g}"
        if (a.leaves, a.lr) != (63, 0.05):
            lab += f"-l{a.leaves}lr{a.lr:g}"
        cache = ROOT / "models" / "wf-cache" / f"{a.min_trade_frac}-{a.rounds}{lab}-{int(P.ts[start])}"
        if (cache / "meta.json").exists():
            from train import FlipModel
            model = FlipModel.load(cache)
        else:
            model = fit(F, Y, Q, tm, names, rounds=a.rounds, params={"num_leaves": a.leaves, "learning_rate": a.lr})
            model.save(cache)
        warm = start - 8 * bars_day
        test = P.slice(warm, end)
        Ft = {k: v[warm:end] for k, v in F.items()}
        row = {"from": int(P.ts[start]), "to": int(P.ts[end - 1])}
        runs = [(STRATEGIES["top_margin"](), a.share), (STRATEGIES["v0_1"](), a.share)]
        variants, at5 = ROUNDS[a.round]
        runs += [(ModelStrategy(model, Ft, name=vname, **kw), a.share) for vname, kw in variants.items()]
        # Cautious 5% fill share: does the edge survive?
        runs += [(ModelStrategy(model, Ft, name=f"{v}@5%", **variants[v]), 0.05) for v in at5]
        for S, share in runs:
            sim = Sim(test, Config(share=share), S)
            r = sim.run(start=start - warm)
            res = {k: r[k] for k in ("profit_per_day", "max_drawdown", "win_rate", "trips", "exits")}
            if r["strategy"].startswith("model"):
                # Diagnostics: where did the losses come from, and is profit concentrated?
                prof = np.array([x["profit"] for x in sim.trips]) if sim.trips else np.zeros(0)
                res["worst_trades"] = [{"item": test.names[x["item"]], "qty": x["qty"], "buy": round(x["buy"]),
                                        "sell": x["sell"], "profit": round(x["profit"]), "hold_h": round(x["hold"], 1),
                                        "reason": x["reason"]} for x in sorted(sim.trips, key=lambda x: x["profit"])[:5]]
                res["top3_share_of_profit"] = round(float(np.sort(prof)[-3:].sum() / prof.sum()), 2) if prof.sum() > 0 else None
                eq = np.array(sim.equity)
                res["drawdown_at_day"] = round(float(np.argmax(np.maximum.accumulate(eq) - eq)) / bars_day, 1)
            row[r["strategy"]] = res
        folds.append(row)
        day = time.strftime("%Y-%m-%d", time.gmtime(row["from"]))
        print(f"fold {day}: " + "  ".join(f"{k}={v['profit_per_day']:+,}/d" for k, v in row.items() if isinstance(v, dict))
              + f"  ({time.time() - t0:.0f}s)", flush=True)
        start += a.every_days * bars_day

    summary = {}
    for k in ([k for k in folds[0] if isinstance(folds[0][k], dict)] if folds else []):
        ppd = np.array([f[k]["profit_per_day"] for f in folds])
        summary[k] = {"mean_profit_per_day": int(ppd.mean()) if len(ppd) else None,
                      "median_profit_per_day": int(np.median(ppd)) if len(ppd) else None,
                      "folds_positive": f"{int((ppd > 0).sum())}/{len(ppd)}",
                      "worst_fold": int(ppd.min()) if len(ppd) else None,
                      "max_drawdown": int(max(f[k]["max_drawdown"] for f in folds)) if folds else None}
    out = {"config": vars(a), "panel": [P.T, P.N], "summary": summary, "folds": folds}
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    Path(a.out).write_text(json.dumps(out, indent=1))
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()
