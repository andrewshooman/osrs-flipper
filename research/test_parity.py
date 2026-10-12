"""Browser/Python parity: js/features.js and js/gbm.js must reproduce
research/features.py and LightGBM exactly. Needs node + lightgbm.
    python research/test_parity.py
"""
import json
import subprocess
import tempfile
from pathlib import Path

import lightgbm as lgb
import numpy as np

from export_js import compact_booster
from features import features
from panel import synthetic_panel

JS = Path(__file__).resolve().parent.parent / "js"
tmp = Path(tempfile.mkdtemp())

P = synthetic_panel(T=500, N=30, seed=4)
rng = np.random.default_rng(0)
holes = rng.random(P.ah.shape) < 0.15
P.ah[holes] = np.nan
P.hv[holes] = 0
F = features(P)
names = sorted(F)

cases = []
for _ in range(60):
    t, i = int(rng.integers(200, P.T)), int(rng.integers(0, P.N))
    pts = [{"timestamp": int(P.ts[k]),
            "avgHighPrice": float(P.ah[k, i]) if np.isfinite(P.ah[k, i]) else None,
            "avgLowPrice": float(P.al[k, i]) if np.isfinite(P.al[k, i]) else None,
            "highPriceVolume": int(P.hv[k, i]), "lowPriceVolume": int(P.lv[k, i])}
           for k in range(max(0, t - 364), t + 1)
           if np.isfinite(P.ah[k, i]) or np.isfinite(P.al[k, i]) or P.hv[k, i] or P.lv[k, i]]
    cases.append({"pts": pts, "barTs": int(P.ts[t]), "item": {"limit": int(P.limit[i]), "highalch": float(P.highalch[i])},
                  "mkt": {"mkt_ret_1h": float(F["mkt_ret_1h"][t, i]), "mkt_ret_24h": float(F["mkt_ret_24h"][t, i])},
                  "py": {k: (float(F[k][t, i]) if np.isfinite(F[k][t, i]) else None) for k in names}})

# A small model on the same features.
X = np.column_stack([F[k].reshape(-1) for k in names]).astype(np.float32)
y = (np.nan_to_num(F["ret_1h"].reshape(-1)) > 0).astype(float)
b = lgb.train({"objective": "binary", "num_leaves": 15, "verbose": -1}, lgb.Dataset(X, y), 30)
Xc = np.array([[np.nan if c["py"][k] is None else c["py"][k] for k in names] for c in cases], dtype=np.float32)
model = {"features": names, "boosters": {"p": compact_booster(b.dump_model())}}
(tmp / "cases.json").write_text(json.dumps({"cases": cases, "pred": b.predict(Xc).tolist(), "model": model}))

js = f"""
global.window = {{}};
require({json.dumps(str(JS / "features.js"))}); require({json.dumps(str(JS / "gbm.js"))});
const d = require({json.dumps(str(tmp / "cases.json"))});
const bad = {{}}; let worst = 0;
d.cases.forEach((c, j) => {{
  const f = window.OSF.features.itemFeatures(window.OSF.features.denseSeries(c.pts, c.barTs), c.item, c.mkt);
  for (const [k, v] of Object.entries(c.py)) {{
    const x = f[k];
    const ok = (v === null && Number.isNaN(x)) || (v !== null && Number.isFinite(x) && Math.abs(x - v) <= 1e-4 * Math.max(1, Math.abs(v)));
    if (!ok) bad[k] = (bad[k] || 0) + 1;
  }}
  worst = Math.max(worst, Math.abs(window.OSF.gbm.predict(d.model, f).p - d.pred[j]));
}});
console.log(JSON.stringify({{ bad, worst }}));
"""
out = json.loads(subprocess.run(["node", "-e", js], capture_output=True, text=True, check=True).stdout)
ok = not out["bad"] and out["worst"] < 1e-5
print("parity:", "OK" if ok else f"FAILED {out}")
raise SystemExit(0 if ok else 1)
