/* ============================================================
   GE TYCOON — recommendation model

   Pipeline (each stage is a pure function, so every number is explainable):
     1. screen()    — cheap pass over the whole market using bulk endpoints:
                      tradability, freshness, volume, post-tax margin.
     2. deepen()    — for the shortlist only, per-item history: volatility,
                      trend, distance from the 7-day mean, how persistently
                      the margin has been there, volume surges.
     3. estimate()  — quantity (buy limit / liquidity / capital), time to
                      buy+sell, expected profit after a risk charge,
                      gp per slot-hour, confidence grade, reasons.
     4. allocate()  — spread the deployable gp across free GE slots.
   Plus adviseHolding() / adviseOffer() for what you already own, and
   simulateOffer() which fills practice offers against real market history.
   ============================================================ */
(function (OSF) {
  "use strict";
  const C = OSF.config, U = OSF.util;

  const nowSec = (now) => Math.floor((now ?? Date.now()) / 1000);
  const has = (x) => x != null && isFinite(x) && x > 0;

  /** Per-hour volumes on each side, blending 1h and 24h data when both exist. */
  function volumes(id, mk) {
    const h = mk.h1 && mk.h1[id], d = mk.d1 && mk.d1[id];
    const h1Buy = h ? h.lowPriceVolume || 0 : 0;   // instant sells → fill your BUY offers
    const h1Sell = h ? h.highPriceVolume || 0 : 0; // instant buys → fill your SELL offers
    if (d) {
      const dBuy = (d.lowPriceVolume || 0) / 24, dSell = (d.highPriceVolume || 0) / 24;
      return {
        buyPerHr: 0.5 * h1Buy + 0.5 * dBuy,
        sellPerHr: 0.5 * h1Sell + 0.5 * dSell,
        daily: (d.lowPriceVolume || 0) + (d.highPriceVolume || 0),
        dailyEstimated: false,
      };
    }
    return { buyPerHr: h1Buy, sellPerHr: h1Sell, daily: (h1Buy + h1Sell) * 24, dailyEstimated: true };
  }

  /**
   * Prices we'd actually post. Bid one above the instant-sell price and ask
   * one below the instant-buy price, but never trust a single outlier trade:
   * use the recent averages when they're less favourable.
   */
  function quotePrices(id, mk) {
    const L = mk.latest[id];
    if (!L || !has(L.high) || !has(L.low)) return null;
    const f = mk.m5 && mk.m5[id], h = mk.h1 && mk.h1[id];
    const avgLow = f && has(f.avgLowPrice) ? f.avgLowPrice : h && has(h.avgLowPrice) ? h.avgLowPrice : null;
    const avgHigh = f && has(f.avgHighPrice) ? f.avgHighPrice : h && has(h.avgHighPrice) ? h.avgHighPrice : null;
    const buy = Math.round(Math.max(L.low, avgLow ?? 0)) + 1;
    const sell = Math.round(Math.min(L.high, avgHigh ?? Infinity)) - 1;
    return { buy, sell, rawLow: L.low, rawHigh: L.high, lowTime: L.lowTime, highTime: L.highTime };
  }

  const netMargin = (buy, sell, id) => U.netSellPrice(sell, id) - buy;

  function natureRunePrice(mk) {
    const n = mk.latest[C.NATURE_RUNE_ID];
    return n && has(n.high) ? n.high : 200;
  }

  // ---------------------------------------------------------------- 1. screen
  /**
   * Returns shortlisted candidates sorted by a rough "gp per cycle" figure.
   * ctx: { ledger, now } — used for remaining buy limits.
   */
  function screen(mk, settings, ctx = {}) {
    const strat = C.STRATEGIES[settings.strategy] || C.STRATEGIES.active;
    const now = ctx.now ?? Date.now();
    const excluded = new Set(settings.excludedIds || []);
    const deployable = ctx.deployable ?? settings.bankroll * (1 - settings.cashReservePct);
    const perItemCap = deployable * settings.maxPerItemPct;
    const out = [];
    const counts = { total: 0, tradable: 0, fresh: 0, liquid: 0, profitable: 0 };

    for (const [id, item] of mk.items) {
      counts.total++;
      if (excluded.has(id) || (!settings.members && item.members)) continue;
      const q = quotePrices(id, mk);
      if (!q) continue;
      counts.tradable++;
      const staleMin = Math.max(nowSec(now) - q.lowTime, nowSec(now) - q.highTime) / 60;
      if (staleMin > strat.maxStaleMinutes) continue;
      counts.fresh++;
      const vol = volumes(id, mk);
      if (vol.daily < strat.minDailyVolume || vol.buyPerHr <= 0 || vol.sellPerHr <= 0) continue;
      counts.liquid++;
      const margin = netMargin(q.buy, q.sell, id);
      const roi = margin / q.buy;
      if (margin < settings.minMarginGp || roi * 100 < settings.minRoiPct) continue;
      if (q.buy > perItemCap) continue;
      counts.profitable++;

      const limitLeft = ctx.ledger ? OSF.ledger.remainingLimit(ctx.ledger, id, item.limit, now) : (item.limit || Infinity);
      const myBuy = strat.captureShare * vol.buyPerHr, mySell = strat.captureShare * vol.sellPerHr;
      const liqCap = Math.floor(strat.horizonHours * (myBuy * mySell) / (myBuy + mySell));
      const qty = Math.max(0, Math.min(limitLeft, liqCap, Math.floor(perItemCap / q.buy)));
      out.push({ id, item, quote: q, vol, margin, roi, staleMin, limitLeft, roughProfit: qty * margin });
    }
    out.sort((a, b) => b.roughProfit - a.roughProfit);
    return { candidates: out.filter((c) => c.roughProfit > 0), counts };
  }

  // ---------------------------------------------------------------- 2. deepen
  /**
   * History features. ts5m ≈ last 30h in 5-minute buckets, ts1h ≈ last 15 days
   * in hourly buckets (OSRS Wiki /timeseries returns up to 365 points).
   */
  function historyFeatures(id, ts5m, ts1h) {
    const f = {};
    const mids = (pts) => pts.filter((p) => has(p.avgHighPrice) && has(p.avgLowPrice))
      .map((p) => ({ t: p.timestamp, mid: (p.avgHighPrice + p.avgLowPrice) / 2 }));

    // Volatility, trend and mean-reversion from hourly mids.
    const h = mids(ts1h || []);
    if (h.length >= 12) {
      const logs = h.map((p) => Math.log(p.mid));
      const rets = logs.slice(1).map((x, i) => x - logs[i]).slice(-72);
      f.sigmaHr = U.stdev(rets);
      const last24 = h.slice(-24), last168 = h.slice(-168);
      f.trend24h = U.slope(last24.map((p) => p.t / 3600), last24.map((p) => Math.log(p.mid)));   // log-change per hour
      f.trend7d = U.slope(last168.map((p) => p.t / 3600), last168.map((p) => Math.log(p.mid)));
      const m7 = last168.map((p) => p.mid);
      f.mean7d = U.mean(m7);
      f.sd7d = U.stdev(m7);
      f.z7d = f.sd7d > 0 ? (h[h.length - 1].mid - f.mean7d) / f.sd7d : 0;
      f.min7d = Math.min(...m7); f.max7d = Math.max(...m7);
    }

    // Margin persistence and volume surges from 5-minute buckets.
    const recent = (ts5m || []).slice(-36); // last ~3h
    const nets = recent.filter((p) => has(p.avgHighPrice) && has(p.avgLowPrice))
      .map((p) => netMargin(Math.round(p.avgLowPrice) + 1, Math.round(p.avgHighPrice) - 1, id));
    if (nets.length >= 6) {
      f.persistence = nets.filter((n) => n > 0).length / nets.length;
      f.typicalMargin = U.median(nets);
      f.marginSamples = nets.length;
    }
    if (!f.sigmaHr) {
      const m = mids(ts5m || []);
      if (m.length >= 24) {
        const logs = m.map((p) => Math.log(p.mid));
        f.sigmaHr = U.stdev(logs.slice(1).map((x, i) => x - logs[i])) * Math.sqrt(12);
      }
    }
    const vol = (p) => (p.highPriceVolume || 0) + (p.lowPriceVolume || 0);
    const all5 = ts5m || [];
    if (all5.length >= 48) {
      const lastHour = U.sum(all5.slice(-12).map(vol));
      const baseline = U.mean(all5.slice(0, -12).map(vol)) * 12;
      f.volumeSurge = baseline > 0 ? lastHour / baseline : null;
    }
    return f;
  }

  // ---------------------------------------------------------------- 3. estimate
  /**
   * Full estimate for one candidate.
   * opts: { capitalCap, strategy, settings, mk, hist, myRecord }
   */
  function estimate(cand, opts) {
    const { settings, mk } = opts;
    const strat = C.STRATEGIES[opts.strategy || settings.strategy] || C.STRATEGIES.active;
    const hist = opts.hist || {};
    const { id, item, quote: q, vol } = cand;
    const reasons = [], flags = [];

    // Margin: blend current with the recent typical margin; never above current.
    let margin = cand.margin;
    if (hist.typicalMargin != null) {
      margin = Math.min(cand.margin, 0.5 * cand.margin + 0.5 * hist.typicalMargin);
      if (hist.typicalMargin > 0 && cand.margin > 3 * hist.typicalMargin) flags.push({ k: "spike", bad: true, t: "Margin is >3× its recent norm — likely a one-off trade" });
    }
    if (margin <= 0) return null;
    const persistence = hist.persistence ?? 0.6;

    // Quantity.
    const myBuy = strat.captureShare * vol.buyPerHr, mySell = strat.captureShare * vol.sellPerHr;
    const liqCap = Math.floor(strat.horizonHours * (myBuy * mySell) / (myBuy + mySell));
    const afford = Math.floor((opts.capitalCap ?? Infinity) / q.buy);
    const qty = Math.max(0, Math.min(cand.limitLeft, liqCap, afford));
    if (qty < 1) return null;
    const binding = qty === cand.limitLeft ? "buy limit" : qty === liqCap ? "market volume" : "capital";

    // Time to fill both legs (sequential, conservative).
    const hours = Math.max(1 / 12, qty / myBuy + qty / mySell);

    // Risk charge: adverse price move over the holding time.
    const sigma = hist.sigmaHr ?? 0.01;
    let riskCost = 0.5 * strat.riskAversion * qty * q.buy * sigma * Math.sqrt(hours);
    const alchFloor = item.highalch ? item.highalch - natureRunePrice(mk) : 0;
    if (settings.members && alchFloor >= q.buy * 0.98) {
      riskCost *= 0.3;
      flags.push({ k: "alch", bad: false, t: `High alch floor ${U.gp(alchFloor)} limits the downside` });
    }
    // Drift: falling items cost you; rising ones help a little.
    const trend = hist.trend24h ?? 0;
    const driftRaw = qty * q.buy * trend * hours;
    const drift = Math.min(driftRaw, 0) + 0.25 * Math.max(driftRaw, 0);

    const gross = qty * margin;
    const expected = gross * (0.4 + 0.6 * persistence) + drift - riskCost;
    if (expected <= 0) return null;
    const perHour = expected / hours;

    // Flags.
    if (hist.sigmaHr > 0.02) flags.push({ k: "volatile", bad: true, t: `Volatile: ±${U.pct(hist.sigmaHr)} per hour` });
    if (hist.trend7d != null && hist.trend7d * 24 < -0.01) flags.push({ k: "down", bad: true, t: `Falling ${U.pct(-hist.trend7d * 24)}/day over 7 days` });
    if (hist.trend24h != null && hist.trend24h * 24 > 0.02) flags.push({ k: "up", bad: false, t: `Rising ${U.pct(hist.trend24h * 24)}/day today` });
    if (hist.volumeSurge > 3) flags.push({ k: "surge", bad: true, t: `Volume ${hist.volumeSurge.toFixed(1)}× normal in the last hour — news or a dump?` });
    if (vol.daily < 5000) flags.push({ k: "thin", bad: true, t: `Thin market: ~${U.gp(vol.daily)} trades/day` });
    if (cand.staleMin > 10) flags.push({ k: "stale", bad: true, t: `Last trade ${Math.round(cand.staleMin)}m ago` });
    if (opts.myRecord && opts.myRecord.flips >= 2 && opts.myRecord.profit < 0) flags.push({ k: "mine", bad: true, t: `You're ${U.gp(opts.myRecord.profit)} on this item so far` });

    // Confidence grade.
    let pts = 0;
    pts += vol.daily >= 50_000 ? 2 : vol.daily >= 5_000 ? 1 : 0;
    pts += persistence >= 0.7 ? 2 : persistence >= 0.4 ? 1 : 0;
    pts += hist.marginSamples ? 1 : 0;
    pts += sigma < 0.01 ? 1 : 0;
    pts -= flags.filter((x) => x.bad).length;
    const grade = pts >= 5 ? "A" : pts >= 3 ? "B" : pts >= 1 ? "C" : "D";

    reasons.push(`${U.gp(margin)} profit per item after tax (${U.pct(margin / q.buy, 2)})`);
    if (hist.persistence != null) reasons.push(`Margin held in ${Math.round(hist.persistence * 100)}% of the last ${hist.marginSamples} five-minute windows`);
    reasons.push(`~${U.gp(vol.buyPerHr + vol.sellPerHr)} traded per hour; qty set by ${binding}`);
    if (hist.z7d != null && Math.abs(hist.z7d) > 1.5) reasons.push(`Price is ${hist.z7d > 0 ? "above" : "below"} its 7-day average (z=${hist.z7d.toFixed(1)})`);

    return {
      id, name: item.name, icon: item.icon, limit: item.limit, members: item.members,
      buy: q.buy, sell: q.sell, margin, roi: margin / q.buy, qty, cost: qty * q.buy,
      hours, expected, perHour, gross, riskCost, drift, persistence,
      sigma, grade, flags, reasons, binding, vol, alchFloor,
      strategy: opts.strategy || settings.strategy, hist,
    };
  }

  // ---------------------------------------------------------------- 4. allocate
  /**
   * Greedy allocation over two scarce resources — gp and GE slots. Each idea
   * is ranked by gp/hour divided by the larger share of either resource it
   * uses, so one expensive pick can't crowd out several efficient ones.
   * Any single item is capped at maxPerItemPct of deployable gp.
   * `reestimate(cand, capitalCap)` recomputes a pick for a smaller budget.
   */
  function allocate(recs, cap, settings, reestimate, skipIds = new Set()) {
    let capitalLeft = cap.deployable;
    let slotsLeft = cap.slotsFree;
    const perItemCap = cap.deployable * settings.maxPerItemPct;
    const plan = [];
    const usage = (r) => Math.max(Math.min(r.cost, perItemCap) / Math.max(cap.deployable, 1), 1 / Math.max(cap.slotsFree, 1));
    const efficiency = (r) => r.perHour / usage(r);
    for (const r of [...recs].sort((a, b) => efficiency(b) - efficiency(a))) {
      if (slotsLeft <= 0) break;
      if (skipIds.has(r.id) || capitalLeft < r.buy) continue;
      const budget = Math.min(capitalLeft, perItemCap);
      const pick = r.cost <= budget ? r : reestimate(r, budget);
      if (!pick || pick.qty < 1) continue;
      plan.push(pick);
      capitalLeft -= pick.cost;
      slotsLeft--;
    }
    return {
      plan, capitalLeft, slotsLeft,
      expected: U.sum(plan.map((p) => p.expected)),
      invested: U.sum(plan.map((p) => p.cost)),
    };
  }

  // ---------------------------------------------------------------- holdings & offers
  /** What to do with an item you hold. */
  function adviseHolding(h, mk, settings, item, ctx = {}) {
    const now = ctx.now ?? Date.now();
    const q = quotePrices(h.itemId, mk);
    const L = mk.latest[h.itemId];
    const breakeven = U.breakevenSellPrice(h.avgCost, h.itemId);
    const heldHours = (now - h.oldestTs) / 36e5;
    if (!q || !L) return { action: "unknown", label: "No price data", breakeven, heldHours, tone: "muted" };

    const target = Math.max(q.sell, breakeven);
    const netNow = U.netSellPrice(q.sell, h.itemId) - h.avgCost;
    const pnl = netNow * h.qty, pnlPct = netNow / h.avgCost;
    const instant = L.low; // dumping into the best buy offer
    const alch = settings.members && item && item.highalch ? item.highalch - natureRunePrice(mk) : 0;
    const listed = (ctx.openSells || []).filter((o) => o.itemId === h.itemId);
    const base = { breakeven, heldHours, marketSell: q.sell, instant, pnl, pnlPct, target, alch };

    if (listed.length) {
      const o = listed[0];
      const mins = (now - o.placedAt) / 6e4;
      if (o.price > q.rawHigh * 1.01 && mins > 30)
        return { ...base, action: "reprice", label: `Listed at ${U.full(o.price)} for ${Math.round(mins)}m — buyers are paying ${U.full(q.rawHigh)}. Lower to ${U.full(q.sell)}${q.sell < breakeven ? " (below breakeven)" : ""}`, tone: "warn" };
      return { ...base, action: "listed", label: `Listed at ${U.full(o.price)} — waiting to fill`, tone: "muted" };
    }
    if (pnlPct <= -settings.stopLossPct)
      return { ...base, action: "cut", label: `Down ${U.pct(-pnlPct)} — stop-loss hit. Sell at ${U.full(q.sell)} (instant: ${U.full(instant)})`, tone: "bad" };
    if (heldHours >= settings.maxHoldHours)
      return { ...base, action: "time", label: `Held ${U.duration(heldHours)} — over your ${settings.maxHoldHours}h limit. Sell at ${U.full(q.sell)}`, tone: "warn" };
    if (netNow > 0)
      return { ...base, action: "sell", label: `Sell at ${U.full(q.sell)} → ${U.gp(pnl, { plus: true })} after tax`, tone: "good" };
    return { ...base, action: "hold", label: `Below breakeven. List at ${U.full(breakeven)} or wait`, tone: "muted" };
  }

  /** Advice for an open BUY offer. */
  function adviseOffer(o, mk, ctx = {}) {
    const now = ctx.now ?? Date.now();
    const q = quotePrices(o.itemId, mk);
    if (!q || o.side !== "buy") return null;
    const marginAtOffer = netMargin(o.price, q.sell, o.itemId);
    const mins = (now - o.placedAt) / 6e4;
    if (marginAtOffer <= 0) return { action: "cancel", label: `Margin gone (sell is ${U.full(q.sell)}) — cancel`, tone: "bad" };
    if (o.filled === 0 && mins > 20 && q.rawLow > o.price)
      return { action: "raise", label: `No fills in ${Math.round(mins)}m; buyers are paying ${U.full(q.rawLow)}. Raise to ${U.full(q.rawLow + 1)} or cancel`, tone: "warn" };
    return { action: "wait", label: `Margin still ${U.gp(marginAtOffer)} each`, tone: "muted" };
  }

  // ---------------------------------------------------------------- practice fills
  /**
   * Fills a practice offer against real 5-minute market history after it
   * was placed. A buy fills when instant-sells traded at or below your price
   * (you get `share` of that volume); a sell when instant-buys traded at or
   * above it. Crossing the spread fills much faster.
   * Returns { fills: [{qty, ts}], cursor, frac } — caller applies them.
   */
  function simulateOffer(o, ts5m, share) {
    let cursor = o.simCursor ?? Math.ceil(o.placedAt / 1000 / 300) * 300; // first whole bucket after placing
    let frac = o.simFrac || 0;
    let remaining = o.qty - o.filled;
    const fills = [];
    for (const p of ts5m) {
      if (p.timestamp < cursor || remaining <= 0) continue;
      let avail = 0;
      if (o.side === "buy" && has(p.avgLowPrice) && p.avgLowPrice <= o.price) {
        const crossing = has(p.avgHighPrice) && o.price >= p.avgHighPrice;
        avail = (p.lowPriceVolume || 0) * share + (crossing ? (p.highPriceVolume || 0) * 0.5 : 0);
      } else if (o.side === "sell" && has(p.avgHighPrice) && p.avgHighPrice >= o.price) {
        const crossing = has(p.avgLowPrice) && o.price <= p.avgLowPrice;
        avail = (p.highPriceVolume || 0) * share + (crossing ? (p.lowPriceVolume || 0) * 0.5 : 0);
      }
      cursor = p.timestamp + 300;
      if (avail <= 0) continue;
      frac += avail;
      const q = Math.min(remaining, Math.floor(frac));
      if (q > 0) {
        frac -= q;
        remaining -= q;
        fills.push({ qty: q, ts: (p.timestamp + 300) * 1000 });
      }
    }
    return { fills, cursor, frac: remaining > 0 ? frac : 0 };
  }

  OSF.model = {
    volumes, quotePrices, netMargin, natureRunePrice,
    screen, historyFeatures, estimate, allocate,
    adviseHolding, adviseOffer, simulateOffer,
  };
})(window.OSF = window.OSF || {});
