import sys, time, json
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parent))
from pathlib import Path
from panel import read_days, panel_from_df
from features import features_chunked, labels_chunked
from train import candidate_mask, fit
t = time.time()
P = panel_from_df(read_days("1h"), "1h", min_trade_frac=0.3)
F = features_chunked(P); Y, Q = labels_chunked(P)
m = fit(F, Y, Q, candidate_mask(P, F), sorted(F), rounds=200)
m.meta.update({"trained_from": int(P.ts[0]), "trained_to": int(P.ts[-1]), "panel": [P.T, P.N],
               "note": "full-year v1 (2025-10-12..2026-10-12), production preset validated in walkforward-year-r2"})
m.save(Path(__file__).resolve().parent / "models" / "full-year-v1")
print("saved full-year-v1 rows", m.meta["rows"], f"{time.time()-t:.0f}s", flush=True)
