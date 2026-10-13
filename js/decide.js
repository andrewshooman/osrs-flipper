/* ============================================================
   GE FLIPPER — the validated "production" strategy, ported from
   research/strategy_model.py (PRESETS["production"]) for live use.

   Inputs per item: features from js/features.js (latest hourly bar) and
   predictions from js/gbm.js (fill, ret, hours, fwd4_q10/50/90).
   Outputs are plain orders a person can place at their next check-in.
   ============================================================ */
(function (OSF) {
  "use strict";
  const U = OSF.util;

  // Matches research/strategy_model.py PRESETS["checkin"]: the production preset
  // with a 10% per-item cap, which won for a player who checks in ~3x a day
  // (walk-forward round 3: +90k/day on 5.5M, 11/11 two-week folds positive).
  const P = {
    kellyFrac: 0.15,
    maxItemFrac: 0.10,
    minExpRet: 0.005,
    buyWaitHours: 6,        // patient offers: leave them up for ~6h, then cancel the rest
    captureShare: 0.10,     // share of real volume we expect to get
    guard: { maxMargin: 0.15, maxRet24h: 0.25, maxZ168h: 3, minActive: 0.5 },
    maxHoldHours: 48,       // research ModelStrategy.max_hold_h: sell anything held 2 days
  };

  const LOG1P2 = Math.log1p(2);

  /** Candidate screen + pump-and-dump guard (same rules as the backtest). */
  function eligible(f) {
    if (!(f.net_margin_rel > 0) || !(f.log_vol_buy_6h > LOG1P2) || !(f.log_vol_sell_6h > LOG1P2)) return false;
    const g = P.guard;
    if (f.net_margin_rel > g.maxMargin || f.ret_24h > g.maxRet24h || f.z_168h > g.maxZ168h) return false;
    return f.active_24h >= g.minActive;
  }

  /**
   * Buy plan. items: [{id, name, limitLeft, bar: {ah, al, hv, lv}, f, pred}]
   * cap: {deployable, slotsFree}. Returns ranked orders that fit gp + slots.
   */
  function buyPlan(items, cap, mktRet24h) {
    let deploy = cap.deployable;
    if (mktRet24h < -0.03) deploy *= 0.5; // whole market falling: half size
    const ideas = [];
    for (const it of items) {
      const { f, pred, bar } = it;
      if (!eligible(f) || !bar || !(bar.al > 0)) continue;
      const fill = U.clamp(pred.fill, 0, 1);
      const expRet = fill * pred.ret;
      if (!(expRet > P.minExpRet)) continue;
      const hours = Math.max(pred.hours, 0.25);
      const spread = Math.max(pred.fwd4_q90 - pred.fwd4_q10, 1e-3) / 2.56;
      const buy = Math.round(bar.al) + 1;
      const rate = Math.min(bar.lv, bar.hv) * P.captureShare; // per hour
      const kelly = U.clamp((P.kellyFrac * expRet) / (spread * spread), 0, P.maxItemFrac);
      const budget = Math.min(kelly * deploy, deploy * P.maxItemFrac);
      const qty = Math.floor(Math.min(budget / buy, it.limitLeft ?? Infinity, rate * 4));
      if (qty < 1) continue;
      const gp = qty * buy * expRet;
      ideas.push({
        id: it.id, name: it.name, buy, qty, cost: qty * buy, expRet, expected: gp, hours,
        sellTarget: Math.round(bar.ah) - 1, fill, q50: pred.fwd4_q50,
        reasons: [
          `${U.pct(expRet, 2)} expected after tax (fills ${U.pct(fill, 0)} of the time)`,
          `~${U.duration(hours)} to buy and sell; up to 10% of your free gp, capped by the buy limit and what the market can fill`,
        ],
      });
    }
    const plan = [];
    let left = deploy, slots = cap.slotsFree;
    const usage = (r) => Math.max(r.cost / Math.max(deploy, 1), 1 / Math.max(slots, 1));
    ideas.sort((a, b) => b.expected / b.hours / usage(b) - a.expected / a.hours / usage(a));
    for (const r of ideas) {
      if (slots <= 0) break;
      if (r.cost > left) {
        const q = Math.floor(left / r.buy);
        if (q < 1) continue;
        Object.assign(r, { qty: q, cost: q * r.buy, expected: q * r.buy * r.expRet });
      }
      plan.push(r);
      left -= r.cost;
      slots--;
    }
    return { plan, ideas, deployUsed: deploy - left };
  }

  /**
   * What to do with a holding. h: {qty, avgCost, heldHours}; bar: latest hourly
   * {ah, al}; pred: fwd4 quantiles. Mirrors ModelStrategy.manage().
   */
  function holdingAction(h, bar, pred, itemId) {
    if (!bar || !(bar.ah > 0)) return { action: "wait", label: "No recent price — check again later", tone: "muted" };
    const ask = Math.round(bar.ah) - 1;
    const netNow = U.netSellPrice(ask, itemId);
    const be = U.breakevenSellPrice(h.avgCost, itemId);
    const { fwd4_q10: q10, fwd4_q50: q50, fwd4_q90: q90 } = pred;
    if (h.heldHours >= P.maxHoldHours)
      return { action: "cut", price: Math.round(bar.al), label: `Held over ${Math.round(P.maxHoldHours / 24)} days: sell now (~${U.full(Math.round(bar.al))}) and free the gp`, tone: "warn" };
    if (netNow < h.avgCost) {
      const optimistic = ask * Math.exp(q90);
      if (U.netSellPrice(optimistic, itemId) < h.avgCost && q50 < 0)
        return { action: "cut", price: Math.round(bar.al), label: `Cut: even the good case won't reach breakeven. Sell now (~${U.full(Math.round(bar.al))})`, tone: "bad" };
      return { action: "hold", price: Math.max(ask, be), label: `Hold — list at ${U.full(Math.max(ask, be))} (breakeven ${U.full(be)})`, tone: "muted" };
    }
    if (q50 < -0.002 || q10 < -0.03)
      return { action: "sell", price: ask, label: `Sell now at ${U.full(ask)} — forecast is falling`, tone: "good" };
    return { action: "list", price: ask, label: `List at ${U.full(ask)} (profit ${U.gp((netNow - h.avgCost) * h.qty, { plus: true })})`, tone: "good" };
  }

  OSF.decide = { PRESET: P, eligible, buyPlan, holdingAction };
})(window.OSF = window.OSF || {});
