/* ============================================================
   GE TYCOON — small helpers: GE tax, number formatting, statistics
   ============================================================ */
(function (OSF) {
  "use strict";
  const C = OSF.config;

  // ---- GE tax --------------------------------------------------------
  /** Tax paid on ONE item sold at `price`. */
  function taxPerItem(price, itemId) {
    if (!(price > 0) || C.TAX_EXEMPT_IDS.has(itemId)) return 0;
    return Math.min(Math.floor(price * C.TAX_RATE), C.TAX_CAP_PER_ITEM);
  }
  /** What you actually receive per item after tax. */
  function netSellPrice(price, itemId) {
    return price - taxPerItem(price, itemId);
  }
  /** Lowest sell price that recovers `cost` per item after tax. */
  function breakevenSellPrice(cost, itemId) {
    if (!(cost > 0)) return 0;
    // Lower bound on the answer: either the uncapped (2%) or capped regime.
    let p = Math.max(1, Math.floor(Math.min((cost - 1) / (1 - C.TAX_RATE), cost + C.TAX_CAP_PER_ITEM)) - 1);
    while (netSellPrice(p, itemId) < cost) p++;
    return p;
  }

  // ---- formatting ----------------------------------------------------
  function gp(n, opts) {
    if (n == null || !isFinite(n)) return "—";
    const sign = n < 0 ? "-" : (opts && opts.plus && n > 0 ? "+" : "");
    const a = Math.abs(n);
    let s;
    if (a >= 1e9) s = (a / 1e9).toFixed(a >= 1e10 ? 1 : 2) + "B";
    else if (a >= 1e6) s = (a / 1e6).toFixed(a >= 1e7 ? 1 : 2) + "M";
    else if (a >= 1e4) s = (a / 1e3).toFixed(a >= 1e5 ? 0 : 1) + "K";
    else s = Math.round(a).toLocaleString("en-US");
    return sign + s;
  }
  const full = (n) => (n == null || !isFinite(n) ? "—" : Math.round(n).toLocaleString("en-US"));
  const pct = (x, d = 1) => (x == null || !isFinite(x) ? "—" : (x * 100).toFixed(d) + "%");
  function duration(hours) {
    if (hours == null || !isFinite(hours)) return "—";
    if (hours < 1) return Math.max(1, Math.round(hours * 60)) + "m";
    if (hours < 48) return (hours < 10 ? hours.toFixed(1) : Math.round(hours)) + "h";
    return Math.round(hours / 24) + "d";
  }
  function ago(ms) {
    const s = Math.max(0, (Date.now() - ms) / 1000);
    if (s < 60) return Math.round(s) + "s ago";
    if (s < 3600) return Math.round(s / 60) + "m ago";
    if (s < 86400) return (s / 3600).toFixed(1) + "h ago";
    return Math.round(s / 86400) + "d ago";
  }
  /** Parses "1.5m", "250k", "12,345" → number. */
  function parseGp(str) {
    if (typeof str === "number") return str;
    const m = String(str || "").trim().toLowerCase().replace(/,/g, "").match(/^(-?\d*\.?\d+)\s*([kmb])?$/);
    if (!m) return NaN;
    const mult = { k: 1e3, m: 1e6, b: 1e9 }[m[2]] || 1;
    return Math.round(parseFloat(m[1]) * mult);
  }

  // ---- statistics ----------------------------------------------------
  const sum = (a) => a.reduce((s, x) => s + x, 0);
  const mean = (a) => (a.length ? sum(a) / a.length : NaN);
  function stdev(a) {
    if (a.length < 2) return NaN;
    const m = mean(a);
    return Math.sqrt(sum(a.map((x) => (x - m) ** 2)) / (a.length - 1));
  }
  function median(a) {
    if (!a.length) return NaN;
    const s = [...a].sort((x, y) => x - y), h = s.length >> 1;
    return s.length % 2 ? s[h] : (s[h - 1] + s[h]) / 2;
  }
  /** Least-squares slope of ys against xs. */
  function slope(xs, ys) {
    const n = xs.length;
    if (n < 3) return NaN;
    const mx = mean(xs), my = mean(ys);
    let num = 0, den = 0;
    for (let i = 0; i < n; i++) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) ** 2; }
    return den ? num / den : NaN;
  }
  const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
  const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

  OSF.util = {
    taxPerItem, netSellPrice, breakevenSellPrice,
    gp, full, pct, duration, ago, parseGp,
    sum, mean, stdev, median, slope, clamp, uid,
  };
})(window.OSF = window.OSF || {});
