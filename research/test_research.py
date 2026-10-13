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

print("research checks:", "OK" if not fails else "FAILED\n  " + "\n  ".join(fails))
raise SystemExit(1 if fails else 0)
