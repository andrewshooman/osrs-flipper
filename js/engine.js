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

  OSF.engine = { recommend, syncPractice, history, activeLedger, bankrollFor, capitalFor };
})(window.OSF = window.OSF || {});
