/* ============================================================
   OSRS FLIPPER — test harness (plain Node, no dependencies)
   Run with `node tests/run.js` (or `npm test`).
   Uses small synthetic markets — these test the arithmetic and the model's
   behaviour, not real prices.
   ============================================================ */
global.window = {};
const store = new Map();
global.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};
const path = require("path");
["config", "util", "api", "ledger", "model", "store", "engine", "decide"].forEach((f) => require(path.join(__dirname, "..", "js", f + ".js")));
const OSF = global.window.OSF;
const { util: U, ledger: L, model: M, config: C } = OSF;

let passed = 0, failed = 0;
const fails = [];
function ok(cond, msg) { if (cond) passed++; else { failed++; fails.push(msg); } }
function eq(a, b, msg) { ok(a === b, `${msg} (expected ${b}, got ${a})`); }
async function group(name, fn) {
  try { await fn(); } catch (e) { failed++; fails.push(`${name}: threw ${(e && e.stack) || e}`); }
}

const H = 36e5;
const settings = () => ({ ...C.DEFAULT_SETTINGS });

/* ---------- synthetic market ---------- */
function makeMarket(now = Date.now()) {
  const t = Math.floor(now / 1000);
  const items = new Map([
    [1, { id: 1, name: "Steady Bar", members: true, limit: 10000, highalch: 100, value: 200 }],
    [2, { id: 2, name: "Spiky Gem", members: true, limit: 500, highalch: 10, value: 50 }],
    [3, { id: 3, name: "Dead Item", members: true, limit: 100, highalch: 1, value: 1 }],
    [4, { id: 4, name: "F2P Ore", members: false, limit: 13000, highalch: 30, value: 60 }],
    [561, { id: 561, name: "Nature rune", members: false, limit: 18000, highalch: 108, value: 180 }],
    [13190, { id: 13190, name: "Old school bond", members: false, limit: 100, highalch: 0, value: 1 }],
  ]);
  const latest = {
    1: { high: 1050, highTime: t - 60, low: 1000, lowTime: t - 30 },
    2: { high: 5000, highTime: t - 120, low: 4000, lowTime: t - 90 },
    3: { high: 900, highTime: t - 7200, low: 500, lowTime: t - 7200 }, // stale
    4: { high: 125, highTime: t - 30, low: 122, lowTime: t - 20 },
    561: { high: 110, highTime: t - 10, low: 108, lowTime: t - 10 },
  };
  const m5 = {
    1: { avgHighPrice: 1048, highPriceVolume: 900, avgLowPrice: 1001, lowPriceVolume: 1100 },
    2: { avgHighPrice: 4300, highPriceVolume: 20, avgLowPrice: 4200, lowPriceVolume: 25 },
  };
  const h1 = {
    1: { avgHighPrice: 1049, highPriceVolume: 10000, avgLowPrice: 1001, lowPriceVolume: 12000 },
    2: { avgHighPrice: 4350, highPriceVolume: 300, avgLowPrice: 4150, lowPriceVolume: 280 },
    3: { avgHighPrice: 900, highPriceVolume: 1, avgLowPrice: 500, lowPriceVolume: 1 },
    4: { avgHighPrice: 125, highPriceVolume: 30000, avgLowPrice: 122, lowPriceVolume: 30000 },
    561: { avgHighPrice: 110, highPriceVolume: 90000, avgLowPrice: 108, lowPriceVolume: 90000 },
  };
  return { items, latest, m5, h1, d1: null };
}
function series(n, stepSec, fn, now = Date.now()) {
  const end = Math.floor(now / 1000 / stepSec) * stepSec;
  return Array.from({ length: n }, (_, i) => ({ timestamp: end - (n - i) * stepSec, ...fn(i) }));
}

(async () => {
  await group("tax", () => {
    eq(U.taxPerItem(49), 0, "under 50gp pays no tax");
    eq(U.taxPerItem(50), 1, "50gp pays 1gp");
    eq(U.taxPerItem(1001), 20, "tax rounds down");
    eq(U.taxPerItem(1_000_000_000), 5_000_000, "tax capped at 5M per item");
    eq(U.taxPerItem(10_000_000, 13190), 0, "bonds are exempt");
    eq(U.taxPerItem(500, 8007), 0, "teleport tablets are exempt");
    eq(U.taxPerItem(150, 379), 0, "lobsters are exempt");
    eq(U.netSellPrice(1000), 980, "net after 2%");
    for (const cost of [1, 49, 50, 99, 1000, 12345, 7_777_777, 400_000_000]) {
      const p = U.breakevenSellPrice(cost);
      ok(U.netSellPrice(p) >= cost && U.netSellPrice(p - 1) < cost, `breakeven is minimal for cost ${cost} (got ${p})`);
    }
  });

  await group("formatting", () => {
    eq(U.parseGp("1.5m"), 1_500_000, "parse 1.5m");
    eq(U.parseGp("250k"), 250_000, "parse 250k");
    eq(U.parseGp("12,345"), 12345, "parse commas");
    ok(isNaN(U.parseGp("abc")), "parse garbage → NaN");
    eq(U.gp(11_000_000), "11.0M", "format 11M");
    eq(U.gp(-2500), "-2,500", "format negative");
  });

  await group("ledger FIFO + tax", () => {
    const lg = L.emptyLedger();
    L.addTrade(lg, { itemId: 1, side: "buy", qty: 100, price: 1000, ts: 1000 });
    L.addTrade(lg, { itemId: 1, side: "buy", qty: 100, price: 1100, ts: 2000 });
    L.addTrade(lg, { itemId: 1, side: "sell", qty: 150, price: 1200, ts: 3000 });
    const { holdings, closed } = L.positions(lg.trades);
    eq(closed.length, 1, "one close");
    eq(closed[0].cost, 100 * 1000 + 50 * 1100, "FIFO cost");
    eq(closed[0].tax, 24 * 150, "tax per item × qty");
    eq(closed[0].profit, 150 * 1200 - 24 * 150 - 155000, "profit after tax");
    eq(holdings.get(1).qty, 50, "50 left");
    eq(holdings.get(1).avgCost, 1100, "remaining lot cost");
    L.addTrade(lg, { itemId: 2, side: "sell", qty: 5, price: 100, ts: 4000 });
    eq(L.positions(lg.trades).unmatched.length, 1, "sell without buy is reported as unmatched");
  });

  await group("buy limits", () => {
    const lg = L.emptyLedger();
    const t0 = 10 * H;
    L.addTrade(lg, { itemId: 1, side: "buy", qty: 300, price: 1, ts: t0 });
    L.addTrade(lg, { itemId: 1, side: "buy", qty: 200, price: 1, ts: t0 + 1 * H });
    eq(L.buyLimitUsage(lg.trades, 1, t0 + 2 * H).used, 500, "both buys inside the window");
    eq(L.buyLimitUsage(lg.trades, 1, t0 + 4 * H).used, 0, "window expires 4h after first buy");
    L.addTrade(lg, { itemId: 1, side: "buy", qty: 50, price: 1, ts: t0 + 5 * H });
    eq(L.buyLimitUsage(lg.trades, 1, t0 + 5.5 * H).used, 50, "new window opens");
    L.placeOffer(lg, { itemId: 1, side: "buy", qty: 100, price: 1 });
    eq(L.remainingLimit(lg, 1, 1000, t0 + 5.5 * H), 850, "open buy offers count toward the limit");
  });

  await group("capital + reserve", () => {
    const s = settings();
    const lg = L.emptyLedger();
    let cap = L.capital(lg, s, 11_000_000);
    eq(cap.deployable, 5_500_000, "half kept in reserve");
    L.addTrade(lg, { itemId: 1, side: "buy", qty: 1000, price: 1000, ts: Date.now() });
    L.placeOffer(lg, { itemId: 2, side: "buy", qty: 100, price: 5000 });
    cap = L.capital(lg, s, 11_000_000);
    eq(cap.deployable, 5_500_000 - 1_000_000 - 500_000, "holdings and buy offers use deployable gp");
    eq(cap.slotsFree, 6, "one of the 7 tool slots in use");
  });

  await group("offers + fills", () => {
    const lg = L.emptyLedger();
    const o = L.placeOffer(lg, { itemId: 1, side: "buy", qty: 10, price: 100 });
    L.fillOffer(lg, o.id, 4);
    eq(o.filled, 4, "partial fill");
    eq(o.status, "open", "still open");
    L.fillOffer(lg, o.id, 99);
    eq(o.filled, 10, "fill capped at offer qty");
    eq(o.status, "done", "completed");
    eq(lg.trades.length, 2, "each fill is a trade");
  });

  await group("CSV round trip", () => {
    const lg = L.emptyLedger();
    L.addTrade(lg, { itemId: 1, side: "buy", qty: 5, price: 10, ts: Date.parse("2026-01-01T00:00:00Z") });
    L.addTrade(lg, { itemId: 1, side: "sell", qty: 5, price: 12, ts: Date.parse("2026-01-01T01:00:00Z") });
    const back = L.fromCSV(L.toCSV(lg, new Map([[1, { name: 'Bar, "big"' }]])));
    eq(back.length, 2, "two trades back");
    eq(back[1].price, 12, "price preserved");
    eq(back[0].ts, Date.parse("2026-01-01T00:00:00Z"), "time preserved");
  });

  await group("model screen", () => {
    const mk = makeMarket();
    const s = settings();
    const { candidates } = M.screen(mk, s, { deployable: 5_500_000 });
    const ids = candidates.map((c) => c.id);
    ok(ids.includes(1), "liquid profitable item passes");
    ok(!ids.includes(3), "stale item rejected");
    ok(!ids.includes(4), "item whose margin doesn't beat tax is rejected");
    const steady = candidates.find((c) => c.id === 1);
    eq(steady.quote.buy, 1002, "bid uses the less favourable of latest/average low, +1");
    eq(steady.quote.sell, 1047, "ask uses the less favourable of latest/average high, −1");
    eq(steady.margin, 1047 - 20 - 1002, "margin after tax");
    const f2p = M.screen(mk, { ...s, members: false }, { deployable: 5_500_000 }).candidates;
    ok(!f2p.some((c) => mk.items.get(c.id).members), "F2P mode excludes members items");
    const ex = M.screen(mk, { ...s, excludedIds: [1] }, { deployable: 5_500_000 }).candidates;
    ok(!ex.some((c) => c.id === 1), "excluded items are skipped");
  });

  await group("model history + estimate", () => {
    const mk = makeMarket();
    const s = settings();
    const cand = M.screen(mk, s, { deployable: 5_500_000 }).candidates.find((c) => c.id === 1);
    // Stable item: margin present in every 5m bucket, flat hourly price.
    const ts5m = series(100, 300, () => ({ avgHighPrice: 1048, avgLowPrice: 1001, highPriceVolume: 800, lowPriceVolume: 900 }));
    const ts1h = series(200, 3600, (i) => ({ avgHighPrice: 1048 + (i % 2), avgLowPrice: 1001 + (i % 3), highPriceVolume: 9000, lowPriceVolume: 9000 }));
    const hist = M.historyFeatures(1, ts5m, ts1h);
    eq(hist.persistence, 1, "margin persisted in every bucket");
    ok(Math.abs(hist.trend7d) < 1e-4, "flat price → ~zero trend");
    ok(hist.sigmaHr > 0 && hist.sigmaHr < 0.01, "low volatility");
    const r = M.estimate(cand, { settings: s, mk, hist, capitalCap: 1_375_000 });
    ok(r && r.qty > 0, "produces a pick");
    ok(r.qty <= 10000, "respects buy limit");
    ok(r.cost <= 1_375_000, "respects capital cap");
    ok(r.expected > 0 && r.expected < r.gross, "expected profit is discounted from gross");
    ok(["A", "B"].includes(r.grade), `stable liquid item grades well (got ${r.grade})`);

    // Same item but crashing and spiking margin → much worse.
    const crash1h = series(200, 3600, (i) => { const p = 2000 * Math.exp(-0.004 * i); return { avgHighPrice: p * 1.02, avgLowPrice: p, highPriceVolume: 9000, lowPriceVolume: 9000 }; });
    const flaky5m = series(100, 300, (i) => (i % 4 ? { avgHighPrice: 1010, avgLowPrice: 1005, highPriceVolume: 800, lowPriceVolume: 900 } : { avgHighPrice: 1048, avgLowPrice: 1001, highPriceVolume: 800, lowPriceVolume: 900 }));
    const bad = M.estimate(cand, { settings: s, mk, hist: M.historyFeatures(1, flaky5m, crash1h), capitalCap: 1_375_000 });
    ok(!bad || bad.perHour < r.perHour, "falling item with unreliable margin ranks lower");
    if (bad) ok(bad.flags.some((f) => f.k === "down"), "downtrend flagged");
    if (bad) ok(bad.flags.some((f) => f.k === "spike"), "margin spike flagged");

    const small = M.estimate(cand, { settings: s, mk, hist, capitalCap: 10_000 });
    eq(small.qty, Math.floor(10_000 / cand.quote.buy), "re-estimate shrinks to a smaller budget");
  });

  await group("allocate", () => {
    const mk = makeMarket();
    const s = settings();
    const recs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((i) => ({ id: i, buy: 1000, qty: 2000, cost: 2_000_000, expected: 10000 * i, perHour: 1000 * i }));
    const re = (r, budget) => ({ ...r, qty: Math.floor(budget / r.buy), cost: Math.floor(budget / r.buy) * r.buy, expected: r.expected * budget / r.cost });
    const cap = { deployable: 5_500_000, slotsFree: 8 };
    const a = M.allocate(recs, cap, s, re);
    ok(a.plan.length <= 8, "never more picks than free slots");
    ok(a.invested <= 5_500_000, "never more than deployable");
    ok(a.plan.every((p) => p.cost <= 5_500_000 * s.maxPerItemPct + 1), "per-item cap respected");
    eq(a.plan[0].id, 10, "best gp/hour first");
    // When gp is the scarce resource, a cheap efficient pick beats a slightly
    // higher-gp/h pick that eats a quarter of the stack.
    const big = { id: 100, buy: 1000, qty: 250, cost: 250_000, expected: 10_000, perHour: 10_000 };
    const cheap = { id: 101, buy: 1000, qty: 50, cost: 50_000, expected: 6_000, perHour: 6_000 };
    const b = M.allocate([big, cheap], { deployable: 1_000_000, slotsFree: 8 }, s, re);
    eq(b.plan[0].id, 101, "capital-efficient pick ranks first");
    eq(b.plan.length, 2, "both still fit");
    const skip = M.allocate(recs, cap, s, re, new Set([10]));
    ok(!skip.plan.some((p) => p.id === 10), "items with open offers are skipped");
    void mk;
  });

  await group("holding advice", () => {
    const mk = makeMarket();
    const s = settings();
    const now = Date.now();
    const item = mk.items.get(1);
    const winner = M.adviseHolding({ itemId: 1, qty: 100, avgCost: 900, oldestTs: now - H }, mk, s, item, { now });
    eq(winner.action, "sell", "profitable holding → sell");
    const loser = M.adviseHolding({ itemId: 1, qty: 100, avgCost: 1200, oldestTs: now - H }, mk, s, item, { now });
    eq(loser.action, "cut", "stop-loss");
    const old = M.adviseHolding({ itemId: 1, qty: 100, avgCost: 1040, oldestTs: now - 20 * H }, mk, s, item, { now });
    eq(old.action, "time", "time stop after max hold");
    const meh = M.adviseHolding({ itemId: 1, qty: 100, avgCost: 1030, oldestTs: now - H }, mk, s, item, { now });
    eq(meh.action, "hold", "small loss inside the stop → hold");
    const alchy = M.adviseHolding({ itemId: 1, qty: 100, avgCost: 1030, oldestTs: now - H }, mk, s, { ...item, highalch: 1200 }, { now });
    ok(alchy.action !== "alch", "never advises alching (that is money making, not flipping)");
  });

  await group("practice fill simulator", () => {
    const now = Date.now();
    const placedAt = now - 2 * H;
    const ts = series(40, 300, (i) => ({ avgHighPrice: 110, avgLowPrice: i < 30 ? 105 : 99, highPriceVolume: 100, lowPriceVolume: 100 }), now);
    const offer = { id: "x", itemId: 1, side: "buy", qty: 50, price: 100, placedAt, filled: 0, status: "open" };
    const r = M.simulateOffer(offer, ts, 0.1);
    ok(r.fills.length > 0, "fills once sellers trade at or below the bid");
    ok(r.fills.every((f) => f.ts > placedAt), "no fills before the offer existed");
    ok(r.fills.reduce((a, f) => a + f.qty, 0) <= 50, "never overfills");
    const r2 = M.simulateOffer({ ...offer, simCursor: r.cursor, simFrac: r.frac, filled: r.fills.reduce((a, f) => a + f.qty, 0) }, ts, 0.1);
    eq(r2.fills.length, 0, "re-running from the cursor doesn't double-fill");
    const never = M.simulateOffer({ ...offer, price: 90 }, ts, 0.1);
    eq(never.fills.length, 0, "bid below the market never fills");
  });

  await group("engine end-to-end (stubbed API)", async () => {
    const mk = makeMarket();
    const list = [...mk.items.values()];
    const flat5 = series(100, 300, () => ({ avgHighPrice: 1048, avgLowPrice: 1001, highPriceVolume: 800, lowPriceVolume: 900 }));
    const flat1 = series(200, 3600, () => ({ avgHighPrice: 1048, avgLowPrice: 1001, highPriceVolume: 9000, lowPriceVolume: 9000 }));
    OSF.api.fetchImpl = async (url) => {
      const p = url.replace(C.API_BASE, "");
      let body;
      if (p === "/mapping") body = list;
      else if (p === "/latest") body = { data: mk.latest };
      else if (p === "/5m") body = { data: mk.m5 };
      else if (p === "/1h") body = { data: mk.h1 };
      else if (p === "/24h") return { ok: false, status: 404 };
      else if (p.startsWith("/timeseries")) body = { data: p.includes("5m") ? flat5 : flat1 };
      return { ok: true, json: async () => body };
    };
    const state = OSF.store.fresh();
    const res = await OSF.engine.recommend(state, {});
    ok(res.recs.length > 0, "recommendations produced");
    ok(res.alloc.plan.length > 0, "plan produced");
    ok(res.alloc.invested <= res.cap.deployable, "plan within deployable");
    // Place the top pick as a practice offer and sync fills.
    const top = res.alloc.plan[0];
    const lg = state.ledgers.practice;
    const o = L.placeOffer(lg, { itemId: top.id, side: "buy", qty: top.qty, price: top.buy, placedAt: Date.now() - 3 * H });
    const fills = await OSF.engine.syncPractice(state);
    ok(fills.length > 0 && o.filled > 0, "practice offer filled from history");
    // 7 GE slots by default (one left for your own trades); old saved defaults move once.
    eq(OSF.store.fresh().settings.slots, 7, "fresh settings use 7 slots");
    eq(OSF.store.normalise({ settings: { slots: 8 } }).settings.slots, 7, "old 8-slot default moves to 7");
    eq(OSF.store.normalise({ settings: { slots: 5 } }).settings.slots, 5, "a custom slot count is kept");
    eq(OSF.store.normalise({ settings: { slots: 8, slotsDefault7: true } }).settings.slots, 8, "choosing 8 after the move sticks");
    OSF.store.save(state);
    eq(OSF.store.load().ledgers.practice.trades.length, lg.trades.length, "state persists");
  });

  await group("production strategy (decide.js)", () => {
    const D = OSF.decide;
    const f = { net_margin_rel: 0.03, log_vol_buy_6h: 3, log_vol_sell_6h: 3, ret_24h: 0.01, z_168h: 0.5, active_24h: 0.9 };
    ok(D.eligible(f), "normal item is eligible");
    ok(!D.eligible({ ...f, net_margin_rel: 0.4 }), "absurd margin blocked by the guard");
    ok(!D.eligible({ ...f, ret_24h: 0.5 }), "price spike blocked by the guard");
    ok(!D.eligible({ ...f, active_24h: 0.2 }), "inactive item blocked");
    const pred = { fill: 0.9, ret: 0.02, hours: 3, fwd4_q10: -0.02, fwd4_q50: 0.002, fwd4_q90: 0.03 };
    const items = Array.from({ length: 12 }, (_, i) => ({ id: i + 1, name: "it" + i, limitLeft: 5000,
      bar: { ah: 1050, al: 1000, hv: 20000, lv: 20000 }, f, pred: { ...pred, ret: 0.01 + i * 0.002 } }));
    const r = D.buyPlan(items, { deployable: 5_500_000, slotsFree: 8 }, 0);
    ok(r.plan.length <= 8, "never more orders than free slots");
    ok(U.sum(r.plan.map((x) => x.cost)) <= 5_500_000, "never more than deployable gp");
    ok(r.plan.every((x) => x.cost <= 5_500_000 * 0.10 + 1001), "per-item cap 10%");
    ok(r.plan.every((x) => x.qty <= 5000), "buy limit respected");
    ok(r.plan[0].buy === 1001, "bid = hourly instant-sell + 1");
    const half = D.buyPlan(items, { deployable: 5_500_000, slotsFree: 8 }, -0.05);
    ok(U.sum(half.plan.map((x) => x.cost)) <= 2_750_000 + 1, "market brake halves the gp used");
    const bar = { ah: 1100, al: 1050 };
    eq(D.holdingAction({ qty: 100, avgCost: 1000, heldHours: 2 }, bar, pred, 1).action, "list", "profitable holding: list at market");
    eq(D.holdingAction({ qty: 100, avgCost: 1000, heldHours: 2 }, bar, { ...pred, fwd4_q50: -0.01 }, 1).action, "sell", "falling forecast: sell now");
    eq(D.holdingAction({ qty: 100, avgCost: 1300, heldHours: 2 }, bar, { ...pred, fwd4_q50: -0.01, fwd4_q90: 0.01 }, 1).action, "cut", "hopeless loser: cut");
    eq(D.holdingAction({ qty: 100, avgCost: 1100, heldHours: 2 }, bar, { ...pred, fwd4_q90: 0.08 }, 1).action, "hold", "recoverable loser: hold at breakeven");
    eq(D.holdingAction({ qty: 100, avgCost: 1000, heldHours: 50 }, bar, pred, 1).action, "cut", "held over 2 days: sell now");
  });

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed) { fails.forEach((f) => console.log("  ✗ " + f)); process.exit(1); }
})();
