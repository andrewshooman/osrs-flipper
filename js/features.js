/* ============================================================
   GE FLIPPER — live feature builder. Mirrors research/features.py exactly
   (same windows, NaN rules, sample std) for the LAST hourly bar of one item,
   so the browser feeds the trained models the same inputs they learned on.
   Parity is checked in research/test_research.py.
   ============================================================ */
(function (OSF) {
  "use strict";
  const NATURE_RUNE_PRICE = 110; // must match research/features.py
  const STEP = 3600;
  const tax = (p) => Math.min(Math.floor(p * 0.02), 5e6);
  const fin = Number.isFinite;

  // ---- window helpers over the last n values (NaN-skipping, like pandas) ----
  const tail = (a, n) => a.slice(Math.max(0, a.length - n));
  const valid = (a) => a.filter(fin);
  function mean(a, minP) { const v = valid(a); return v.length >= minP ? v.reduce((s, x) => s + x, 0) / v.length : NaN; }
  function std(a, minP) {
    const v = valid(a);
    if (v.length < Math.max(minP, 2)) return NaN;
    const m = v.reduce((s, x) => s + x, 0) / v.length;
    return Math.sqrt(v.reduce((s, x) => s + (x - m) ** 2, 0) / (v.length - 1));
  }
  function median(a, minP) {
    const v = valid(a).sort((x, y) => x - y);
    if (v.length < minP || !v.length) return NaN;
    const h = v.length >> 1;
    return v.length % 2 ? v[h] : (v[h - 1] + v[h]) / 2;
  }
  const sum = (a) => a.reduce((s, x) => s + (fin(x) ? x : 0), 0);
  function corr(x, y, minP) {
    const px = [], py = [];
    for (let i = 0; i < x.length; i++) if (fin(x[i]) && fin(y[i])) { px.push(x[i]); py.push(y[i]); }
    if (px.length < minP || px.length < 2) return NaN;
    const mx = px.reduce((s, v) => s + v, 0) / px.length, my = py.reduce((s, v) => s + v, 0) / py.length;
    let sxy = 0, sxx = 0, syy = 0;
    for (let i = 0; i < px.length; i++) { sxy += (px[i] - mx) * (py[i] - my); sxx += (px[i] - mx) ** 2; syy += (py[i] - my) ** 2; }
    return sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : NaN;
  }
  function ffill(a, limit) {
    const out = a.slice();
    let last = NaN, gap = 0;
    for (let i = 0; i < out.length; i++) {
      if (fin(out[i])) { last = out[i]; gap = 0; } else if (fin(last) && ++gap <= limit) out[i] = last;
    }
    return out;
  }

  /** Dense hourly arrays ending at barTs from /timeseries?timestep=1h points (gaps → NaN / 0). */
  function denseSeries(points, barTs, bars = 365) {
    const ah = new Array(bars).fill(NaN), al = new Array(bars).fill(NaN), hv = new Array(bars).fill(0), lv = new Array(bars).fill(0);
    const start = barTs - (bars - 1) * STEP;
    for (const p of points) {
      const i = Math.round((p.timestamp - start) / STEP);
      if (i < 0 || i >= bars) continue;
      if (p.avgHighPrice > 0) ah[i] = p.avgHighPrice;
      if (p.avgLowPrice > 0) al[i] = p.avgLowPrice;
      hv[i] = p.highPriceVolume || 0;
      lv[i] = p.lowPriceVolume || 0;
    }
    return { ah, al, hv, lv, barTs };
  }

  /**
   * Features at the last bar. s: denseSeries(); item: {limit, highalch};
   * ctx: {mkt_ret_1h, mkt_ret_24h}.
   */
  function itemFeatures(s, item, ctx = {}) {
    const n = s.ah.length, last = n - 1;
    const ah = ffill(s.ah, 6), al = ffill(s.al, 6);
    const mid = ah.map((h, i) => (h + al[i]) / 2);
    const lmid = mid.map(Math.log);
    const ret1 = lmid.map((x, i) => (i ? x - lmid[i - 1] : NaN));
    const bid = al.map((x) => x + 1), ask = ah.map((x) => x - 1);
    const net = ask.map((a, i) => a - tax(a) - bid[i]);
    const netRel = net.map((x, i) => x / bid[i]);
    const lim = item.limit || 0;
    const F = {};

    F.spread_rel = (ah[last] - al[last]) / mid[last];
    F.net_margin_rel = netRel[last];
    F.net_margin_gp_log = fin(net[last]) ? Math.sign(net[last]) * Math.log1p(Math.abs(net[last])) : NaN;
    F.log_price = Math.log(mid[last]);
    F.log_limit = Math.log1p(lim);
    F.log_limit_value = Math.log1p(lim * mid[last]);

    for (const h of [1, 6, 24]) {
      const sh = sum(tail(s.hv, h)), sl = sum(tail(s.lv, h));
      F[`log_vol_buy_${h}h`] = Math.log1p(sl / h);
      F[`log_vol_sell_${h}h`] = Math.log1p(sh / h);
      F[`imbalance_${h}h`] = (sh - sl) / (sh + sl + 1);
    }
    const tot = s.hv.map((x, i) => x + s.lv[i]);
    F.vol_surge = tot[last] / (mean(tail(tot, 24), 1) + 1);
    F.log_limit_over_vol = Math.log1p(lim / (sum(tail(s.lv, 4)) + 1));

    for (const h of [1, 3, 6, 24, 72, 168]) F[`ret_${h}h`] = last - h >= 0 ? lmid[last] - lmid[last - h] : NaN;
    for (const h of [24, 168]) {
      const minP = Math.max(3, Math.floor(h / 4));
      F[`vol_${h}h`] = std(tail(ret1, h), minP);
      const w = tail(lmid, h);
      F[`z_${h}h`] = (lmid[last] - mean(w, minP)) / std(w, minP);
      const wm = valid(tail(mid, h));
      const lo = wm.length >= 3 ? Math.min(...wm) : NaN, hi = wm.length >= 3 ? Math.max(...wm) : NaN;
      F[`range_pos_${h}h`] = (mid[last] - lo) / (hi - lo);
    }
    const r168 = tail(ret1, 168), lag168 = tail(ret1.map((x, i) => (i ? ret1[i - 1] : NaN)), 168);
    F.autocorr_168h = corr(r168, lag168, 42);

    const prof = net.map((x) => (fin(x) ? (x > 0 ? 1 : 0) : NaN));
    for (const h of [6, 24]) F[`margin_persist_${h}h`] = mean(tail(prof, h), 2);
    F.margin_median_rel_24h = median(tail(netRel, 24), 3);
    F.margin_vs_median = F.net_margin_rel - F.margin_median_rel_24h;
    F.active_24h = mean(tail(s.ah.map((x, i) => (fin(x) && fin(s.al[i]) ? 1 : 0)), 24), 1);

    F.mkt_ret_1h = ctx.mkt_ret_1h ?? 0;
    F.mkt_ret_24h = ctx.mkt_ret_24h ?? 0;
    const hour = Math.floor(s.barTs / 3600) % 24;
    const dow = (Math.floor(s.barTs / 86400) + 4) % 7;
    const how = (2 * Math.PI * (dow * 24 + hour)) / 168;
    F.how_sin = Math.sin(how); F.how_cos = Math.cos(how);
    F.hour_sin = Math.sin((2 * Math.PI * hour) / 24); F.hour_cos = Math.cos((2 * Math.PI * hour) / 24);
    const alch = (item.highalch || 0) - NATURE_RUNE_PRICE;
    F.alch_floor_rel = alch > 0 ? alch / bid[last] : 0;
    for (const k of Object.keys(F)) if (!fin(F[k])) F[k] = NaN;
    return F;
  }

  OSF.features = { denseSeries, itemFeatures, NATURE_RUNE_PRICE };
})(window.OSF = window.OSF || {});
