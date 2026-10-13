#!/usr/bin/env node
/* ============================================================
   Parity fixtures for the plugin's Java engine (com.geflipper.engine), produced
   by running the web app's own JS modules (loaded as tests/run.js does) on
   seeded synthetic inputs. The JUnit tests replay every case through the Java
   port and require the same numbers and strings.

     TZ=UTC node plugin/tools/make-fixtures.js           # (re)write the fixtures
     TZ=UTC node plugin/tools/make-fixtures.js --check   # exit 1 if they drifted from the JS

   Output: plugin/src/test/resources/com/geflipper/fixtures/*.json. Non-finite
   numbers and -0 are written as the strings "NaN", "Infinity", "-Infinity", "-0".
   ============================================================ */
"use strict";
process.env.TZ = "UTC"; // stats() and dayKey() use the local calendar day
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..", "..");
const OUT = path.join(ROOT, "plugin", "src", "test", "resources", "com", "geflipper", "fixtures");
const CHECK = process.argv.includes("--check");

global.window = {};
const mem = new Map();
global.localStorage = {
  getItem: (k) => (mem.has(k) ? mem.get(k) : null),
  setItem: (k, v) => mem.set(k, String(v)),
  removeItem: (k) => mem.delete(k),
};
["config", "util", "api", "ledger", "features", "gbm", "decide", "engine"].forEach((f) => require(path.join(ROOT, "js", f + ".js")));
require(path.join(ROOT, "models", "flip-model.js"));
const OSF = global.window.OSF;
const MODEL = global.window.OSF_MODEL;
const { util: U, ledger: L, features: FT, gbm: G, decide: D, config: C } = OSF;

if (new Date(0).getTimezoneOffset() !== 0 || new Date(Date.UTC(2026, 6, 1)).getHours() !== 0) {
  console.error("make-fixtures: run with TZ=UTC");
  process.exit(1);
}

// ---------------------------------------------------------------- helpers
/** Seeded RNG (mulberry32); one per fixture file so files don't shift each other. */
function rng(seed) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const r = {
    next,
    uni: (lo, hi) => lo + (hi - lo) * next(),
    int: (lo, hi) => lo + Math.floor(next() * (hi - lo + 1)),
    pick: (arr) => arr[Math.floor(next() * arr.length)],
    chance: (p) => next() < p,
    logUni: (lo, hi) => Math.exp(Math.log(lo) + (Math.log(hi) - Math.log(lo)) * next()),
    normal: () => {
      let u = 0;
      while (u === 0) u = next();
      return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * next());
    },
    shuffle: (arr) => {
      for (let i = arr.length - 1; i > 0; i--) {
        const j = Math.floor(next() * (i + 1));
        [arr[i], arr[j]] = [arr[j], arr[i]];
      }
      return arr;
    },
  };
  return r;
}

const enc = (v) => JSON.stringify(v, (k, x) => (typeof x !== "number" ? x : Number.isFinite(x) ? (Object.is(x, -0) ? "-0" : x) : String(x)));
/** Top-level keys one per line, arrays one element per line: small diffs, valid JSON. */
function doc(obj) {
  const parts = Object.entries(obj).map(([k, v]) => (Array.isArray(v) && v.length
    ? `${JSON.stringify(k)}:[\n${v.map(enc).join(",\n")}\n]`
    : `${JSON.stringify(k)}:${enc(v)}`));
  return `{\n${parts.join(",\n")}\n}\n`;
}

const HOUR = 3600;
const BASE_TS = 1791892800; // 2026-10-13 12:00 UTC (a Tuesday)
const pt = (timestamp, avgHighPrice, avgLowPrice, highPriceVolume, lowPriceVolume) =>
  ({ timestamp, avgHighPrice, avgLowPrice, highPriceVolume, lowPriceVolume });
const ptRow = (p) => [p.timestamp, p.avgHighPrice ?? null, p.avgLowPrice ?? null, p.highPriceVolume ?? null, p.lowPriceVolume ?? null];
const nextUp = (x) => {
  const b = new Float64Array([x]), i = new BigInt64Array(b.buffer);
  if (x === 0) return Number.MIN_VALUE;
  i[0] += x > 0 ? 1n : -1n;
  return b[0];
};
const nextDown = (x) => -nextUp(-x);

/**
 * Synthetic hourly history ending `endLag` hours before barTs: a log random walk with a
 * bid/ask spread, volumes, and optional gaps (one side or both, sometimes whole bars
 * missing), nulls, zero prices, jittered and duplicated timestamps.
 */
function makeSeries(r, o) {
  const pts = [];
  let lp = Math.log(o.price);
  let gapH = 0, gapL = 0, gapB = 0, gapDrop = false;
  for (let i = 0; i < o.n; i++) {
    let ts = o.barTs - (o.endLag + o.n - 1 - i) * HOUR;
    if (o.jitter && r.chance(0.3)) ts += r.int(-o.jitter, o.jitter);
    lp += o.sigma * r.normal() + (o.drift || 0);
    if (o.flat) lp = Math.log(o.price);
    const mid = Math.exp(lp);
    const sp = o.flat ? mid * o.spread : mid * o.spread * r.uni(0.4, 1.6);
    let ah = Math.max(1, Math.round(mid + sp / 2)), al = Math.max(1, Math.round(mid - sp / 2));
    if (o.integer === false) { ah = mid + sp / 2; al = Math.max(0.5, mid - sp / 2); }
    let hv = o.vol ? Math.round(o.vol * r.uni(0.1, 1.9)) : 0, lv = o.vol ? Math.round(o.vol * r.uni(0.1, 1.9)) : 0;
    if (o.gapP && !gapH && !gapL && !gapB && r.chance(o.gapP)) {
      const len = r.int(1, o.gapMax || 12), side = r.int(0, 2);
      if (side === 0) gapH = len; else if (side === 1) gapL = len; else { gapB = len; gapDrop = r.chance(0.5); }
    }
    if (gapB) { gapB--; if (gapDrop) continue; ah = null; al = null; hv = 0; lv = 0; }
    if (gapH) { gapH--; ah = null; hv = r.chance(0.5) ? null : 0; }
    if (gapL) { gapL--; al = null; lv = r.chance(0.5) ? null : 0; }
    if (o.onlyHigh) { al = null; lv = null; }
    if (o.onlyLow) { ah = null; hv = null; }
    if (o.zeroP && r.chance(o.zeroP)) { if (r.chance(0.5)) ah = 0; else al = 0; }
    if (o.nullVolP && r.chance(o.nullVolP)) { hv = null; lv = null; }
    pts.push(pt(ts, ah, al, hv, lv));
    if (o.dupP && r.chance(o.dupP)) pts.push(pt(ts + r.int(-600, 600), r.chance(0.5) ? null : ah, al, hv, r.chance(0.5) ? null : lv));
  }
  if (o.extra) {
    pts.push(pt(o.barTs + 2 * HOUR, 999, 900, 5, 5)); // future
    pts.push(pt(o.barTs - 400 * HOUR, 999, 900, 5, 5)); // before the window
    pts.push(pt(o.barTs + 1800, 999, 900, 5, 5)); // rounds to one past the end
    pts.push(pt(o.barTs - 364 * HOUR - 1799, 777, 700, 3, 4)); // rounds to the first bar
  }
  return pts;
}

// ---------------------------------------------------------------- util.json: tax + formatting
function utilFixture() {
  const r = rng(101);
  const taxed = [0, 2, 4151, 561];
  const exempt = [13190, 233, 28824, 8007];
  const tax = [];
  const prices = [0, -5, NaN];
  for (let p = 1; p <= 60; p++) prices.push(p);
  prices.push(99, 100, 101, 149, 150, 151, 999, 1000, 1001, 12345, 49999, 50000, 50001, 249999999, 250000000,
    250000049, 250000050, 250000051, 1e9, 2147483647, 1e12, 2 ** 53 - 1, 49.99, 50.5, 1234.56, 250000049.9, 1e-9);
  for (let i = 0; i < 60; i++) prices.push(Math.round(r.logUni(1, 3e9)));
  for (let i = 0; i < 20; i++) prices.push(r.logUni(1, 3e9));
  for (const p of prices) for (const id of [r.pick(taxed), r.pick(exempt)]) {
    tax.push([p, id, U.taxPerItem(p, id), U.netSellPrice(p, id)]);
  }
  const breakeven = [];
  const costs = [0, -1, NaN, 0.5, 1, 1.5];
  for (let c = 2; c <= 60; c++) costs.push(c);
  costs.push(48.9, 49, 49.5, 50, 51, 98, 99, 100, 1000, 1000.3333333333334, 12345, 7777777, 244999999, 245000000,
    245000001, 250000000, 400000000, 1e9, 2.1e9, 1e12);
  for (let i = 0; i < 60; i++) costs.push(r.chance(0.5) ? Math.round(r.logUni(1, 3e9)) : r.logUni(1, 3e9));
  for (const c of costs) for (const id of [r.pick(taxed), r.pick(exempt)]) breakeven.push([c, id, U.breakevenSellPrice(c, id)]);

  const nums = [null, NaN, Infinity, -Infinity, 0, -0, 0.4, 0.5, 0.6, -0.4, -0.5, -0.6, 1, -1, 1.5, 2.5, -2.5, 9.5, 999,
    1000, 1234.5, 9999, 9999.4, 9999.5, 9999.6, 10000, -10000, 10049, 10050, 10051, 10149.999, 15000, 99949, 99950,
    99999, 99999.5, 100000, 100499, 100500, 999499, 999500, 999999, 999999.9, 1e6, -1e6, 1004999, 1005000, 1234567,
    9994999, 9995000, 9999999, 1e7, -1e7, 10049999, 10050000, 99950000, 1e8, 999999999, 1e9, -1e9, 1005000000,
    1.234e9, 9995000000, 9999999999, 1e10, 1.05e10, -2.5e10, 123456789012, 2 ** 53, 2 ** 53 + 2, 2 ** 60, 1e21, 1e22,
    1e30, -1e30, 1e100, 0.0123, -0.0123, 0.00005, -0.00005, 0.000049, 0.000051, 0.125, 0.0125, 0.00125, -0.00125,
    1.005, -0.001, 0.015, 0.025, 0.035, 0.045, 0.0045, 0.005, 0.9999, 0.99999, 0.001, 0.0083, 0.0084, 0.00833333,
    0.25, 0.99, 0.999, 0.9917, 0.99166, 1.05, 1.15, 1.25, 1.35, 1.45, 9.94, 9.95, 9.96, 9.99, 10.5, 47.4, 47.5,
    47.6, 48, 59.9, 60, 84, 1000.5, 1e6 + 0.5, 1e-7, 5e-324, -5e-324];
  for (let i = 0; i < 150; i++) {
    const mag = Math.pow(10, r.uni(-6, 12));
    nums.push((r.chance(0.3) ? -1 : 1) * (r.chance(0.3) ? Math.round(mag) : mag));
  }
  const fmt = nums.map((n) => [n, U.gp(n), U.gp(n, { plus: true }), U.full(n), U.pct(n), U.pct(n, 0), U.pct(n, 2), U.duration(n)]);
  const clamp = [];
  for (const x of [NaN, -1, 0, 0.5, 1, 2, -0, Infinity, -Infinity]) clamp.push([x, U.clamp(x, 0, 1)]);
  return doc({ exempt: [...C.TAX_EXEMPT_IDS].sort((a, b) => a - b), tax, breakeven, fmt, clamp });
}

// ---------------------------------------------------------------- features.json + predict.json
function featureCases() {
  const r = rng(202);
  const levels = [2, 3, 7, 15, 48, 110, 260, 999, 1500, 4800, 12000, 52000, 180000, 750000, 2.4e6, 9.9e6, 3.3e7, 1.2e8,
    6e8, 1.5e9, 2.1e9];
  const specs = [
    { name: "clean liquid", gapP: 0 },
    { name: "short gaps", gapP: 0.08, gapMax: 5 },
    { name: "long gaps", gapP: 0.05, gapMax: 20 },
    { name: "gap of 6 at the end", gapP: 0, tailGap: 6 },
    { name: "gap of 7 at the end", gapP: 0, tailGap: 7 },
    { name: "zero volumes", vol: 0 },
    { name: "null volumes", nullVolP: 0.3 },
    { name: "no history", n: 0 },
    { name: "one point", n: 1 },
    { name: "two points", n: 2 },
    { name: "three points", n: 3 },
    { name: "five points", n: 5 },
    { name: "ten points", n: 10 },
    { name: "25 points", n: 25 },
    { name: "50 points", n: 50 },
    { name: "100 points", n: 100 },
    { name: "170 points", n: 170 },
    { name: "more than the window", n: 420 },
    { name: "no limit", limit: 0 },
    { name: "limit missing", limit: undefined },
    { name: "alch floor above bid", alchAbove: true },
    { name: "highalch missing", highalch: undefined },
    { name: "jittered + duplicated timestamps", jitter: 1799, dupP: 0.05, extra: true },
    { name: "stale 3h", endLag: 3 },
    { name: "stale 10h", endLag: 10 },
    { name: "only highs", onlyHigh: true },
    { name: "only lows", onlyLow: true },
    { name: "constant price", flat: true, sigma: 0 },
    { name: "zero prices", zeroP: 0.05 },
    { name: "fractional prices", integer: false },
    { name: "crash", drift: -0.01 },
    { name: "pump", drift: 0.004, n: 40 },
    { name: "wide spread", spread: 0.2 },
    { name: "tight spread", spread: 0.002 },
    { name: "huge volume", vol: 3e6 },
    { name: "ctx missing", ctx: "none" },
    { name: "ctx empty", ctx: "empty" },
    { name: "ctx partial", ctx: "partial" },
    { name: "mixed gaps and nulls", gapP: 0.1, gapMax: 9, nullVolP: 0.1, zeroP: 0.02 },
    { name: "volatile", sigma: 0.06 },
    { name: "sparse trading", gapP: 0.3, gapMax: 8 },
    { name: "item missing", item: "none" },
  ];
  while (specs.length < 56) specs.push({ name: `random ${specs.length}`, gapP: r.pick([0, 0.02, 0.1]), gapMax: r.int(2, 14) });
  return specs.map((sp, k) => {
    const price = levels[k % levels.length] * r.uni(0.9, 1.1);
    const barTs = BASE_TS + r.int(-24 * 14, 24 * 14) * HOUR;
    const o = {
      barTs, price, n: sp.n ?? 365, endLag: sp.endLag ?? 0, sigma: sp.sigma ?? r.uni(0.002, 0.03), drift: sp.drift,
      spread: sp.spread ?? r.uni(0.004, 0.08), vol: sp.vol ?? Math.round(r.logUni(1, 2e5)), gapP: sp.gapP ?? 0.03,
      gapMax: sp.gapMax, jitter: sp.jitter, dupP: sp.dupP, extra: sp.extra, onlyHigh: sp.onlyHigh, onlyLow: sp.onlyLow,
      flat: sp.flat, zeroP: sp.zeroP, nullVolP: sp.nullVolP, integer: sp.integer,
    };
    let pts = makeSeries(r, o);
    if (sp.tailGap) pts = pts.map((p) => (p.timestamp > barTs - sp.tailGap * HOUR ? pt(p.timestamp, null, null, 0, 0) : p));
    let item = { limit: r.pick([100, 500, 4000, 13000, 25000]), highalch: Math.round(price * r.uni(0, 0.6)) };
    if ("limit" in sp) { if (sp.limit === undefined) delete item.limit; else item.limit = sp.limit; }
    if ("highalch" in sp) delete item.highalch;
    if (sp.alchAbove) item.highalch = Math.min(2 ** 31 - 1, Math.round(price * 1.2 + 200)); // item values are 32-bit in game
    if (sp.item === "none") item = undefined;
    let ctx = { mkt_ret_1h: r.uni(-0.01, 0.01), mkt_ret_24h: r.uni(-0.05, 0.05) };
    if (sp.ctx === "none") ctx = undefined;
    if (sp.ctx === "empty") ctx = {};
    if (sp.ctx === "partial") ctx = { mkt_ret_24h: -0.02 };
    const s = FT.denseSeries(pts, barTs);
    const f = FT.itemFeatures(s, item || {}, ctx);
    const last = s.ah.length - 1;
    return { name: sp.name, barTs, item: item ?? null, ctx: ctx ?? null, points: pts.map(ptRow), features: f,
      bar: { ah: s.ah[last], al: s.al[last], hv: s.hv[last], lv: s.lv[last] } };
  });
}

function featuresFixture(cases) {
  const r = rng(203);
  const dense = [];
  for (let k = 0; k < 8; k++) {
    const bars = r.pick([1, 2, 24, 48]);
    const barTs = BASE_TS + r.int(-100, 100) * HOUR;
    const pts = makeSeries(r, { barTs, price: r.logUni(2, 1e6), n: bars + r.int(-1, 6), endLag: r.int(-2, 2), sigma: 0.01,
      spread: 0.03, vol: 50, gapP: 0.15, gapMax: 4, jitter: 1800, dupP: 0.1, nullVolP: 0.1, zeroP: 0.05 });
    const s = FT.denseSeries(pts, barTs, bars);
    dense.push({ barTs, bars, points: pts.map(ptRow), ah: s.ah, al: s.al, hv: s.hv, lv: s.lv });
  }
  return doc({ keys: Object.keys(cases[0].features), cases, dense });
}

function predictFixture(cases) {
  const r = rng(303);
  const feats = MODEL.features;
  const vecs = cases.map((c) => ({ name: c.name, feats: c.features }));
  vecs.push({ name: "all NaN", feats: Object.fromEntries(feats.map((k) => [k, NaN])) });
  vecs.push({ name: "all zero", feats: Object.fromEntries(feats.map((k) => [k, 0])) });
  vecs.push({ name: "empty", feats: {} });
  vecs.push({ name: "negative zero", feats: Object.fromEntries(feats.map((k) => [k, -0])) });
  vecs.push({ name: "huge", feats: Object.fromEntries(feats.map((k) => [k, 1e300])) });
  vecs.push({ name: "tiny negative", feats: Object.fromEntries(feats.map((k) => [k, -1e-300])) });
  for (let i = 0; i < 20; i++) {
    const base = r.pick(cases).features, f = {};
    for (const k of feats) { const p = r.next(); f[k] = p < 0.25 ? NaN : p < 0.35 ? 0 : p < 0.4 ? undefined : base[k]; }
    for (const k of Object.keys(f)) if (f[k] === undefined) delete f[k];
    vecs.push({ name: `holes ${i}`, feats: f });
  }
  // Thresholds of each feature (LightGBM shares bin edges across trees), probed exactly,
  // one double ulp either side, and around the float32 rounding the evaluator applies.
  const th = feats.map(() => []);
  for (const b of Object.values(MODEL.boosters)) for (const tr of b.trees) tr.f.forEach((fi, n) => th[fi].push(tr.t[n]));
  for (let i = 0; i < 60; i++) {
    const f = {};
    feats.forEach((k, fi) => {
      if (!th[fi].length) { f[k] = 0; return; }
      const t = r.pick(th[fi]);
      const f32 = Math.fround(t);
      const mode = r.int(0, 6);
      f[k] = [t, nextUp(t), nextDown(t), f32, nextUp(f32), nextDown(f32), (f32 + nextUp(Math.fround(nextUp(f32)))) / 2][mode];
    });
    vecs.push({ name: `thresholds ${i}`, feats: f });
  }
  for (const v of vecs) v.out = G.predict(MODEL, v.feats);
  return doc({ boosters: Object.keys(MODEL.boosters), cases: vecs });
}

// ---------------------------------------------------------------- decide.json
/**
 * js/decide.js with the minExpectedGp rule the plugin ships ahead of the web app: after
 * the shrink-to-the-gp-left block, an order expected to make less than the minimum is
 * skipped. Built by patching the module's own source, so everything else is the real JS.
 */
function patchedDecide() {
  let src = fs.readFileSync(path.join(ROOT, "js", "decide.js"), "utf8");
  const edits = [
    ["function buyPlan(items, cap, mktRet24h) {", "function buyPlan(items, cap, mktRet24h, minGp = 0) {"],
    ["      plan.push(r);\n", "      if (r.expected < minGp) continue;\n      plan.push(r);\n"],
  ];
  for (const [a, b] of edits) {
    if (src.split(a).length !== 2) {
      throw new Error(`js/decide.js changed: can't apply the minExpectedGp patch at ${JSON.stringify(a)}` +
        " (if buyPlan now takes the minimum itself, use OSF.decide directly)");
    }
    src = src.replace(a, b);
  }
  const win = { OSF: { util: U } };
  new Function("window", src)(win);
  return win.OSF.decide;
}
const DP = patchedDecide();

function decideFixture(fcases) {
  const r = rng(404);
  const okF = { net_margin_rel: 0.03, log_vol_buy_6h: 3, log_vol_sell_6h: 3, ret_24h: 0.01, z_168h: 0.5, active_24h: 0.9 };
  const eligible = [];
  const lg = Math.log1p(2);
  const probes = {
    net_margin_rel: [0, 1e-9, 0.15, nextUp(0.15), -0.01, NaN, undefined],
    log_vol_buy_6h: [lg, nextUp(lg), 0, NaN, undefined],
    log_vol_sell_6h: [lg, nextUp(lg), NaN],
    ret_24h: [0.25, nextUp(0.25), NaN, -0.5],
    z_168h: [3, nextUp(3), NaN, -10],
    active_24h: [0.5, nextDown(0.5), NaN, 1, undefined],
  };
  eligible.push([okF, D.eligible(okF)]);
  for (const [k, vals] of Object.entries(probes)) for (const v of vals) {
    const f = { ...okF, [k]: v };
    if (v === undefined) delete f[k];
    eligible.push([f, D.eligible(f)]);
  }
  for (const c of fcases) eligible.push([c.features, D.eligible(c.features)]);

  const goodPred = () => ({ fill: r.uni(0.3, 1.2), ret: r.uni(-0.01, 0.05), hours: r.uni(-1, 12), fwd4_q10: r.uni(-0.06, 0),
    fwd4_q50: r.uni(-0.01, 0.02), fwd4_q90: r.uni(0, 0.08) });
  const cand = (id, o = {}) => {
    const price = o.price ?? Math.round(r.logUni(5, 2e7));
    const f = { ...okF, net_margin_rel: r.uni(0.005, 0.1) };
    if (r.chance(0.08)) f.active_24h = 0.3;
    return {
      id, name: `Item ${id}`, limitLeft: o.limitLeft ?? r.pick([Infinity, 0, 5, 100, 5000, 30000]),
      bar: o.bar !== undefined ? o.bar : { ah: price * r.uni(1.01, 1.1), al: price, hv: Math.round(r.logUni(1, 1e5)), lv: Math.round(r.logUni(1, 1e5)) },
      f: o.f ?? f, pred: o.pred ?? goodPred(),
    };
  };
  const sets = [];
  const add = (name, items, deployable, slotsFree, mkt, minExpectedGp = 0) =>
    sets.push({ name, items, deployable, slotsFree, mktRet24h: mkt, minExpectedGp });
  const basePred = { fill: 0.9, ret: 0.02, hours: 3, fwd4_q10: -0.02, fwd4_q50: 0.002, fwd4_q90: 0.03 };
  const twelve = Array.from({ length: 12 }, (_, i) => ({ id: i + 1, name: "it" + i, limitLeft: 5000,
    bar: { ah: 1050, al: 1000, hv: 20000, lv: 20000 }, f: okF, pred: { ...basePred, ret: 0.01 + i * 0.002 } }));
  add("tests/run.js twelve", twelve, 5_500_000, 8, 0);
  add("market falling: half size", twelve, 5_500_000, 8, -0.05);
  add("market at -3%", twelve, 5_500_000, 8, -0.03);
  add("more ideas than slots", Array.from({ length: 20 }, (_, i) => cand(100 + i)), 20_000_000, 3, 0.01);
  const rich = (i, price) => cand(i, { price, limitLeft: Infinity, bar: { ah: price * 1.06, al: price, hv: 1e5, lv: 1e5 },
    pred: { ...basePred, ret: r.uni(0.02, 0.04), fwd4_q10: -0.005, fwd4_q90: 0.01 } });
  add("budget exhaustion", Array.from({ length: 16 }, (_, i) => rich(200 + i, Math.round(r.logUni(2e3, 3e5)))), 3_000_000, 16, 0);
  add("budget exhaustion, falling market", Array.from({ length: 16 }, (_, i) => rich(230 + i, Math.round(r.logUni(2e3, 3e5)))), 3_000_000, 14, -0.04);
  add("tiny budget", Array.from({ length: 6 }, (_, i) => cand(250 + i)), 900, 8, 0);
  add("no slots", Array.from({ length: 5 }, (_, i) => cand(300 + i)), 5e6, 0, 0);
  add("no gp", Array.from({ length: 5 }, (_, i) => cand(320 + i)), 0, 5, 0);
  add("unknown and used-up limits", Array.from({ length: 12 }, (_, i) => cand(400 + i, { limitLeft: [Infinity, 0, 1, 3][i % 4] })), 8e6, 8, 0);
  add("bars missing or unpriced", [
    cand(500, { bar: null }), cand(501, { bar: { ah: 1000, al: 0, hv: 10, lv: 10 } }), cand(502, { bar: { ah: NaN, al: 900, hv: 5000, lv: 5000 } }),
    cand(503, { bar: { ah: 1100, al: NaN, hv: 10, lv: 10 } }), cand(504, { bar: { ah: 1100.5, al: 999.5, hv: 3000, lv: 2500 } }),
    cand(505, { bar: { ah: 10, al: 2.4999, hv: 1e6, lv: 1e6 } }),
  ], 5e6, 8, 0);
  add("NaN predictions", [
    cand(600, { pred: { ...basePred, fill: NaN } }), cand(601, { pred: { ...basePred, ret: NaN } }),
    cand(602, { pred: { ...basePred, hours: NaN } }), cand(603, { pred: { ...basePred, fwd4_q90: NaN } }),
    cand(604, { pred: { ...basePred, fwd4_q10: NaN } }), cand(605, { pred: { fill: 0.8, ret: 0.03 } }),
    cand(606, { pred: { ...basePred, fwd4_q50: NaN } }), cand(607), cand(608), cand(609, { pred: {} }),
    cand(610, { pred: { ...basePred, hours: NaN } }), cand(611),
  ], 6e6, 8, 0);
  add("NaN limit", [cand(650, { limitLeft: NaN }), cand(651), cand(652)], 3e6, 8, 0);
  add("ineligible features", fcases.slice(0, 12).map((c, i) => ({ id: 700 + i, name: c.name, limitLeft: 1000,
    bar: c.bar, f: c.features, pred: G.predict(MODEL, c.features) })), 5e6, 8, 0);
  add("minimum expected gp", twelve, 5_500_000, 8, 0, 6000);
  add("minimum expected gp drops shrunk orders", Array.from({ length: 16 }, (_, i) => rich(800 + i, Math.round(r.logUni(2e3, 3e5)))), 3_000_000, 16, 0, 5000);
  add("minimum expected gp with NaN predictions", [
    cand(850, { pred: { ...basePred, fwd4_q90: NaN } }), cand(851), cand(852), cand(853, { pred: { ...basePred, hours: NaN } }),
  ], 6e6, 8, 0, 2000);
  add("minimum expected gp above every order", Array.from({ length: 6 }, (_, i) => cand(870 + i)), 5e6, 8, 0, 1e12);
  add("negative minimum", Array.from({ length: 6 }, (_, i) => cand(890 + i)), 5e6, 8, 0, -5);
  for (let k = 0; k < 30; k++) {
    const n = r.pick([1, 2, 5, 9, 15, 30, 45]);
    const nanP = r.pick([0, 0, 0.05, 0.3]);
    const items = Array.from({ length: n }, (_, i) => {
      const c = cand(1000 * (k + 1) + i);
      if (r.chance(nanP)) c.pred[r.pick(["fill", "ret", "hours", "fwd4_q10", "fwd4_q90"])] = NaN;
      return c;
    });
    add(`random ${k}`, items, r.pick([0, 1e5, 2e6, 5.5e6, 5e7, 2e9]), r.int(0, 12), r.pick([0, -0.02, -0.04, 0.03]),
      r.pick([0, 0, 0, 500, 20000, 250000]));
  }
  for (const s of sets) {
    const res = DP.buyPlan(s.items, { deployable: s.deployable, slotsFree: s.slotsFree }, s.mktRet24h, s.minExpectedGp);
    if (s.minExpectedGp === 0) { // the patch must not change the app's behaviour without a minimum
      const app = D.buyPlan(s.items, { deployable: s.deployable, slotsFree: s.slotsFree }, s.mktRet24h);
      if (enc(app) !== enc(res)) throw new Error(`patched buyPlan differs from js/decide.js for "${s.name}"`);
    }
    s.out = { ideas: res.ideas, plan: res.plan.map((p) => res.ideas.indexOf(p)), deployUsed: res.deployUsed };
  }

  const hold = [];
  const hcase = (h, bar, pred, itemId) => hold.push({ h, bar, pred, itemId, out: D.holdingAction(h, bar, pred, itemId) });
  const P = basePred;
  hcase({ qty: 100, avgCost: 1000, heldHours: 2 }, null, P, 1);
  hcase({ qty: 100, avgCost: 1000, heldHours: 2 }, { ah: 0, al: 990 }, P, 1);
  hcase({ qty: 100, avgCost: 1000, heldHours: 2 }, { ah: NaN, al: 990 }, P, 1);
  hcase({ qty: 100, avgCost: 1000, heldHours: 48 }, { ah: 1100, al: 1050 }, P, 1);
  hcase({ qty: 100, avgCost: 1000, heldHours: 47.999 }, { ah: 1100, al: 1050 }, P, 1);
  hcase({ qty: 100, avgCost: 1000, heldHours: 300 }, { ah: 1100, al: NaN }, P, 1);
  hcase({ qty: 100, avgCost: 1000, heldHours: 2 }, { ah: 1100, al: 1050 }, P, 1);
  hcase({ qty: 100, avgCost: 1000, heldHours: 2 }, { ah: 1100, al: 1050 }, { ...P, fwd4_q50: -0.01 }, 1);
  hcase({ qty: 100, avgCost: 1000, heldHours: 2 }, { ah: 1100, al: 1050 }, { ...P, fwd4_q50: -0.002 }, 1);
  hcase({ qty: 100, avgCost: 1000, heldHours: 2 }, { ah: 1100, al: 1050 }, { ...P, fwd4_q10: -0.031 }, 1);
  hcase({ qty: 100, avgCost: 1300, heldHours: 2 }, { ah: 1100, al: 1050 }, { ...P, fwd4_q50: -0.01, fwd4_q90: 0.01 }, 1);
  hcase({ qty: 100, avgCost: 1100, heldHours: 2 }, { ah: 1100, al: 1050 }, { ...P, fwd4_q90: 0.08 }, 1);
  hcase({ qty: 100, avgCost: 1100, heldHours: 2 }, { ah: 1100, al: 1050 }, { ...P, fwd4_q90: 0.08 }, 13190);
  hcase({ qty: 3, avgCost: 1099.5, heldHours: 2 }, { ah: 1100.49, al: 1050 }, P, 233);
  hcase({ qty: 2, avgCost: 400e6, heldHours: 5 }, { ah: 380e6, al: 370e6 }, { ...P, fwd4_q90: 0.1 }, 1);
  hcase({ qty: 2, avgCost: 400e6, heldHours: 5 }, { ah: 380e6, al: 370e6 }, { ...P, fwd4_q50: -0.01, fwd4_q90: 0.01 }, 1);
  hcase({ qty: 1, avgCost: 2.1e9, heldHours: 1 }, { ah: 2.2e9, al: 2.15e9 }, P, 1);
  hcase({ qty: 1000, avgCost: 5, heldHours: 1 }, { ah: 7, al: 5 }, P, 2);
  hcase({ qty: 1000, avgCost: 5, heldHours: 1 }, { ah: 7, al: 5 }, {}, 2);
  hcase({ qty: 1000, avgCost: 6.2, heldHours: 1 }, { ah: 7, al: 5 }, { fwd4_q90: 0.02 }, 2);
  for (let i = 0; i < 160; i++) {
    const price = r.logUni(2, 2e9);
    const ah = r.chance(0.7) ? Math.round(price * r.uni(1, 1.08)) : price * r.uni(1, 1.08);
    const bar = r.chance(0.04) ? null : { ah: r.chance(0.04) ? NaN : ah, al: r.chance(0.05) ? NaN : Math.round(price) };
    const avgCost = r.chance(0.5) ? Math.round(price * r.uni(0.9, 1.12)) : price * r.uni(0.9, 1.12);
    const pred = { fwd4_q10: r.uni(-0.08, 0.01), fwd4_q50: r.uni(-0.02, 0.02), fwd4_q90: r.uni(-0.01, 0.1) };
    hcase({ qty: r.int(1, 20000), avgCost, heldHours: r.uni(0, 60) }, bar, pred, r.pick([1, 2, 4151, 13190, 233, 561]));
  }
  return doc({ eligible, buyPlan: sets, holding: hold });
}

// ---------------------------------------------------------------- ledger.json
function ledgerFixture() {
  const r = rng(505);
  const ITEMS = [2, 4151, 13190, 561, 233, 11832];
  const BASE = { 2: 170, 4151: 1_600_000, 13190: 9_000_000, 561: 110, 233: 35, 11832: 22_000_000 };
  const T0 = Date.UTC(2026, 9, 10, 21, 0, 0);
  const cases = [];
  const make = (nTrades, nOffers, items) => {
    const trades = [];
    let ts = T0 + r.int(0, 3) * 36e5;
    for (let i = 0; i < nTrades; i++) {
      if (!r.chance(0.12)) ts += r.int(1, 240) * 60e3 + r.int(0, 59999);
      const itemId = r.pick(items);
      const side = r.chance(0.55) ? "buy" : "sell";
      const t = { id: "t" + i, itemId, side, qty: r.int(1, r.pick([5, 100, 5000])), price: Math.max(1, Math.round(BASE[itemId] * r.uni(0.9, 1.15))), ts };
      if (r.chance(0.3)) t.offerId = "o" + r.int(0, 50);
      if (r.chance(0.1)) t.note = "runelite";
      if (side === "sell" && r.chance(0.08)) t.noTax = true;
      trades.push(t);
    }
    r.shuffle(trades);
    const offers = [];
    for (let j = 0; j < nOffers; j++) {
      const itemId = r.pick(items), qty = r.int(1, 5000);
      const status = r.pick(["open", "open", "done", "cancelled"]);
      const o = { id: "o" + j, itemId, side: r.chance(0.6) ? "buy" : "sell", qty, price: Math.max(1, Math.round(BASE[itemId] * r.uni(0.9, 1.1))),
        placedAt: T0 + r.int(0, 72) * 36e5, filled: status === "done" ? qty : r.int(0, qty), status };
      if (status !== "open") o.closedAt = o.placedAt + r.int(1, 10) * 36e5;
      if (r.chance(0.5)) o.slot = r.int(0, 7);
      offers.push(o);
    }
    return { trades, offers };
  };
  const ledgers = [];
  // tests/run.js "ledger FIFO + tax"
  ledgers.push({ trades: [
    { id: "a", itemId: 1, side: "buy", qty: 100, price: 1000, ts: 1000 },
    { id: "b", itemId: 1, side: "buy", qty: 100, price: 1100, ts: 2000 },
    { id: "c", itemId: 1, side: "sell", qty: 150, price: 1200, ts: 3000 },
    { id: "d", itemId: 2, side: "sell", qty: 5, price: 100, ts: 4000 },
  ], offers: [] });
  // same-time buy and sell: the buy is replayed first
  ledgers.push({ trades: [
    { id: "s", itemId: 2, side: "sell", qty: 10, price: 200, ts: T0 },
    { id: "b", itemId: 2, side: "buy", qty: 10, price: 150, ts: T0 },
    { id: "s2", itemId: 2, side: "sell", qty: 4, price: 210, ts: T0 + 1, noTax: true },
  ], offers: [{ id: "x", itemId: 2, side: "buy", qty: 50, price: 160, placedAt: T0, filled: 10, status: "open" }] });
  ledgers.push({ trades: [], offers: [] });
  for (let k = 0; k < 24; k++) {
    const items = r.shuffle([...ITEMS]).slice(0, r.int(1, ITEMS.length));
    ledgers.push(make(r.pick([1, 3, 10, 40, 80, 150]), r.int(0, 10), items));
  }
  for (const lg of ledgers) {
    const settings = { cashReservePct: r.pick([0, 0.25, 0.5, 0.9]), slots: r.pick([7, 8, 3, 0]) };
    const bankroll = r.pick([11_000_000, 1_000_000, 0, 250_000_000]);
    const times = lg.trades.map((t) => t.ts);
    const last = times.length ? Math.max(...times) : T0;
    const now = r.pick([last + r.int(0, 5) * 36e5, last + 36e5 * r.uni(0, 30), T0 - 1]);
    const ids = [...new Set([...lg.trades.map((t) => t.itemId), ...lg.offers.map((o) => o.itemId), 999])];
    const limits = [];
    for (const itemId of ids) {
      const buys = lg.trades.filter((t) => t.itemId === itemId && t.side === "buy").map((t) => t.ts);
      const probes = [now];
      if (buys.length) {
        const b = r.pick(buys);
        probes.push(b, b + C.BUY_LIMIT_WINDOW_MS - 1, b + C.BUY_LIMIT_WINDOW_MS, b + 5 * 36e5);
      }
      for (const t of probes) {
        const limit = r.pick([0, 100, 5000, 13000]);
        limits.push({ itemId, now: t, limit, usage: L.buyLimitUsage(lg.trades, itemId, t), remaining: L.remainingLimit(lg, itemId, limit, t) });
      }
    }
    const p = L.positions(lg.trades);
    const realNow = Date.now;
    Date.now = () => now;
    const st = L.stats(lg);
    Date.now = realNow;
    cases.push({
      ledger: lg, settings, bankroll, now,
      positions: { holdings: [...p.holdings.values()], closed: p.closed, unmatched: p.unmatched },
      limits,
      openOffers: L.openOffers(lg).map((o) => o.id),
      capital: L.capital(lg, settings, bankroll),
      stats: { profit: st.profit, tax: st.tax, roi: st.roi, flips: st.flips, winRate: st.winRate, avgHoldHours: st.avgHoldHours,
        today: st.today, byItem: st.byItem, byDay: st.byDay },
    });
  }
  return doc({ cases });
}

// ---------------------------------------------------------------- sort.json (V8 Array.prototype.sort)
function sortFixture() {
  const r = rng(606);
  const cases = [];
  const sizes = [0, 1, 2, 3, 5, 10, 31, 32, 33, 63, 64, 65, 100, 128, 200, 257, 500, 1200];
  const gen = (n, kind) => {
    const a = [];
    if (kind === "random") for (let i = 0; i < n; i++) a.push(r.int(0, 50));
    if (kind === "wide") for (let i = 0; i < n; i++) a.push(r.uni(-1e6, 1e6));
    if (kind === "sorted") for (let i = 0; i < n; i++) a.push(i >> 1);
    if (kind === "reversed") for (let i = 0; i < n; i++) a.push(n - (i >> 2));
    if (kind === "runs") { let v = 0; for (let i = 0; i < n; i++) { if (r.chance(0.02)) v = r.int(0, 100); a.push(v += r.int(0, 3)); } }
    if (kind === "interleaved") { const h = n >> 1; for (let i = 0; i < n; i++) a.push(i < h ? i * 2 : (i - h) * 2 + 1); }
    if (kind === "saw") for (let i = 0; i < n; i++) a.push(i % 37);
    return a;
  };
  for (const n of sizes) for (const kind of ["random", "wide", "sorted", "reversed", "runs", "interleaved", "saw"]) {
    for (const nanP of [0, 0.03, 0.3, 0.95]) {
      if (n >= 500 && (nanP === 0.95 || kind === "wide" || kind === "saw" || kind === "sorted")) continue;
      const keys = gen(n, kind).map((k) => (r.chance(nanP) ? NaN : k));
      const desc = r.chance(0.5);
      const idx = keys.map((k, i) => ({ k, i }));
      idx.sort(desc ? (a, b) => b.k - a.k : (a, b) => a.k - b.k);
      cases.push({ desc, keys, perm: idx.map((x) => x.i) });
    }
  }
  return doc({ cases });
}

// ---------------------------------------------------------------- recommend.json (doThisNow end to end)
async function recommendFixture() {
  const r = rng(707);
  const NOW = Date.UTC(2026, 9, 13, 14, 23, 17, 456);
  const barTs = Math.floor(NOW / 1000 / 3600) * 3600 - 3600;
  const mapping = [];
  const series = {};
  const latest = {};
  const levels = [3, 6, 14, 40, 95, 180, 420, 900, 1800, 3500, 7200, 15000, 33000, 70000, 150000, 320000, 700000,
    1.5e6, 3.2e6, 7e6, 1.6e7, 4e7, 1.1e8, 3.5e8, 1.2e9];
  let id = 1;
  for (let k = 0; k < 42; k++, id += r.int(1, 40)) {
    const price = r.pick(levels) * r.uni(0.85, 1.2);
    const item = { id, name: `Item ${id}`, members: r.chance(0.7), limit: r.pick([0, 50, 100, 1000, 5000, 13000, 25000]),
      value: Math.round(price * 0.8), highalch: Math.round(price * r.uni(0.1, 0.7)) };
    if (r.chance(0.1)) delete item.limit;
    if (r.chance(0.05)) delete item.highalch;
    mapping.push(item);
    const n = r.chance(0.85) ? r.int(200, 365) : r.int(1, 200);
    // Most items get the liquid, margin-rich profile the model buys.
    const liquid = r.chance(0.75);
    series[id] = makeSeries(r, { barTs, price, n, endLag: r.chance(0.08) ? r.int(1, 8) : 0, sigma: r.uni(0.002, 0.02),
      spread: liquid ? r.uni(0.02, 0.09) : r.uni(0.002, 0.03), vol: liquid ? Math.round(r.logUni(200, 50000)) : Math.round(r.logUni(1, 50)),
      gapP: r.pick([0, 0, 0.02, 0.1]), gapMax: r.int(2, 10) });
    const lastPt = series[id][series[id].length - 1];
    if (lastPt && lastPt.avgLowPrice > 0 && !r.chance(0.1)) {
      const low = Math.round(lastPt.avgLowPrice * r.pick([1, 1, 1.005, 1.03, 0.95, 1.1, 0.9]));
      latest[id] = { high: Math.round(low * 1.04), highTime: barTs + 3000, low: r.chance(0.05) ? null : low, lowTime: barTs + 3100 };
    }
  }
  const ids = mapping.map((m) => m.id);
  const at = (sid, ts) => (series[sid] || []).find((p) => p.timestamp === ts);
  const snap = (ts, scale = 1) => {
    const out = {};
    for (const sid of ids) {
      const p = at(sid, ts);
      if (!p) continue;
      out[sid] = { avgHighPrice: p.avgHighPrice > 0 ? Math.round(p.avgHighPrice * scale) : null, highPriceVolume: p.highPriceVolume ?? 0,
        avgLowPrice: p.avgLowPrice > 0 ? Math.round(p.avgLowPrice * scale) : null, lowPriceVolume: p.lowPriceVolume ?? 0 };
    }
    out[99999] = { avgHighPrice: 5000, highPriceVolume: 900, avgLowPrice: 4000, lowPriceVolume: 900 }; // not in /mapping
    return out;
  };
  const day24 = {};
  for (const sid of ids) {
    const pts = (series[sid] || []).filter((p) => p.timestamp > barTs - 24 * HOUR);
    if (!pts.length) continue;
    const lv = pts.reduce((s, p) => s + (p.lowPriceVolume || 0), 0), hv = pts.reduce((s, p) => s + (p.highPriceVolume || 0), 0);
    day24[sid] = { avgHighPrice: pts[pts.length - 1].avgHighPrice, highPriceVolume: hv, avgLowPrice: pts[pts.length - 1].avgLowPrice, lowPriceVolume: lv };
  }
  const snapshots = (scale24, scale1 = 1) => ({ [barTs]: snap(barTs), [barTs - HOUR]: snap(barTs - HOUR, scale1), [barTs - 24 * HOUR]: snap(barTs - 24 * HOUR, scale24) });

  // Ledger with holdings and offers in every state the run handles.
  const priced = ids.filter((sid) => at(sid, barTs) && at(sid, barTs).avgHighPrice > 0 && at(sid, barTs).avgLowPrice > 0);
  const [h1, h2, h3, h4, b1, b2, b3, b4] = r.shuffle([...priced]).slice(0, 8);
  const unpriced = ids.find((sid) => !at(sid, barTs) || !(at(sid, barTs).avgHighPrice > 0));
  const nowMs = NOW;
  const tr = (tid, itemId, side, qty, price, ts) => ({ id: tid, itemId, side, qty, price, ts });
  const lowOf = (sid) => Math.round(at(sid, barTs).avgLowPrice);
  const ledger = {
    trades: [
      tr("t1", h1, "buy", 120, lowOf(h1), nowMs - 3 * 36e5),
      tr("t2", h2, "buy", 40, Math.round(lowOf(h2) * 1.08), nowMs - 5 * 36e5),
      tr("t3", h3, "buy", 7, lowOf(h3), nowMs - 50 * 36e5),
      tr("t4", h4, "buy", 300, Math.round(lowOf(h4) * 0.97), nowMs - 2 * 36e5),
      tr("t5", h4, "sell", 100, Math.round(lowOf(h4) * 1.05), nowMs - 36e5),
      tr("t6", 77777, "buy", 10, 5000, nowMs - 4 * 36e5), // not in /mapping, no history
      tr("t7", b1, "buy", 25, lowOf(b1), nowMs - 2 * 36e5),
      tr("t8", b1, "sell", 25, Math.round(lowOf(b1) * 1.04), nowMs - 36e5),
    ].concat(unpriced ? [tr("t9", unpriced, "buy", 3, 100, nowMs - 6 * 36e5)] : []),
    offers: [
      { id: "o1", itemId: h1, side: "sell", qty: 120, price: Math.round(lowOf(h1) * 1.05), placedAt: nowMs - 2 * 36e5, filled: 0, status: "open", slot: 0 },
      { id: "o2", itemId: b2, side: "buy", qty: 100, price: lowOf(b2), placedAt: nowMs - 5 * 36e5, filled: 50, status: "open", slot: 1 },
      { id: "o3", itemId: b3, side: "buy", qty: 200, price: lowOf(b3), placedAt: nowMs - 4 * 36e5, filled: 0, status: "open", slot: 2 },
      { id: "o4", itemId: b4, side: "buy", qty: 10, price: lowOf(b4), placedAt: nowMs - 6 * 36e5, filled: 0, status: "open", slot: 3 },
      { id: "o5", itemId: b1, side: "buy", qty: 25, price: lowOf(b1), placedAt: nowMs - 3 * 36e5, filled: 25, status: "done", closedAt: nowMs - 2 * 36e5 },
      { id: "o6", itemId: h2, side: "buy", qty: 40, price: lowOf(h2), placedAt: nowMs - 7 * 36e5, filled: 0, status: "cancelled", closedAt: nowMs - 6 * 36e5 },
    ],
  };
  const base = { members: true, bankroll: 11_000_000, cashReservePct: 0.5, slots: 7, excludedIds: [], candidates: 300, minExpectedGp: 0 };
  const scenarios = [
    { name: "holdings, offers and a full market", now: NOW, settings: { ...base, slots: 5, excludedIds: [ids[3]] }, ledger,
      snapshots: snapshots(1), avg24h: day24, failing: [ids[5], ids[11]], latestFails: false },
    { name: "fresh ledger, F2P, no /24h, /latest down", now: NOW, settings: { ...base, members: false, bankroll: 50_000_000, candidates: 25 },
      ledger: { trades: [], offers: [] }, snapshots: snapshots(1), avg24h: null, failing: [], latestFails: true },
    { name: "falling market, every slot free", now: NOW, settings: { ...base, slots: 8, cashReservePct: 0.2 },
      ledger: { trades: [], offers: [] }, snapshots: snapshots(1.08, 1.01), avg24h: day24, failing: [ids[0]], latestFails: false },
    { name: "minimum expected gp", now: NOW, settings: { ...base, slots: 8, cashReservePct: 0.2, minExpectedGp: 6000 },
      ledger: { trades: [], offers: [] }, snapshots: snapshots(1.08, 1.01), avg24h: day24, failing: [ids[0]], latestFails: false },
    { name: "small stack, many slots", now: NOW + 36 * 60e3, settings: { ...base, bankroll: 1_500_000 + ledger.trades[0].qty * ledger.trades[0].price + ledger.trades[1].qty * ledger.trades[1].price, cashReservePct: 0, slots: 20 },
      ledger: { trades: ledger.trades.slice(0, 2), offers: [] }, snapshots: snapshots(0.99, 0.995), avg24h: day24, failing: [], latestFails: false },
  ];
  const realNow = Date.now;
  const A = OSF.api;
  const saved = { mapping: A.mapping, snapshot: A.snapshot, avg24h: A.avg24h, timeseries: A.timeseries, latest: A.latest };
  const appBuyPlan = OSF.decide.buyPlan;
  try {
    for (const sc of scenarios) {
      A.mapping = async () => new Map(mapping.map((it) => [it.id, it]));
      A.snapshot = async (step, ts) => { if (step !== "1h" || !sc.snapshots[ts]) throw new Error(`no snapshot ${step} ${ts}`); return sc.snapshots[ts]; };
      A.avg24h = async () => sc.avg24h;
      A.timeseries = async (sid, step) => {
        if (step !== "1h") throw new Error("unexpected step " + step);
        if (sc.failing.includes(sid)) throw new Error("HTTP 500");
        return series[sid] || [];
      };
      A.latest = async () => { if (sc.latestFails) throw new Error("HTTP 503"); return latest; };
      // The plugin's Recommender passes settings.minExpectedGp to buyPlan.
      OSF.decide.buyPlan = (items, cap, mkt) => DP.buyPlan(items, cap, mkt, sc.settings.minExpectedGp);
      Date.now = () => sc.now;
      const state = { mode: "real", settings: { ...C.DEFAULT_SETTINGS, ...sc.settings }, ledgers: { real: sc.ledger, practice: L.emptyLedger() } };
      let progress = 0;
      const res = await OSF.engine.doThisNow(state, { onProgress: () => progress++, candidates: sc.settings.candidates });
      Date.now = realNow;
      sc.out = {
        barTs: res.barTs, ctx: res.ctx, cap: res.cap, at: res.at, buySlots: res.buySlots, progress,
        ideas: res.plan.ideas, plan: res.plan.plan.map((p) => res.plan.ideas.indexOf(p)), deployUsed: res.plan.deployUsed,
        holds: res.holds.map((h) => ({ itemId: h.itemId, name: h.name ?? null, qty: h.qty, avgCost: h.avgCost, cost: h.cost, oldestTs: h.oldestTs,
          heldHours: h.heldHours, act: h.act, listing: h.listing ? h.listing.id : null, ask: h.ask, pnl: h.pnl })),
        makeRoom: res.makeRoom.map((o) => o.id),
      };
    }
  } finally {
    Date.now = realNow;
    Object.assign(A, saved);
    OSF.decide.buyPlan = appBuyPlan;
  }
  const seriesRows = Object.fromEntries(Object.entries(series).map(([k, v]) => [k, v.map(ptRow)]));
  return doc({ mapping, latest, series: seriesRows, scenarios });
}

// ---------------------------------------------------------------- main
(async () => {
  const fcases = featureCases();
  const files = {
    "util.json": utilFixture(),
    "features.json": featuresFixture(fcases),
    "predict.json": predictFixture(fcases),
    "decide.json": decideFixture(fcases),
    "ledger.json": ledgerFixture(),
    "sort.json": sortFixture(),
    "recommend.json": await recommendFixture(),
  };
  let bad = 0, total = 0;
  if (!CHECK) fs.mkdirSync(OUT, { recursive: true });
  for (const [name, text] of Object.entries(files)) {
    const p = path.join(OUT, name);
    total += text.length;
    if (CHECK) {
      const cur = fs.existsSync(p) ? fs.readFileSync(p, "utf8") : null;
      if (cur !== text) { bad++; console.error(`${name} differs from what the JS produces now`); }
    } else {
      fs.writeFileSync(p, text);
      console.log(`wrote ${name} (${(text.length / 1024).toFixed(0)} KB)`);
    }
  }
  if (CHECK) {
    if (bad) { console.error("fixtures are out of date: run TZ=UTC node plugin/tools/make-fixtures.js"); process.exit(1); }
    console.log(`fixtures match the JS (${Object.keys(files).length} files)`);
  } else {
    console.log(`total ${(total / 1024).toFixed(0)} KB`);
  }
})().catch((e) => { console.error(e); process.exit(1); });
