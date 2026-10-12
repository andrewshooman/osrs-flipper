/* ============================================================
   GE FLIPPER — gradient-boosted tree evaluator for models trained in
   research/ (LightGBM, exported by research/export_js.py).
   ============================================================ */
(function (OSF) {
  "use strict";

  function predictTree(tr, x) {
    if (!tr.f.length) return tr.v[0];
    let n = 0;
    for (;;) {
      const raw = x[tr.f[n]];
      const m = tr.m[n];
      let left;
      if (raw == null || Number.isNaN(raw)) {
        left = m === 0 ? 0 <= tr.t[n] : tr.d[n] === 1; // "None": NaN is treated as 0
      } else if (m === 1 && raw === 0) {
        left = tr.d[n] === 1;
      } else {
        left = Math.fround(raw) <= tr.t[n]; // features were float32 at training time
      }
      const c = left ? tr.l[n] : tr.r[n];
      if (c < 0) return tr.v[~c];
      n = c;
    }
  }

  function predictBooster(b, x) {
    let s = 0;
    for (const tr of b.trees) s += predictTree(tr, x);
    return b.transform === "sigmoid" ? 1 / (1 + Math.exp(-s)) : s;
  }

  /** model: exported JSON; feats: {name: value}. Returns {boosterName: prediction}. */
  function predict(model, feats) {
    const x = model.features.map((k) => (feats[k] == null ? NaN : feats[k]));
    const out = {};
    for (const [k, b] of Object.entries(model.boosters)) out[k] = predictBooster(b, x);
    return out;
  }

  OSF.gbm = { predict, predictBooster, predictTree };
})(window.OSF = window.OSF || {});
