"""Checks for the research pipeline (synthetic data only): python research/test_research.py"""
import numpy as np

from backtest import STRATEGIES, Config, Offer, Position, Sim, breakeven, tax
from panel import synthetic_panel
from strategy_model import ModelStrategy

fails = []
def ok(c, m):
    if not c:
        fails.append(m)

ok(tax(49) == 0 and tax(50) == 1 and tax(1e9) == 5e6, "tax rules")
for c in [10, 1000, 123456, 4e8]:
    b = breakeven(c)
    ok(b - tax(b) >= c and (b - 1) - tax(b - 1) < c, f"breakeven minimal for {c}")

P = synthetic_panel(T=24 * 20, N=60, seed=3)
for name, S in STRATEGIES.items():
    sim = Sim(P, Config(), S())
    r = sim.run()
    ok(abs(sim.cash - (Config().bankroll + sim.realised)) < 1, f"{name}: cash conserved after liquidation")
    ok(not sim.pos and not sim.offers, f"{name}: flat at the end")
    ok(r["trips"] > 0, f"{name}: trades happened")
    # Never over the buy limit inside any 4h window.
    for i, log in sim.buys_log.items():
        if P.limit[i] > 0:
            for t, _ in log:
                ok(sum(q for tt, q in log if t - sim.window < tt <= t) <= P.limit[i], f"{name}: buy limit item {i}")

# No look-ahead: changing the future must not change decisions made before it.
P2 = synthetic_panel(T=24 * 20, N=60, seed=3)
P2.ah[300:] *= 3; P2.al[300:] *= 0.3
a, b = Sim(P, Config(), STRATEGIES["v0_1"]()), Sim(P2, Config(), STRATEGIES["v0_1"]())
a.run(0, 300); b.run(0, 300)
ok([x["profit"] for x in a.trips] == [x["profit"] for x in b.trips], "no look-ahead")

# Sell priority: with all slots holding buy offers, the least-filled one makes way for a sell.
sim = Sim(P, Config(), STRATEGIES["v0_1"]())
sim.offers = [Offer(i, "buy", 100.0, 10, 0, filled=0 if i == 3 else 5) for i in range(8)]
sim.pos[20] = Position(qty=5, cost=500.0, opened=0)
ok(not sim.place_sell(5, 20, 120.0), "no sell without a free slot")
ModelStrategy._free_slot(ModelStrategy.__new__(ModelStrategy), 5, sim)
ok(len(sim.offers) == 7 and all(o.item != 3 for o in sim.offers), "sell priority cancels the least-filled buy")
ok(sim.place_sell(5, 20, 120.0), "freed slot takes the sell")

# A sim pickled before the click counters existed (the live bot's saved state) still trades.
import pickle
sim = Sim(P, Config(), STRATEGIES["v0_1"]())
del sim.__dict__["n_buys"], sim.__dict__["n_sells"]
old = pickle.loads(pickle.dumps(sim))
ok(old.place_buy(0, 1, float(P.al[0, 1] or 100), 1) and old.n_buys == 1, "old saved sim can still buy")
old.pos[2] = Position(qty=3, cost=300.0, opened=0)
ok(old.place_sell(0, 2, 120.0) and old.n_sells == 1, "old saved sim can still sell")

# Dip alerts (dips.py): a sharp dip that reverts makes money; flat prices give no trade.
import argparse as _ap
import dips
from panel import Panel
T5 = 700
flat = lambda v: np.full((T5, 1), v, dtype=np.float32)
P5 = Panel(300, np.arange(T5) * 300, np.array([1]), flat(104), flat(100), flat(1000), flat(1000),
           np.array([100_000]), np.array([0.0]), ["Thing"])
P5.al[400:412] = 80  # a dump: instant-sells at 80 for an hour
ref_l, ref_h = dips.rolling_median(P5.al, dips.DAY), dips.rolling_median(P5.ah, dips.DAY)
args = _ap.Namespace(share=0.1, cap=1_000_000, slots=2, drop=0.12, vol_x=1.0, margin=0.0, hold_h=6, fill_min=60)
sig = P5.al <= ref_l * (1 - args.drop)
tr = dips.simulate(P5, np.array([0]), ref_l, ref_h, sig, args, dips.DAY, T5)
ok(len(tr) == 1 and tr[0]["buy"] == 80 and tr[0]["profit"] > 0 and tr[0]["dumped"] == 0, "dip bought at 80 and sold at the old price")
ok(not dips.simulate(P5, np.array([0]), ref_l, ref_h, np.zeros_like(sig), args, dips.DAY, T5), "no signal, no trade")
vw = dips.rolling_vwap(P5.al, P5.lv, dips.DAY)
ok(abs(vw[300, 0] - 100) < 1e-3 and np.isnan(vw[10, 0]), "24h volume-weighted reference")

# collect.py re-collects a day that was first collected while still in progress.
import tempfile as _tf
from pathlib import Path as _Path
import collect as _collect
with _tf.TemporaryDirectory() as _d:
    _f = _Path(_d) / "day.parquet"
    _collect.write_day(_f, [(86400 + h * 3600, 1, 10.0, 1, 9.0, 1) for h in range(12)])
    ok(not _collect.day_complete(_f, 86400, 3600), "a half day is collected again")
    _collect.write_day(_f, [(86400 + h * 3600, 1, 10.0, 1, 9.0, 1) for h in range(24)])
    ok(_collect.day_complete(_f, 86400, 3600), "a full day is kept")
    ok(not _collect.day_complete(_Path(_d) / "missing.parquet", 86400, 3600), "a missing file is collected")

print("research checks:", "OK" if not fails else "FAILED\n  " + "\n  ".join(fails))
raise SystemExit(1 if fails else 0)
