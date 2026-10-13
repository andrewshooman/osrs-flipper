/* ============================================================
   OSRS FLIPPER — orchestration: fetch → screen → deepen → estimate → allocate,
   and the practice-offer fill simulator loop.
   ============================================================ */
(function (OSF) {
  "use strict";
  const C = OSF.config, M = OSF.model, L = OSF.ledger, A = OSF.api, U = OSF.util;

  const activeLedger = (state) => state.ledgers[state.mode];
  const bankrollFor = (state) => (state.mode === "practice" ? state.settings.practiceBankroll : state.settings.bankroll);
  const capitalFor = (state) => L.capital(activeLedger(state), state.settings, bankrollFor(state));

  async function history(id) {
    const [ts5m, ts1h] = await Promise.all([
      A.timeseries(id, "5m").catch(() => []),
      A.timeseries(id, "1h").catch(() => []),
    ]);
    return { ts5m, ts1h, features: M.historyFeatures(id, ts5m, ts1h) };
  }

  /**
   * Full recommendation run. onProgress(text) gets status updates.
   */
  async function recommend(state, { force = false, onProgress = () => {} } = {}) {
    const s = state.settings;
    const ledger = activeLedger(state);
    onProgress("Fetching market prices…");
    const mk = await A.market(force);
    const cap = capitalFor(state);
    // Screen against the capital you'd have with nothing deployed, so the list
    // stays useful even when every slot is busy. The plan uses what's free now.
    const screenCapital = Math.max(cap.deployable, cap.equity * (1 - s.cashReservePct));
    const { candidates, counts } = M.screen(mk, s, { ledger, deployable: screenCapital });
    const deep = candidates.slice(0, C.DEEP_CANDIDATES);

    onProgress(`Analysing price history for ${deep.length} candidates…`);
    let done = 0;
    const hists = await Promise.all(deep.map((c) => history(c.id).then((h) => {
      onProgress(`Analysing price history… ${++done}/${deep.length}`);
      return h;
    })));

    const records = new Map(L.stats(ledger).byItem.map((r) => [r.itemId, r]));
    const perItemCap = screenCapital * s.maxPerItemPct;
    const opts = (c, i, capitalCap) => ({ settings: s, mk, hist: hists[i].features, capitalCap, myRecord: records.get(c.id) });
    const recs = [];
    deep.forEach((c, i) => {
      const r = M.estimate(c, opts(c, i, perItemCap));
      if (r) { r._cand = c; r._i = i; recs.push(r); }
    });
    recs.sort((a, b) => b.perHour - a.perHour);

    const busy = new Set(L.openOffers(ledger).map((o) => o.itemId));
    const reestimate = (r, budget) => M.estimate(r._cand, opts(r._cand, r._i, budget));
    const alloc = M.allocate(recs, cap, s, reestimate, busy);

    return { mk, recs, alloc, cap, counts, at: Date.now() };
  }

  /**
   * Advance every open practice offer against real market history.
   * Returns the list of fills applied (mutates state).
   */
  async function syncPractice(state) {
    const ledger = state.ledgers.practice;
    const open = L.openOffers(ledger);
    if (!open.length) return [];
    const strat = C.STRATEGIES[state.settings.strategy] || C.STRATEGIES.active;
    const applied = [];
    const ids = [...new Set(open.map((o) => o.itemId))];
    const series = new Map(await Promise.all(ids.map(async (id) => [id, await A.timeseries(id, "5m", 2 * 60 * 1000).catch(() => [])])));
    // Buys before sells so a sell can match a buy filled in the same pass.
    for (const o of open.sort((a, b) => (a.side === b.side ? a.placedAt - b.placedAt : a.side === "buy" ? -1 : 1))) {
      const res = M.simulateOffer(o, series.get(o.itemId) || [], strat.captureShare);
      o.simCursor = res.cursor;
      o.simFrac = res.frac;
      for (const f of res.fills) {
        const t = L.fillOffer(ledger, o.id, f.qty, f.ts);
        if (t) applied.push({ offer: o, trade: t });
      }
    }
    return applied;
  }

  // ---------------------------------------------------------------- validated model (production)
  const tax1 = (p) => Math.min(Math.floor(p * 0.02), 5e6);
  const midOf = (d) => (d && d.avgHighPrice > 0 && d.avgLowPrice > 0 ? (d.avgHighPrice + d.avgLowPrice) / 2 : NaN);

  /** Median h-hour return across liquid items, from whole-market /1h snapshots. */
  function marketReturns(now, prev1, prev24, day) {
    const liquid = Object.keys(now).filter((id) => day && day[id] && (day[id].lowPriceVolume || 0) / 24 > 5);
    const med = (prev) => {
      const r = liquid.map((id) => Math.log(midOf(now[id])) - Math.log(midOf(prev[id]))).filter(Number.isFinite).sort((a, b) => a - b);
      return r.length ? r[r.length >> 1] : 0;
    };
    return { mkt_ret_1h: med(prev1), mkt_ret_24h: med(prev24) };
  }

  /** Features + model predictions for one item at the latest completed hour. */
  async function scoreItem(id, item, barTs, ctx) {
    const pts = await A.timeseries(id, "1h");
    const s = OSF.features.denseSeries(pts, barTs);
    const f = OSF.features.itemFeatures(s, item, ctx);
    const last = s.ah.length - 1;
    const bar = { ah: s.ah[last], al: s.al[last], hv: s.hv[last], lv: s.lv[last] };
    return { f, bar, pred: OSF.gbm.predict(window.OSF_MODEL, f) };
  }

  /**
   * "Do this now": buy orders for free slots + an action for every holding,
   * from the validated model. Needs models/flip-model.js loaded.
   */
  async function doThisNow(state, { onProgress = () => {}, candidates = 150 } = {}) {
    if (!window.OSF_MODEL) throw new Error("Model file not loaded (models/flip-model.js)");
    const ledger = activeLedger(state);
    const barTs = Math.floor(Date.now() / 1000 / 3600) * 3600 - 3600; // latest completed hour
    onProgress("Fetching market snapshots…");
    const [items, now, prev1, prev24, day] = await Promise.all([
      A.mapping(), A.snapshot("1h", barTs), A.snapshot("1h", barTs - 3600), A.snapshot("1h", barTs - 86400), A.avg24h(),
    ]);
    const ctx = marketReturns(now, prev1, prev24, day);
    const cap = capitalFor(state);
    const s = state.settings;
    // Shortlist: both sides traded last hour, members setting, positive post-tax margin, decent volume.
    const short = Object.entries(now).map(([k, d]) => ({ id: +k, d, item: items.get(+k) }))
      .filter(({ d, item }) => item && (s.members || !item.members) && !(s.excludedIds || []).includes(item.id)
        && d.avgHighPrice > 0 && d.avgLowPrice > 0 && d.highPriceVolume > 2 && d.lowPriceVolume > 2
        && d.avgHighPrice - 1 - tax1(d.avgHighPrice - 1) - (d.avgLowPrice + 1) > 0)
      .map((x) => ({ ...x, edge: Math.min(x.d.highPriceVolume, x.d.lowPriceVolume) * (x.d.avgHighPrice - 1 - tax1(x.d.avgHighPrice - 1) - x.d.avgLowPrice - 1) }))
      .sort((a, b) => b.edge - a.edge) // tradeable gp of margin per hour
      .slice(0, candidates);
    const { holdings } = L.positions(ledger.trades);
    const ids = [...new Set([...short.map((x) => x.id), ...holdings.keys()])];
    let done = 0;
    const scored = new Map(await Promise.all(ids.map(async (id) => {
      const r = await scoreItem(id, items.get(id) || {}, barTs, ctx).catch(() => null);
      onProgress(`Running the model… ${++done}/${ids.length}`);
      return [id, r];
    })));
    const open = L.openOffers(ledger);
    const holds = [...holdings.values()].map((h) => {
      const sc = scored.get(h.itemId);
      const heldHours = (Date.now() - h.oldestTs) / 36e5;
      const act = sc ? OSF.decide.holdingAction({ qty: h.qty, avgCost: h.avgCost, heldHours }, sc.bar, sc.pred, h.itemId)
        : { action: "wait", label: "No model data", tone: "muted" };
      const listing = open.find((o) => o.side === "sell" && o.itemId === h.itemId) || null;
      const ask = sc && sc.bar.ah > 0 ? Math.round(sc.bar.ah) - 1 : null;
      const pnl = ask ? (U.netSellPrice(ask, h.itemId) - h.avgCost) * h.qty : null;
      return { ...h, heldHours, name: (items.get(h.itemId) || {}).name, act, listing, ask, pnl };
    });
    // Selling what you hold comes first: keep a slot for every holding that still
    // needs listing, and if there aren't enough, free them by cancelling the
    // least-filled buy offers (same rule as the bot's sell_priority).
    const needSlot = holds.filter((h) => !h.listing && h.act.action !== "wait").length;
    const makeRoom = open.filter((o) => o.side === "buy")
      .sort((a, b) => a.filled / a.qty - b.filled / b.qty || a.placedAt - b.placedAt)
      .slice(0, Math.max(0, needSlot - cap.slotsFree));
    const buySlots = Math.max(0, cap.slotsFree - needSlot);
    const busy = new Set(open.map((o) => o.itemId));
    const buyItems = short.filter((x) => !busy.has(x.id) && !holdings.has(x.id) && scored.get(x.id))
      .map((x) => ({ id: x.id, name: x.item.name, limitLeft: L.remainingLimit(ledger, x.id, x.item.limit), ...scored.get(x.id) }));
    const plan = OSF.decide.buyPlan(buyItems, { ...cap, slotsFree: buySlots }, ctx.mkt_ret_24h);
    // Prices are from the last hourly close; flag orders whose live price has moved since.
    const lt = await A.latest().catch(() => ({}));
    for (const r of plan.plan) {
      const L = lt[r.id];
      if (!L || !(L.low > 0)) continue;
      r.liveLow = L.low;
      if (L.low > r.buy * 1.02) r.drift = `Price rose since the hourly close (instant-sell now ${L.low}). Skip, or bid up to ${Math.min(L.low + 1, r.sellTarget - 1)} if the margin still clears tax.`;
      else if (L.low < r.buy * 0.98) r.drift = `Price dropped since the hourly close (instant-sell now ${L.low}). You can bid a little lower.`;
    }
    return { barTs, ctx, cap, plan, holds, buySlots, makeRoom, at: Date.now() };
  }

  OSF.engine = { recommend, syncPractice, history, activeLedger, bankrollFor, capitalFor, doThisNow, marketReturns };
})(window.OSF = window.OSF || {});
