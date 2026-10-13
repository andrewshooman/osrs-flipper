/* ============================================================
   GE FLIPPER — UI controller
   ============================================================ */
(function (OSF) {
  "use strict";
  const { util: U, ledger: L, model: M, engine: E, store: ST, api: A, config: C, charts: CH, tradefile: TF } = OSF;

  const S = {
    state: ST.load(),
    items: new Map(),       // mapping
    result: null,           // last engine.recommend() output
    error: null,
    loading: false,
    progress: "",
    view: "bot",
    bot: { data: null, error: null, at: 0 },
    orders: { data: null, error: null, loading: false, progress: "" },
    sort: "perHour",
    ticks: 0,
  };

  // ---------------------------------------------------------------- helpers
  const $ = (sel, root = document) => root.querySelector(sel);
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const ledger = () => E.activeLedger(S.state);
  const settings = () => S.state.settings;
  // Practice trading was removed: the app tracks your real trades only (the
  // paper-trading bot is the test bed). Old practice data stays in storage.
  const isPractice = () => false;
  const itemName = (id) => (S.items.get(id) || {}).name || `Item ${id}`;
  const iconUrl = (id) => {
    const it = S.items.get(id);
    return it && it.icon ? "https://oldschool.runescape.wiki/images/" + encodeURIComponent(it.icon.replace(/ /g, "_")) : "";
  };
  const icon = (id) => {
    const u = iconUrl(id);
    return u ? `<img class="icon" src="${u}" alt="" loading="lazy" onerror="this.style.visibility='hidden'">` : `<span class="icon"></span>`;
  };
  function save() {
    S.state.savedAt = Date.now();
    ST.save(S.state);
    TF.schedule(S.state);
  }
  function toast(msg, ms = 4000) {
    const t = document.createElement("div");
    t.className = "toast";
    t.innerHTML = msg;
    $("#toasts").append(t);
    setTimeout(() => t.remove(), ms);
  }
  const signClass = (n) => (n > 0 ? "good" : n < 0 ? "bad" : "");

  // ---------------------------------------------------------------- data
  async function refresh({ force = false, quiet = false } = {}) {
    if (S.loading) return;
    S.loading = true;
    if (!quiet) { S.progress = "Loading…"; renderPicks(); }
    try {
      if (!S.items.size) S.items = await A.mapping();
      if (isPractice()) {
        const fills = await E.syncPractice(S.state);
        for (const f of fills) {
          toast(`Practice ${f.trade.side} filled: ${U.full(f.trade.qty)} × ${esc(itemName(f.trade.itemId))} @ ${U.full(f.trade.price)}`);
        }
        if (fills.length) save();
      }
      S.result = await E.recommend(S.state, { force, onProgress: (p) => { S.progress = p; if (!quiet) renderPicks(); } });
      S.error = null;
    } catch (e) {
      S.error = e.message || String(e);
    } finally {
      S.loading = false;
      S.progress = "";
      renderAll();
    }
  }

  // ---------------------------------------------------------------- shell
  function renderAll() {
    document.documentElement.dataset.mode = S.state.mode;

    document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("on", b.dataset.view === S.view));
    document.querySelectorAll(".view").forEach((v) => v.classList.toggle("on", v.id === "view-" + S.view));
    const botView = S.view === "bot";
    $("#capital").classList.toggle("hidden", botView);

    renderCapital();
    renderBadges();
    ({ bot: renderBot, orders: renderOrders, picks: renderPicks, slots: renderSlots, holdings: renderHoldings, profit: renderProfit, settings: renderSettings })[S.view]();
  }

  function renderCapital() {
    const cap = E.capitalFor(S.state);
    const st = L.stats(ledger());
    const s = settings();
    $("#capital").innerHTML = `
      <div class="stat"><div class="label">Stack</div><div class="value">${U.gp(cap.equity)}</div><div class="sub">${isPractice() ? "practice" : "real"} · at cost</div></div>
      <div class="stat"><div class="label">Free to flip</div><div class="value">${U.gp(cap.deployable)}</div><div class="sub">${U.gp(cap.reserve)} reserve (${Math.round(s.cashReservePct * 100)}%)</div></div>
      <div class="stat"><div class="label">In play</div><div class="value">${U.gp(cap.holdingCost + cap.inBuyOffers)}</div><div class="sub">${U.gp(cap.holdingCost)} items · ${U.gp(cap.inBuyOffers)} offers</div></div>
      <div class="stat"><div class="label">GE slots</div><div class="value">${cap.slotsUsed}/${s.slots}</div><div class="sub">${cap.slotsFree} free</div></div>
      <div class="stat"><div class="label">Profit</div><div class="value ${signClass(st.profit)}">${U.gp(st.profit, { plus: true })}</div><div class="sub">today ${U.gp(st.today, { plus: true })}</div></div>`;
  }

  function renderBadges() {
    const n = L.openOffers(ledger()).length;
    const h = L.positions(ledger().trades).holdings.size;
    const sb = $("#slot-badge"), hb = $("#hold-badge");
    sb.textContent = n; sb.classList.toggle("hidden", !n);
    hb.textContent = h; hb.classList.toggle("hidden", !h);
  }

  // ---------------------------------------------------------------- picks
  function flagChips(r) {
    return r.flags.map((f) => `<span class="chip ${f.bad ? "bad" : "ok"}" title="${esc(f.t)}">${esc(f.t)}</span>`).join("");
  }

  function pickRow(r, inPlan) {
    return `<div class="pick">
      ${icon(r.id)}
      <div style="min-width:0">
        <div class="name" data-item="${r.id}">${esc(r.name)}<span class="grade ${r.grade}" title="Confidence">${r.grade}</span></div>
        <div class="line">Buy ${U.full(r.qty)} @ ${U.full(r.buy)} → sell @ ${U.full(r.sell)}</div>
        <div class="line muted">${U.gp(r.margin)} each · ${U.pct(r.roi, 2)} · uses ${U.gp(r.cost)}</div>
      </div>
      <div class="right">
        <div class="profit good">${U.gp(r.expected, { plus: true })}</div>
        <div class="line">~${U.duration(r.hours)} · ${U.gp(r.perHour)}/h</div>
      </div>
      ${r.flags.length ? `<div class="chips">${flagChips(r)}</div>` : ""}
      <div class="actions">
        <button class="btn sm primary ${isPractice() ? "practice" : ""}" data-place="${r.id}" data-plan="${inPlan ? 1 : 0}">${isPractice() ? "Practice buy" : "I placed this"}</button>
        <button class="btn sm" data-item="${r.id}">Why?</button>
      </div>
    </div>`;
  }

  function renderPicks() {
    const root = $("#view-picks");
    if (S.view !== "picks") return;
    const s = settings();
    const strat = Object.entries(C.STRATEGIES).map(([k, v]) => `<button data-strategy="${k}" class="${s.strategy === k ? "on" : ""}" title="${esc(v.blurb)}">${esc(v.label)}</button>`).join("");
    const head = `<div class="section-head">
        <h2>Suggested plan</h2>
        <span class="seg">${strat}</span>
        <span class="spacer"></span>
        ${S.loading ? `<span class="small muted"><span class="spinner"></span> ${esc(S.progress)}</span>` : ""}
        <button class="btn sm" id="refresh" ${S.loading ? "disabled" : ""}>Refresh</button>
      </div>`;

    if (S.error && !S.result) {
      root.innerHTML = head + `<div class="card"><b>Couldn't load market data.</b><p class="secondary">${esc(S.error)}</p>
        <p class="small muted">The app talks directly to prices.runescape.wiki from your browser. Check your connection or try again in a minute.</p>
        <button class="btn" id="retry">Try again</button></div>`;
      return;
    }
    if (!S.result) {
      root.innerHTML = head + `<div class="card empty-state"><span class="spinner"></span> ${esc(S.progress || "Loading market…")}</div>`;
      return;
    }
    const { alloc, recs, counts, cap, at } = S.result;
    const planIds = new Set(alloc.plan.map((p) => p.id));
    let planHtml;
    if (!cap.slotsFree) planHtml = `<div class="empty-state">All ${s.slots} GE slots are busy. Check <b>Slots</b> and <b>Holdings</b>.</div>`;
    else if (cap.deployable <= 0) planHtml = `<div class="empty-state">Nothing free to flip — the rest of your stack is the ${Math.round(s.cashReservePct * 100)}% reserve or already in play.</div>`;
    else if (!alloc.plan.length) planHtml = `<div class="empty-state">No pick clears your filters right now. Try again in a few minutes or switch strategy.</div>`;
    else planHtml = `<div class="plan-summary">
          <div><div class="small muted">Expected profit</div><div class="big good">${U.gp(alloc.expected, { plus: true })}</div></div>
          <div><div class="small muted">Uses</div><div class="big">${U.gp(alloc.invested)}</div></div>
          <div><div class="small muted">Picks</div><div class="big">${alloc.plan.length}<span class="small muted"> / ${cap.slotsFree} free slots</span></div></div>
        </div>` + alloc.plan.map((r) => pickRow(r, true)).join("");

    const sorters = { perHour: (r) => r.perHour, expected: (r) => r.expected, roi: (r) => r.roi, grade: (r) => -"ABCD".indexOf(r.grade) * 1e12 + r.perHour, volume: (r) => r.vol.daily };
    const others = [...recs].sort((a, b) => sorters[S.sort](b) - sorters[S.sort](a));
    const th = (k, label, right) => `<th data-sort="${k}" class="${right ? "r" : ""}">${label}${S.sort === k ? " ▾" : ""}</th>`;

    root.innerHTML = head + `
      <div class="card">${planHtml}</div>
      <div class="section-head"><h2>All ideas</h2><span class="funnel">
        scanned ${U.full(counts.total)} → fresh ${U.full(counts.fresh)} → liquid ${U.full(counts.liquid)} → profitable ${U.full(counts.profitable)} → ranked ${recs.length}
        · updated ${U.ago(at)}</span></div>
      <div class="card table-wrap"><table class="list">
        <thead><tr><th>Item</th>${th("grade", "Conf.")}<th class="r">Buy</th><th class="r">Sell</th><th class="r">Qty</th>${th("roi", "ROI", 1)}${th("expected", "Profit", 1)}<th class="r">Time</th>${th("perHour", "gp/h", 1)}${th("volume", "Vol/day", 1)}</tr></thead>
        <tbody>${others.map((r) => `<tr>
          <td class="name" data-item="${r.id}">${esc(r.name)}${planIds.has(r.id) ? ' <span class="chip ok">plan</span>' : ""}</td>
          <td><span class="grade ${r.grade}">${r.grade}</span></td>
          <td class="r">${U.full(r.buy)}</td><td class="r">${U.full(r.sell)}</td><td class="r">${U.full(r.qty)}</td>
          <td class="r">${U.pct(r.roi, 2)}</td><td class="r good">${U.gp(r.expected, { plus: true })}</td>
          <td class="r">${U.duration(r.hours)}</td><td class="r">${U.gp(r.perHour)}</td><td class="r">${U.gp(r.vol.daily)}</td></tr>`).join("")}
        </tbody></table></div>`;
  }

  // ---------------------------------------------------------------- bot (paper trading)
  async function fetchBot() {
    try {
      const res = await fetch(C.BOT_STATE_URL + "?t=" + Date.now(), { cache: "no-store" });
      if (!res.ok) throw new Error("HTTP " + res.status);
      S.bot.data = await res.json();
      S.bot.error = null;
    } catch (e) {
      S.bot.error = e.message || String(e);
    }
    S.bot.at = Date.now();
    if (S.view === "bot") renderBot();
  }

  const actionLabel = { buy: "Buy offer", sell: "Sell offer", cut: "Cut loss" };
  const reasonLabel = { sell: "sold at target", dump: "cut / sold at market", buy: "buy" };

  function renderBot() {
    const root = $("#view-bot");
    const d = S.bot.data;
    const head = `<div class="section-head"><h2>Bot — paper trading</h2>
      <span class="small muted">${d ? `updated ${U.ago(d.updated * 1000)} · running since ${new Date(d.started * 1000).toLocaleString()}` : ""}</span>
      <span class="spacer"></span><button class="btn sm" id="bot-refresh">Refresh</button></div>
      <p class="small secondary" style="margin-top:0">The bot decides every buy, hold, sell and cut itself and trades a mock
      ${d ? U.gp(d.bankroll) : "11M"} stack (${d ? Math.round(d.reserve_pct * 100) : 50}% kept in reserve) against the live Grand Exchange.
      Offers only fill when real trades happen at its price, and only up to ${d ? Math.round(d.share_of_volume * 100) : 10}% of the real volume. No real gold is used.</p>`;
    if (!d) {
      root.innerHTML = head + `<div class="card empty-state">${S.bot.error ? `Couldn't load bot state (${esc(S.bot.error)}). Is the bot running?` : `<span class="spinner"></span> Loading…`}</div>`;
      return;
    }
    const tile = (label, value, sub, cls = "") => `<div class="stat"><div class="label">${label}</div><div class="value ${cls}">${value}</div><div class="sub">${sub}</div></div>`;
    const pos = [...d.positions].sort((a, b) => b.qty * b.avg_cost - a.qty * a.avg_cost);
    const plan = (p) => {
      if (!p.plan) return "Holding";
      const e = p.plan;
      if (e.action === "cut") return `Cutting: selling at market (${U.full(e.price)})`;
      if (e.action === "sell") return `Listed to sell at ${U.full(e.price)}`;
      return `Bought @ ${U.full(e.price)}`;
    };
    root.innerHTML = head + `
      <div class="capital" style="padding:0;grid-template-columns:repeat(auto-fill,minmax(150px,1fr))">
        ${tile("Profit", U.gp(d.profit, { plus: true }), "realised + if sold now", signClass(d.profit))}
        ${tile("Realised", U.gp(d.realised, { plus: true }), `after ${U.gp(d.tax_paid)} tax`, signClass(d.realised))}
        ${tile("Open P/L", U.gp(d.unrealised, { plus: true }), `${d.positions.length} items held`, signClass(d.unrealised))}
        ${tile("Free cash", U.gp(d.cash), `${U.gp(d.in_buy_offers)} in buy offers`)}
      </div>
      ${botVsBacktest(d)}
      <div class="section-head"><h2>Stack value</h2><span class="small muted">hourly, if everything were sold instantly after tax</span></div>
      <div class="card"><div id="bot-equity"></div></div>
      <div class="section-head"><h2>Holding now</h2></div>
      <div class="card table-wrap">${pos.length ? `<table class="list"><thead><tr><th>Item</th><th class="r">Qty</th><th class="r">Avg cost</th><th class="r">Sells at</th><th class="r">P/L if sold</th><th class="r">Held</th><th>Plan</th></tr></thead><tbody>
        ${pos.map((p) => `<tr><td class="name" data-item="${p.id}">${esc(p.name)}</td><td class="r">${U.full(p.qty)}</td><td class="r">${U.full(p.avg_cost)}</td>
          <td class="r">${U.full(p.market_sell)}</td><td class="r ${signClass(p.pnl_if_sold)}">${U.gp(p.pnl_if_sold, { plus: true })}</td>
          <td class="r">${U.duration(p.held_h)}</td><td class="small">${esc(plan(p))}</td></tr>`).join("")}</tbody></table>` : `<div class="empty-state">Nothing held right now.</div>`}</div>
      <div class="section-head"><h2>Open offers</h2><span class="small muted">${d.offers.length}/8 slots</span></div>
      <div class="card table-wrap">${d.offers.length ? `<table class="list"><tbody>
        ${d.offers.map((o) => `<tr><td><span class="side ${o.side}">${o.reason === "dump" ? "CUT" : o.side.toUpperCase()}</span></td><td class="name" data-item="${o.id}">${esc(o.name)}</td>
          <td class="r">${U.full(o.filled)}/${U.full(o.qty)} @ ${U.full(o.price)}</td><td class="r muted">${o.placed ? U.ago(o.placed * 1000) : ""}</td></tr>`).join("")}</tbody></table>` : `<div class="empty-state">No open offers.</div>`}</div>
      <div class="section-head"><h2>Closed trades</h2><span class="small muted">${d.trades.length} sells</span></div>
      <div class="card table-wrap">${d.trades.length ? `<table class="list"><thead><tr><th>Item</th><th class="r">Qty</th><th class="r">Bought</th><th class="r">Sold</th><th class="r">Profit</th><th class="r">Held</th><th>Exit</th></tr></thead><tbody>
        ${[...d.trades].reverse().map((t) => `<tr><td class="name" data-item="${t.id}">${esc(t.name)}</td><td class="r">${U.full(t.qty)}</td><td class="r">${U.full(t.buy)}</td><td class="r">${U.full(t.sell)}</td>
          <td class="r ${signClass(t.profit)}">${U.gp(t.profit, { plus: true })}</td><td class="r">${U.duration(t.hold)}</td><td class="small muted">${esc(reasonLabel[t.reason] || t.reason)}</td></tr>`).join("")}</tbody></table>` : `<div class="empty-state">No completed sells yet.</div>`}</div>
      <div class="section-head"><h2>Decision log</h2></div>
      <div class="card table-wrap">${d.events.length ? `<table class="list"><tbody>
        ${[...d.events].reverse().slice(0, 80).map((e) => `<tr><td class="muted">${new Date(e.ts * 1000).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}</td>
          <td>${esc(actionLabel[e.action] || e.action)}</td><td class="name" data-item="${e.item}">${esc(e.name)}</td><td class="r">${U.full(e.qty)} @ ${U.full(e.price)}</td></tr>`).join("")}</tbody></table>` : `<div class="empty-state">No decisions yet.</div>`}</div>`;
    CH.lineChart($("#bot-equity"), d.equity, { baseline: d.bankroll });
  }

  /** Live results vs. what the backtests predicted (research/reports: 5m replay + year walk-forward). */
  function botVsBacktest(d) {
    const hrs = Math.max((d.updated - d.started) / 3600, 0);
    const perDay = hrs >= 24 ? (d.profit / hrs) * 24 : null; // earlier rates swing too much to mean anything
    const lo = 102_000, hi = 141_000; // research/reports/replay-5m-r5: production preset at 5% / 10% of real volume
    const [tone, verdict] = hrs < 72
      ? ["muted", `Too early to judge after ${U.duration(hrs)}. Even in the backtests 14 of 60 days lost money, so give it 3–7 days.`]
      : perDay >= lo ? ["good", "On track: inside or above the backtest range."]
      : perDay >= 0 ? ["warn", "Profitable, but below the backtest range. The backtests may assume too many fills."]
      : ["bad", "Losing money, unlike the backtests. The model needs a review before you follow it."];
    return `<div class="card" style="margin-top:12px">
      <div class="row"><b class="grow">Live vs. backtest</b><span class="small muted">${U.duration(hrs)} live</span></div>
      <dl class="kv" style="margin:8px 0">
        <dt>Live, per day</dt><dd class="${signClass(perDay ?? d.profit)}">${perDay == null ? `after 24h (so far ${U.gp(d.profit, { plus: true })})` : U.gp(perDay, { plus: true })}</dd>
        <dt>Backtests, per day</dt><dd>${U.gp(lo, { plus: true })} to ${U.gp(hi, { plus: true })}</dd>
      </dl>
      <div class="advice ${tone}">${esc(verdict)}</div>
      <p class="small muted" style="margin:6px 0 0">Backtest range: a 60-day replay on real 5-minute trades, filling 5–10% of real volume.
      52 of 60 days were profitable. A year-long test (11 two-week periods) averaged +133k/day, and every period was profitable.</p></div>`;
  }

  // ---------------------------------------------------------------- orders (validated model)
  async function loadOrders() {
    if (S.orders.loading) return;
    S.orders.loading = true;
    S.orders.progress = "Starting…";
    renderOrders();
    try {
      if (!S.items.size) S.items = await A.mapping();
      S.orders.data = await E.doThisNow(S.state, { onProgress: (t) => { S.orders.progress = t; if (S.view === "orders") renderOrders(); } });
      S.orders.error = null;
    } catch (e) {
      S.orders.error = e.message || String(e);
    } finally {
      S.orders.loading = false;
      if (S.view === "orders") renderOrders();
      if (S.view === "holdings") renderHoldings();
      renderBadges();
    }
  }

  function renderOrders() {
    const root = $("#view-orders");
    if (S.view !== "orders") return;
    const d = S.orders.data;
    const head = `<div class="section-head"><h2>Do this now</h2>
        <span class="small muted">${d ? `prices from the ${new Date((d.barTs + 3600) * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })} hourly close` : ""}</span>
        <span class="spacer"></span>
        ${S.orders.loading ? `<span class="small muted"><span class="spinner"></span> ${esc(S.orders.progress)}</span>` : ""}
        <button class="btn sm" id="orders-refresh" ${S.orders.loading ? "disabled" : ""}>Refresh</button></div>
      <p class="small secondary" style="margin-top:0">Built for check-ins a few times a day: place these, leave buy offers up for
      about 6 hours, and come back later. Sell prices don't need babysitting until your next check-in.
      Three check-ins a day capture most of the profit (backtest: 2× +77k, 3× +90k, 6× +105k gp/day).</p>`;
    if (!d) {
      root.innerHTML = head + `<div class="card empty-state">${S.orders.error ? `Couldn't build orders: ${esc(S.orders.error)}` : `<span class="spinner"></span> ${esc(S.orders.progress || "Loading…")}`}</div>`;
      return;
    }
    const now = Date.now();
    const stale = L.openOffers(ledger()).filter((o) => o.side === "buy" && now - o.placedAt > OSF.decide.PRESET.buyWaitHours * 36e5);
    const room = (d.makeRoom || []).filter((o) => !stale.some((x) => x.id === o.id) && L.openOffers(ledger()).some((x) => x.id === o.id));
    const tidy = [...stale.map((o) => ({ o, why: `up ${U.duration((now - o.placedAt) / 36e5)}` })), ...room.map((o) => ({ o, why: "frees a slot to sell what you hold" }))];
    const holds = [...d.holds].sort((a, b) => ({ cut: 0, sell: 1, list: 2, hold: 3, wait: 4 }[a.act.action] - { cut: 0, sell: 1, list: 2, hold: 3, wait: 4 }[b.act.action]));
    const plan = d.plan.plan;
    const sellRows = holds.map((h) => `<div class="pick">
        ${icon(h.itemId)}
        <div style="min-width:0"><div class="name" data-item="${h.itemId}">${esc(h.name || itemName(h.itemId))}</div>
          <div class="line">You hold ${U.full(h.qty)} @ ${U.full(Math.round(h.avgCost))} · held ${U.duration(h.heldHours)}</div>
          ${h.listing ? `<div class="line muted">Listed: ${U.full(h.listing.qty - h.listing.filled)} @ ${U.full(h.listing.price)}</div>` : ""}</div>
        <div class="right"></div>
        <div class="advice ${h.act.tone}" style="grid-column:2 / -1">${esc(h.act.label)}${h.listing && h.act.price && Math.abs(h.listing.price - h.act.price) / h.act.price > 0.005
          ? ` (your listing is at ${U.full(h.listing.price)}: cancel it under Your open GE offers to relist)` : ""}</div>
        <div class="actions">${h.listing ? `<button class="btn sm" data-fill="${h.listing.id}">Record sale</button>` : h.act.price ? (h.act.action === "cut"
          ? `<button class="btn sm primary" data-log-trade="1" data-item-id="${h.itemId}" data-side="sell" data-qty="${h.qty}" data-price="${h.act.price}">I sold it</button>`
          : `<button class="btn sm primary" data-order-sell="${h.itemId}" data-price="${h.act.price}">I listed it</button>
             <button class="btn sm" data-log-trade="1" data-item-id="${h.itemId}" data-side="sell" data-qty="${h.qty}" data-price="${h.act.price}">Already sold</button>`) : ""}</div>
      </div>`).join("");
    const openRows = L.openOffers(ledger()).sort((a, b) => a.placedAt - b.placedAt).map((o) => `<div class="row" style="padding:8px 0;border-top:1px solid var(--border)">
        <span class="side ${o.side}">${o.side.toUpperCase()}</span>
        <span class="grow"><b data-item="${o.itemId}" style="cursor:pointer">${esc(itemName(o.itemId))}</b>
          <span class="small muted">${U.full(o.filled)}/${U.full(o.qty)} @ ${U.full(o.price)} · ${U.ago(o.placedAt)}</span></span>
        <button class="btn sm" data-fill="${o.id}">Record fill</button>
        <button class="btn sm danger" data-cancel="${o.id}">${o.filled ? "Close" : "Cancel"}</button></div>`).join("");
    const buyRows = plan.map((r) => `<div class="pick">
        ${icon(r.id)}
        <div style="min-width:0"><div class="name" data-item="${r.id}">${esc(r.name)}</div>
          <div class="line"><b>Buy ${U.full(r.qty)} @ ${U.full(r.buy)}</b> → sell ~${U.full(r.sellTarget)}</div>
          <div class="line muted">uses ${U.gp(r.cost)} · ${esc(r.reasons[0])}</div>
          ${r.drift ? `<div class="advice warn" style="margin-top:4px">${esc(r.drift)}</div>` : ""}</div>
        <div class="right"><div class="profit good">${U.gp(r.expected, { plus: true })}</div><div class="line">~${U.duration(r.hours)}</div></div>
        <div class="actions"><button class="btn sm primary ${isPractice() ? "practice" : ""}" data-order-buy="${r.id}">${isPractice() ? "Practice buy" : "I placed it"}</button></div>
      </div>`).join("");
    root.innerHTML = head + `
      ${tidy.length ? `<div class="section-head"><h2>Tidy up</h2></div><div class="card">${tidy.map(({ o, why }) => `<div class="row" style="padding:6px 0">
          <span class="grow">Cancel the unfilled part of <b>${esc(itemName(o.itemId))}</b> (${U.full(o.filled)}/${U.full(o.qty)} filled, ${why})</span>
          <button class="btn sm" data-cancel="${o.id}">Cancelled it</button></div>`).join("")}</div>` : ""}
      <div class="section-head"><h2>Your items</h2><span class="small muted">${holds.length ? "sell, hold or cut" : ""}</span></div>
      <div class="card">${sellRows || `<div class="empty-state">Nothing held.</div>`}</div>
      <div class="section-head"><h2>Your open GE offers</h2><span class="small muted">update these when they fill</span><span class="spacer"></span>
        <label class="btn sm" title="Import finished GE offers from RuneLite: the GE Flipper plugin or Flipping Utilities">Import RuneLite<input type="file" accept=".json,application/json" class="import-fu" hidden></label></div>
      <div class="card">${openRows || `<div class="empty-state">No open offers recorded.</div>`}</div>
      ${plan.length ? `<div class="card" style="margin-top:12px"><b class="good">${U.gp(U.sum(plan.map((r) => r.expected)), { plus: true })}</b> expected if these buy orders fill and sell,
        using ${U.gp(d.plan.deployUsed)}. Check back in about ${U.duration(Math.min(6, Math.max(...plan.map((r) => r.hours))))}
        (around ${new Date(Date.now() + Math.min(6, Math.max(...plan.map((r) => r.hours))) * 36e5).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}) to list what filled.</div>` : ""}
      <div class="section-head"><h2>New buy offers</h2><span class="small muted">${plan.length} for ${d.buySlots ?? d.cap.slotsFree} free slots${d.buySlots < d.cap.slotsFree ? " (the rest are kept for selling)" : ""} · ${U.gp(d.plan.deployUsed)} of ${U.gp(d.cap.deployable)} free gp${d.ctx.mkt_ret_24h < -0.03 ? " · market falling: half size" : ""}</span></div>
      <div class="card">${buyRows || `<div class="empty-state">${(d.buySlots ?? d.cap.slotsFree) ? `Nothing worth buying right now: no order is expected to make ${U.gp(settings().minOrderProfit || 0)} or more. Empty slots are fine; check back later.` : "No free slots for new buys: list what you hold first."}</div>`}</div>`;
  }

  function orderBuy(id) {
    const r = S.orders.data && S.orders.data.plan.plan.find((x) => x.id === id);
    if (!r) return;
    offerDialog({ itemId: id, side: "buy", price: r.buy, qty: r.qty,
      predicted: { buy: r.buy, sell: r.sellTarget, expRet: r.expRet, qty: r.qty, hours: r.hours, expected: r.expected, model: "full-year-v1/production", at: Date.now() } });
  }

  function orderSell(id, price) {
    const h = L.positions(ledger().trades).holdings.get(id);
    if (!h) return;
    offerDialog({ itemId: id, side: "sell", price, qty: h.qty });
  }

  // ---------------------------------------------------------------- slots
  function renderSlots() {
    const root = $("#view-slots");
    const open = L.openOffers(ledger()).sort((a, b) => a.placedAt - b.placedAt);
    const s = settings();
    const mk = S.result && S.result.mk;
    const cards = open.map((o) => {
      const adv = mk ? (o.side === "buy" ? M.adviseOffer(o, mk) : null) : null;
      const pct = o.filled / o.qty;
      return `<div class="slot">
        <div class="row"><span class="side ${o.side}">${o.side.toUpperCase()}</span><b class="grow name" data-item="${o.itemId}" style="cursor:pointer">${esc(itemName(o.itemId))}</b><span class="small muted">${U.ago(o.placedAt)}</span></div>
        <div class="num">${U.full(o.filled)} / ${U.full(o.qty)} @ ${U.full(o.price)} <span class="muted small">(${U.gp(o.qty * o.price)})</span></div>
        <div class="bar"><i style="width:${(pct * 100).toFixed(1)}%"></i></div>
        ${adv ? `<div class="advice ${adv.tone}">${esc(adv.label)}</div>` : ""}
        <div class="row" style="margin-top:auto">
          ${isPractice() ? `<span class="small muted grow">Fills automatically from market data</span>` : `<button class="btn sm" data-fill="${o.id}">Record fill</button>`}
          <button class="btn sm" data-edit-offer="${o.id}">Edit</button>
          <button class="btn sm danger" data-cancel="${o.id}">${o.filled ? "Close" : "Cancel"}</button>
        </div>
      </div>`;
    });
    for (let i = open.length; i < s.slots; i++) cards.push(`<div class="slot empty" data-new-offer="1">+ Empty slot</div>`);
    const recent = ledger().offers.filter((o) => o.status !== "open").sort((a, b) => (b.closedAt || 0) - (a.closedAt || 0)).slice(0, 8);
    root.innerHTML = `
      <div class="section-head"><h2>GE slots</h2><span class="spacer"></span>
        ${isPractice() ? `<button class="btn sm" id="sync">Check fills now</button>` : ""}
        <button class="btn sm primary ${isPractice() ? "practice" : ""}" data-new-offer="1">New offer</button></div>
      <div class="slots">${cards.join("")}</div>
      ${recent.length ? `<div class="section-head"><h2>Recently closed</h2></div><div class="card table-wrap"><table class="list"><tbody>
        ${recent.map((o) => `<tr><td><span class="side ${o.side}">${o.side.toUpperCase()}</span></td><td class="name" data-item="${o.itemId}">${esc(itemName(o.itemId))}</td>
          <td class="r">${U.full(o.filled)}/${U.full(o.qty)} @ ${U.full(o.price)}</td><td class="r muted">${o.status}</td><td class="r muted">${o.closedAt ? U.ago(o.closedAt) : ""}</td></tr>`).join("")}
      </tbody></table></div>` : ""}`;
  }

  // ---------------------------------------------------------------- holdings
  function renderHoldings() {
    const root = $("#view-holdings");
    const { holdings } = L.positions(ledger().trades);
    const openSells = L.openOffers(ledger()).filter((o) => o.side === "sell");
    // Same model advice as Orders (one source of truth), refreshed when holdings change.
    const d = S.orders.data;
    const fresh = d && Date.now() - d.at < 15 * 6e4 && [...holdings.keys()].every((id) => d.holds.some((x) => x.itemId === id));
    if (!fresh && !S.orders.loading && holdings.size) setTimeout(loadOrders, 0);
    const rows = [...holdings.values()].map((h) => {
      const m = d && d.holds.find((x) => x.itemId === h.itemId && x.qty === h.qty);
      const adv = m ? { marketSell: m.ask, breakeven: U.breakevenSellPrice(h.avgCost, h.itemId), pnl: m.pnl,
        pnlPct: m.pnl / (h.avgCost * h.qty), heldHours: m.heldHours, label: m.act.label, tone: m.act.tone } : null;
      const listed = openSells.filter((o) => o.itemId === h.itemId).reduce((a, o) => a + o.qty - o.filled, 0);
      return { h, adv, listed };
    }).sort((a, b) => (b.h.cost) - (a.h.cost));
    const total = U.sum(rows.map((r) => (r.adv && isFinite(r.adv.pnl) ? r.adv.pnl : 0)));

    root.innerHTML = `<div class="section-head"><h2>Holdings</h2>
        <span class="small muted">if sold at market now: <b class="${signClass(total)}">${U.gp(total, { plus: true })}</b> after tax</span>
        <span class="spacer"></span><button class="btn sm" data-log-trade="1">Log trade</button></div>
      ${rows.length ? rows.map(({ h, adv, listed }) => `<div class="card">
        <div class="row">${icon(h.itemId)}<b class="grow name" data-item="${h.itemId}" style="cursor:pointer">${esc(itemName(h.itemId))}</b>
          <span class="num">${U.full(h.qty)} × ${U.full(Math.round(h.avgCost))}</span></div>
        ${adv ? `<dl class="kv" style="margin:8px 0">
          <dt>Market sell price</dt><dd>${adv.marketSell ? U.full(adv.marketSell) : "–"}</dd>
          <dt>Breakeven (after tax)</dt><dd>${U.full(adv.breakeven)}</dd>
          <dt>P/L if sold now</dt><dd class="${signClass(adv.pnl)}">${adv.pnl == null ? "–" : `${U.gp(adv.pnl, { plus: true })} (${U.pct(adv.pnlPct)})`}</dd>
          <dt>Held</dt><dd>${U.duration(adv.heldHours)}</dd>
          ${listed ? `<dt>Listed for sale</dt><dd>${U.full(listed)}</dd>` : ""}
        </dl><div class="advice ${adv.tone}">${esc(adv.label)}</div>` : `<p class="muted small"><span class="spinner"></span> Running the model for sell advice…</p>`}
        <div class="row" style="margin-top:8px">
          ${listed < h.qty ? `<button class="btn sm primary ${isPractice() ? "practice" : ""}" data-sell="${h.itemId}">${isPractice() ? "Practice sell" : "List for sale"}</button>` : ""}
          ${!isPractice() ? `<button class="btn sm" data-log-trade="1" data-item-id="${h.itemId}" data-side="sell">Log sale</button>` : ""}
        </div></div>`).join("") : `<div class="card empty-state">Nothing held. Bought items show up here with sell advice.</div>`}`;
  }

  // ---------------------------------------------------------------- profit
  function renderProfit() {
    const root = $("#view-profit");
    const st = L.stats(ledger());
    const days = st.byDay.slice(-30);
    const trades = [...ledger().trades].sort((a, b) => b.ts - a.ts).slice(0, 150);
    root.innerHTML = `
      <div class="section-head"><h2>Profit — ${isPractice() ? "practice" : "real"}</h2><span class="spacer"></span>
        <button class="btn sm" data-log-trade="1">Log trade</button>
        <button class="btn sm" id="export-csv">Export CSV</button>
        <label class="btn sm">Import CSV<input type="file" accept=".csv,text/csv" id="import-csv" hidden></label>
        <label class="btn sm">Import RuneLite<input type="file" accept=".json,application/json" class="import-fu" hidden></label></div>
      <div class="capital" style="padding:0;grid-template-columns:repeat(auto-fill,minmax(150px,1fr))">
        <div class="stat"><div class="label">Realised</div><div class="value ${signClass(st.profit)}">${U.gp(st.profit, { plus: true })}</div><div class="sub">after ${U.gp(st.tax)} tax</div></div>
        <div class="stat"><div class="label">Return on cost</div><div class="value">${U.pct(st.roi, 2)}</div><div class="sub">${st.flips} closed sells</div></div>
        <div class="stat"><div class="label">Win rate</div><div class="value">${U.pct(st.winRate, 0)}</div><div class="sub">avg hold ${U.duration(st.avgHoldHours)}</div></div>
        <div class="stat"><div class="label">Today</div><div class="value ${signClass(st.today)}">${U.gp(st.today, { plus: true })}</div><div class="sub">${new Date().toLocaleDateString()}</div></div>
      </div>
      <div class="section-head"><h2>Daily profit</h2><span class="small muted">last 30 days with closed flips</span></div>
      <div class="card"><div id="day-chart"></div></div>
      ${st.unmatched.length ? `<div class="card advice warn" style="margin-top:10px">${st.unmatched.length} sale(s) have no matching purchase in this ledger, so they aren't counted. Log the buys to include them.</div>` : ""}
      ${calibrationHtml(st)}
      <div class="section-head"><h2>By item</h2></div>
      <div class="card table-wrap">${st.byItem.length ? `<table class="list"><thead><tr><th>Item</th><th class="r">Profit</th><th class="r">Sold</th><th class="r">ROI</th><th class="r">Wins</th><th class="r">Tax</th></tr></thead><tbody>
        ${st.byItem.map((r) => `<tr><td class="name" data-item="${r.itemId}">${esc(itemName(r.itemId))}</td><td class="r ${signClass(r.profit)}">${U.gp(r.profit, { plus: true })}</td>
        <td class="r">${U.full(r.qty)}</td><td class="r">${U.pct(r.profit / r.cost, 2)}</td><td class="r">${r.wins}/${r.flips}</td><td class="r">${U.gp(r.tax)}</td></tr>`).join("")}
      </tbody></table>` : `<div class="empty-state">No closed flips yet.</div>`}</div>
      <div class="section-head"><h2>Trade log</h2><span class="small muted">${ledger().trades.length} trades</span></div>
      <div class="card table-wrap">${trades.length ? `<table class="list"><thead><tr><th>When</th><th>Side</th><th>Item</th><th class="r">Qty</th><th class="r">Price</th><th class="r">Total</th><th></th></tr></thead><tbody>
        ${trades.map((t) => `<tr><td class="muted">${new Date(t.ts).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}</td>
          <td><span class="side ${t.side}">${t.noTax ? "ALCH" : t.side.toUpperCase()}</span></td><td class="name" data-item="${t.itemId}">${esc(itemName(t.itemId))}</td>
          <td class="r">${U.full(t.qty)}</td><td class="r">${U.full(t.price)}</td><td class="r">${U.gp(t.qty * t.price)}</td>
          <td class="r"><button class="btn sm danger" data-del-trade="${t.id}" title="Delete">✕</button></td></tr>`).join("")}
      </tbody></table>` : `<div class="empty-state">No trades yet.</div>`}</div>`;
    CH.barChart($("#day-chart"), days);
  }

  /** Model's predicted return (saved with each recommended offer) vs the realised return per item. */
  function calibrationHtml(st) {
    const pred = new Map();
    for (const o of ledger().offers) {
      if (o.side !== "buy" || !o.predicted || o.predicted.expRet == null || !o.filled) continue;
      const p = pred.get(o.itemId) || { w: 0, sum: 0, n: 0 };
      p.w += o.filled; p.sum += o.filled * o.predicted.expRet; p.n++;
      pred.set(o.itemId, p);
    }
    const rows = st.byItem.filter((r) => pred.has(r.itemId)).map((r) => {
      const p = pred.get(r.itemId);
      return { ...r, predRet: p.sum / p.w, actRet: r.profit / r.cost };
    });
    if (!rows.length) return "";
    const cost = U.sum(rows.map((r) => r.cost));
    const predAvg = U.sum(rows.map((r) => r.predRet * r.cost)) / cost;
    const actAvg = U.sum(rows.map((r) => r.profit)) / cost;
    return `<div class="section-head"><h2>Model vs your results</h2><span class="small muted">trades placed from Orders</span></div>
      <div class="card">
        <p class="small secondary" style="margin-top:0">Predicted ${U.pct(predAvg, 2)} per flip after tax, you got <b class="${signClass(actAvg)}">${U.pct(actAvg, 2)}</b>
        across ${rows.length} item${rows.length > 1 ? "s" : ""}. ${actAvg >= predAvg * 0.7 ? "Tracking close to the model." : "Below the model so far — fills or prices are worse than it assumes; this feeds the next retrain."}</p>
        <div class="table-wrap"><table class="list"><thead><tr><th>Item</th><th class="r">Predicted</th><th class="r">Actual</th><th class="r">Profit</th></tr></thead><tbody>
        ${rows.map((r) => `<tr><td class="name" data-item="${r.itemId}">${esc(itemName(r.itemId))}</td><td class="r">${U.pct(r.predRet, 2)}</td>
          <td class="r ${signClass(r.actRet)}">${U.pct(r.actRet, 2)}</td><td class="r ${signClass(r.profit)}">${U.gp(r.profit, { plus: true })}</td></tr>`).join("")}
        </tbody></table></div></div>`;
  }

  // ---------------------------------------------------------------- trade file on your PC
  /** Compares this browser's copy with the file's and restores, asks, or updates the file. */
  async function syncTradeFile() {
    let file;
    try { file = await TF.read(); } catch (e) { toast("Couldn't read your trade file: " + esc(e.message)); return; }
    const r = TF.reconcile(S.state, file);
    const ago = file && file.savedAt ? ` (saved ${U.ago(file.savedAt)})` : "";
    if (r.action === "restore"
      || (r.action === "ask" && confirm(`Your trade file${ago} is newer than this browser's copy.\n\nFile: ${r.file} trades and offers\nThis browser: ${r.local}\n\nOK loads the file. Cancel keeps this browser's copy and saves it over the file.`))) {
      S.state = file;
      ST.save(S.state);
      renderAll();
      refresh({ quiet: true });
      toast(`Restored your trade history from ${esc(TF.fileName())} (${r.file} entr${r.file === 1 ? "y" : "ies"})`);
      return;
    }
    TF.schedule(S.state, 0); // bring the file up to date
  }

  function renderFileBanner() {
    const el = $("#file-banner");
    if (!el) return;
    const st = TF.T.status;
    const show = st === "needs-permission" || st === "error";
    el.classList.toggle("hidden", !show);
    el.innerHTML = !show ? "" : st === "needs-permission"
      ? `<div><span class="grow">Your trades aren't being saved to <b>${esc(TF.fileName())}</b> this visit: the browser needs your OK again.</span><button class="btn sm primary" id="tf-reconnect">Reconnect file</button></div>`
      : `<div><span class="grow">Couldn't save to your trade file: ${esc(TF.T.error || "unknown error")}. Your trades are still saved in this browser.</span><button class="btn sm" id="tf-reconnect">Try again</button></div>`;
  }

  function tradeFileCard() {
    const st = TF.T.status;
    const intro = `<p class="small secondary" style="margin-top:0">Keep a copy of every trade in a file on your computer, on any drive (for example <b>D:\\GE-Flipper</b>). It's rewritten every time you log something, and if this browser's data is ever cleared, the app loads your trades back from it.</p>`;
    if (st === "unsupported")
      return `<div class="card">${intro}<p class="small" style="margin-bottom:0">This browser can't save to a file by itself. That works in <b>Chrome</b> or <b>Edge</b> on a computer. Here, use <b>Export backup</b> below now and then.</p></div>`;
    if (st === "off")
      return `<div class="card">${intro}<div class="row"><button class="btn primary" id="tf-create">Save trades to a file…</button><button class="btn" id="tf-open">Use an existing file…</button></div></div>`;
    const when = TF.T.lastWrite ? `last saved ${U.ago(TF.T.lastWrite)}` : "connected";
    const line = st === "ok" ? `<span class="good">●</span> Saving to <b>${esc(TF.fileName())}</b>, ${when}.`
      : st === "needs-permission" ? `<span class="bad">●</span> Paused: the browser needs your OK to keep writing <b>${esc(TF.fileName())}</b>.`
      : `<span class="bad">●</span> ${esc(TF.T.error || "Couldn't write the file")}.`;
    return `<div class="card">${intro}<p class="small" style="margin:0 0 8px">${line}</p><div class="row">
      ${st !== "ok" ? `<button class="btn primary" id="tf-reconnect">Reconnect file</button>` : ""}
      <button class="btn" id="tf-create">Use a different file…</button><span class="spacer"></span><button class="btn danger" id="tf-stop">Stop saving to the file</button></div></div>`;
  }

  async function tradeFileAction(id) {
    try {
      if (id === "tf-create") await TF.create();
      else if (id === "tf-open") await TF.openExisting();
      else if (id === "tf-reconnect") await TF.reconnect();
      else if (id === "tf-stop") {
        if (!confirm(`Stop saving to ${TF.fileName()}? The file stays on your computer; it just won't be updated.`)) return;
        await TF.disconnect();
        return toast("Stopped saving to the file");
      }
      if (TF.T.status === "ok") await syncTradeFile();
    } catch (e) {
      if (e && e.name === "AbortError") return; // closed the file dialog
      toast("Trade file: " + esc((e && e.message) || e));
    }
  }

  // ---------------------------------------------------------------- settings
  function renderSettings() {
    const s = settings();
    const num = (k, label, step, scale = 1, suffix = "") => `<label class="field">${label}${suffix ? ` <span class="muted">(${suffix})</span>` : ""}
      <input type="text" inputmode="decimal" data-set="${k}" data-scale="${scale}" value="${scale === 1 ? (k.includes("ankroll") || k === "minOrderProfit" ? U.gp(s[k]).toLowerCase() : s[k]) : +(s[k] * scale).toFixed(4)}" data-step="${step}"></label>`;
    $("#view-settings").innerHTML = `
      <div class="section-head"><h2>Money</h2></div>
      <div class="card"><div class="grid2">
        ${num("bankroll", "Real starting stack", 1, 1, "e.g. 11m")}
        ${num("practiceBankroll", "Practice stack", 1, 1)}
        ${num("cashReservePct", "Cash reserve", 1, 100, "% never used")}
        ${num("maxPerItemPct", "Max in one item", 1, 100, "% of free gp")}
      </div>
      <label class="check"><input type="checkbox" data-set="members" ${s.members ? "checked" : ""}> Members account</label></div>
      <div class="section-head"><h2>Risk & filters</h2></div>
      <div class="card"><div class="grid2">
        ${num("slots", "GE slots the tool uses", 1, 1, "of 8; the rest stay free for your own trades")}
        ${num("minOrderProfit", "Min profit per order", 1, 1, "gp expected; smaller orders aren't suggested")}
        ${num("stopLossPct", "Stop-loss", 1, 100, "%")}
        ${num("maxHoldHours", "Max hold time", 1, 1, "hours")}
        ${num("minRoiPct", "Min return after tax", 0.1, 1, "%")}
        ${num("minMarginGp", "Min profit per item", 1, 1, "gp")}
        ${num("refreshSeconds", "Refresh every", 10, 1, "seconds")}
      </div></div>
      <div class="section-head"><h2>Hidden items</h2></div>
      <div class="card">${(s.excludedIds || []).length ? s.excludedIds.map((id) => `<div class="row" style="padding:4px 0"><span class="grow">${esc(itemName(id))}</span><button class="btn sm" data-unexclude="${id}">Show again</button></div>`).join("") : `<span class="muted small">None. Use "Never suggest" on an item's detail page.</span>`}</div>
      <div class="section-head"><h2>Trade file on your PC</h2></div>
      ${tradeFileCard()}
      <div class="section-head"><h2>Backup</h2></div>
      <div class="card"><p class="small secondary" style="margin-top:0">Everything is also saved in this browser. Export a backup to keep a dated copy or move to another device.</p>
        <div class="row"><button class="btn" id="export-json">Export backup</button>
        <label class="btn">Import backup<input type="file" accept=".json,application/json" id="import-json" hidden></label>
        <span class="spacer"></span>
        <button class="btn danger" id="reset-practice">Reset practice ledger</button></div></div>`;
  }

  // ---------------------------------------------------------------- dialogs
  function dialog(html, onMount) {
    const ov = document.createElement("div");
    ov.className = "overlay";
    ov.innerHTML = `<div class="dialog" role="dialog" aria-modal="true">${html}</div>`;
    document.body.append(ov);
    const close = () => ov.remove();
    ov.addEventListener("click", (e) => { if (e.target === ov || e.target.closest("[data-close]")) close(); });
    document.addEventListener("keydown", function esc(e) { if (e.key === "Escape") { close(); document.removeEventListener("keydown", esc); } });
    if (onMount) onMount(ov.firstElementChild, close);
    return close;
  }

  /** Item picker: binds an input to the mapping with autocomplete; calls onPick(id). */
  function bindItemSearch(input, box, onPick) {
    const names = [...S.items.values()].filter((it) => settings().members || !it.members);
    input.addEventListener("input", () => {
      const q = input.value.trim().toLowerCase();
      if (q.length < 2) { box.classList.add("hidden"); return; }
      const hits = names.filter((it) => it.name.toLowerCase().includes(q))
        .sort((a, b) => (b.name.toLowerCase().startsWith(q) - a.name.toLowerCase().startsWith(q)) || a.name.length - b.name.length).slice(0, 8);
      box.innerHTML = hits.map((it) => `<div data-id="${it.id}">${esc(it.name)}</div>`).join("") || `<div class="muted">No match</div>`;
      box.classList.remove("hidden");
    });
    box.addEventListener("click", (e) => {
      const d = e.target.closest("[data-id]");
      if (!d) return;
      input.value = itemName(+d.dataset.id);
      box.classList.add("hidden");
      onPick(+d.dataset.id);
    });
  }

  /**
   * Place (or edit) a GE offer. pre: { itemId, side, price, qty, predicted, offerId }
   */
  function offerDialog(pre = {}) {
    const editing = pre.offerId && ledger().offers.find((o) => o.id === pre.offerId);
    let itemId = pre.itemId || (editing && editing.itemId) || null;
    const side = pre.side || (editing && editing.side) || "buy";
    const lim = () => {
      const it = S.items.get(itemId);
      if (!it || !it.limit) return "";
      const used = L.buyLimitUsage(ledger().trades, itemId).used;
      return `Buy limit ${U.full(it.limit)} / 4h · ${U.full(used)} used`;
    };
    dialog(`<h3>${editing ? "Edit offer" : isPractice() ? "Practice offer" : "Record a GE offer"}</h3>
      ${isPractice() ? `<p class="small secondary">Mock offer: it fills only when real trades happen at your price.</p>`
        : `<p class="small secondary">Place this offer in-game first, then save it here so the app can track the slot.</p>`}
      <label class="field">Item<input id="f-item" autocomplete="off" value="${itemId ? esc(itemName(itemId)) : ""}" ${editing ? "disabled" : ""} placeholder="Start typing an item name"></label>
      <div class="suggest hidden" id="f-suggest"></div>
      <div class="seg" style="margin-bottom:10px"><button data-side="buy" class="${side === "buy" ? "on" : ""}" ${editing ? "disabled" : ""}>Buy</button><button data-side="sell" class="${side === "sell" ? "on" : ""}" ${editing ? "disabled" : ""}>Sell</button></div>
      <div class="grid2">
        <label class="field">Price each<input id="f-price" inputmode="decimal" value="${pre.price || (editing && editing.price) || ""}"></label>
        <label class="field">Quantity<input id="f-qty" inputmode="decimal" value="${pre.qty || (editing && editing.qty) || ""}"></label>
      </div>
      <div class="small muted" id="f-info"></div>
      ${!isPractice() && !editing ? `<label class="check"><input type="checkbox" id="f-done"> Already completed (log as a finished trade)</label>` : ""}
      <div class="row" style="margin-top:12px"><span class="spacer"></span><button class="btn" data-close>Cancel</button>
        <button class="btn primary ${isPractice() ? "practice" : ""}" id="f-save">${editing ? "Save" : "Place offer"}</button></div>`,
    (root, close) => {
      let sideNow = side;
      const info = () => {
        const p = U.parseGp($("#f-price", root).value), q = U.parseGp($("#f-qty", root).value);
        const parts = [];
        if (p > 0 && q > 0) parts.push(`Total ${U.gp(p * q)}`);
        if (sideNow === "buy" && itemId) parts.push(lim());
        if (sideNow === "sell" && p > 0) parts.push(`You receive ${U.full(U.netSellPrice(p, itemId))} each after tax`);
        $("#f-info", root).textContent = parts.join(" · ");
      };
      bindItemSearch($("#f-item", root), $("#f-suggest", root), (id) => { itemId = id; info(); });
      root.querySelectorAll("[data-side]").forEach((b) => b.addEventListener("click", () => {
        sideNow = b.dataset.side;
        root.querySelectorAll("[data-side]").forEach((x) => x.classList.toggle("on", x === b));
        info();
      }));
      root.addEventListener("input", info);
      info();
      $("#f-save", root).addEventListener("click", () => {
        const price = U.parseGp($("#f-price", root).value), qty = U.parseGp($("#f-qty", root).value);
        if (!itemId) return toast("Pick an item first");
        if (!(price > 0) || !(qty > 0)) return toast("Enter a price and quantity");
        if (editing) {
          if (qty < editing.filled) return toast("Quantity can't be below what's already filled");
          editing.price = price; editing.qty = qty;
          if (editing.filled >= qty) { editing.status = "done"; editing.closedAt = Date.now(); }
        } else if ($("#f-done", root) && $("#f-done", root).checked) {
          L.addTrade(ledger(), { itemId, side: sideNow, qty, price });
        } else {
          if (L.openOffers(ledger()).length >= settings().slots) return toast(`All ${settings().slots} slots are in use`);
          L.placeOffer(ledger(), { itemId, side: sideNow, qty, price, predicted: pre.predicted, strategy: settings().strategy });
        }
        save(); close();
        toast(editing ? "Offer updated" : `${isPractice() ? "Practice " : ""}${sideNow} offer saved`);
        if (S.orders.data) {
          S.orders.data.plan.plan = S.orders.data.plan.plan.filter((r) => r.id !== itemId);
          S.orders.data.holds = S.orders.data.holds.filter((h) => !(sideNow === "sell" && h.itemId === itemId));
        }
        renderAll();
      });
    });
  }

  function fillDialog(offerId) {
    const o = ledger().offers.find((x) => x.id === offerId);
    if (!o) return;
    const left = o.qty - o.filled;
    dialog(`<h3>Record fill — ${esc(itemName(o.itemId))}</h3>
      <p class="small secondary">${o.side === "buy" ? "Bought" : "Sold"} so far: ${U.full(o.filled)} / ${U.full(o.qty)}</p>
      <div class="grid2"><label class="field">Quantity filled now<input id="x-qty" inputmode="decimal" value="${left}"></label>
      <label class="field">Price each<input id="x-price" inputmode="decimal" value="${o.price}"></label></div>
      <div class="row"><span class="spacer"></span><button class="btn" data-close>Cancel</button><button class="btn primary" id="x-save">Save</button></div>`,
    (root, close) => {
      $("#x-save", root).addEventListener("click", () => {
        const q = U.parseGp($("#x-qty", root).value), p = U.parseGp($("#x-price", root).value);
        if (!(q > 0) || !(p > 0)) return toast("Enter a quantity and price");
        L.fillOffer(ledger(), o.id, q, Date.now(), p);
        save(); close(); renderAll();
        if (o.status === "done" && o.side === "buy") toast(`Buy complete. See <b>Holdings</b> for where to sell.`);
      });
    });
  }

  function tradeDialog(pre = {}) {
    let itemId = pre.itemId || null;
    let side = pre.side || "buy";
    const local = new Date(Date.now() - new Date().getTimezoneOffset() * 6e4).toISOString().slice(0, 16);
    dialog(`<h3>Log a completed trade</h3>
      <p class="small secondary">Goes into the <b>${isPractice() ? "practice" : "real"}</b> ledger.</p>
      <label class="field">Item<input id="t-item" autocomplete="off" value="${itemId ? esc(itemName(itemId)) : ""}" placeholder="Start typing an item name"></label>
      <div class="suggest hidden" id="t-suggest"></div>
      <div class="seg" style="margin-bottom:10px"><button data-side="buy" class="${side === "buy" ? "on" : ""}">Bought</button><button data-side="sell" class="${side === "sell" ? "on" : ""}">Sold</button></div>
      <div class="grid2">
        <label class="field">Price each<input id="t-price" inputmode="decimal" placeholder="e.g. 1.2k" value="${pre.price || ""}"></label>
        <label class="field">Quantity<input id="t-qty" inputmode="decimal" value="${pre.qty || ""}"></label>
      </div>
      <label class="field">When<input id="t-when" type="datetime-local" value="${local}"></label>
      <div class="row"><span class="spacer"></span><button class="btn" data-close>Cancel</button><button class="btn primary" id="t-save">Save trade</button></div>`,
    (root, close) => {
      bindItemSearch($("#t-item", root), $("#t-suggest", root), (id) => { itemId = id; });
      root.querySelectorAll("[data-side]").forEach((b) => b.addEventListener("click", () => {
        side = b.dataset.side; root.querySelectorAll("[data-side]").forEach((x) => x.classList.toggle("on", x === b));
      }));
      $("#t-save", root).addEventListener("click", () => {
        const price = U.parseGp($("#t-price", root).value), qty = U.parseGp($("#t-qty", root).value);
        const ts = Date.parse($("#t-when", root).value) || Date.now();
        if (!itemId) return toast("Pick an item first");
        if (!(price > 0) || !(qty > 0)) return toast("Enter a price and quantity");
        L.addTrade(ledger(), { itemId, side, qty, price, ts });
        if (S.orders.data && side === "sell") S.orders.data.holds = S.orders.data.holds.filter((h) => h.itemId !== itemId);
        save(); close(); renderAll(); toast("Trade logged");
      });
    });
  }

  async function itemDialog(id) {
    const r = S.result && S.result.recs.find((x) => x.id === id);
    const it = S.items.get(id) || {};
    const lim = L.buyLimitUsage(ledger().trades, id);
    const rec = L.stats(ledger()).byItem.find((x) => x.itemId === id);
    const q = S.result ? M.quotePrices(id, S.result.mk) : null;
    const br = r ? `<dl class="kv">
        <dt>Buy at / sell at</dt><dd>${U.full(r.buy)} / ${U.full(r.sell)}</dd>
        <dt>Profit per item after tax</dt><dd>${U.full(r.margin)} (${U.pct(r.roi, 2)})</dd>
        <dt>Quantity (limited by ${esc(r.binding)})</dt><dd>${U.full(r.qty)}</dd>
        <dt>Gross if everything fills</dt><dd>${U.gp(r.gross)}</dd>
        <dt>Margin reliability</dt><dd>${r.hist.persistence != null ? U.pct(r.persistence, 0) : "unknown (assumed 60%)"}</dd>
        <dt>Risk charge (volatility ${U.pct(r.sigma, 2)}/h)</dt><dd class="bad">−${U.gp(r.riskCost)}</dd>
        <dt>Trend adjustment</dt><dd class="${signClass(r.drift)}">${U.gp(r.drift, { plus: true })}</dd>
        <dt><b>Expected profit</b></dt><dd><b class="good">${U.gp(r.expected, { plus: true })}</b></dd>
        <dt>Time to buy and sell</dt><dd>~${U.duration(r.hours)}</dd>
        <dt>Trades per day</dt><dd>${U.gp(r.vol.daily)}${r.vol.dailyEstimated ? " (est.)" : ""}</dd>
        ${r.hist.z7d != null ? `<dt>vs 7-day average</dt><dd>z = ${r.hist.z7d.toFixed(2)}</dd>` : ""}
        ${r.hist.trend7d != null ? `<dt>7-day trend</dt><dd>${U.pct(r.hist.trend7d * 24, 2)}/day</dd>` : ""}
      </dl>
      <ul class="small secondary">${r.reasons.map((x) => `<li>${esc(x)}</li>`).join("")}</ul>
      ${r.flags.length ? `<div class="chips" style="margin-bottom:8px">${flagChips(r)}</div>` : ""}`
      : `<p class="small muted">Not in the current picks${q ? ` — latest instant-buy ${U.full(q.rawHigh)}, instant-sell ${U.full(q.rawLow)}` : ""}.</p>`;
    dialog(`<div class="row">${icon(id)}<h3 class="grow" style="margin:0">${esc(it.name || "Item " + id)}${r ? `<span class="grade ${r.grade}">${r.grade}</span>` : ""}</h3><button class="btn sm" data-close>Close</button></div>
      <p class="small muted">${esc(it.examine || "")}</p>
      <div class="row small secondary" style="margin-bottom:6px">
        <span>Buy limit ${it.limit ? U.full(it.limit) : "?"}/4h${lim.used ? ` · ${U.full(lim.used)} used, resets ${new Date(lim.resetsAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` : ""}</span>
        ${it.highalch ? `<span>· High alch ${U.full(it.highalch)}</span>` : ""}
        ${rec ? `<span>· Your record: ${U.gp(rec.profit, { plus: true })} over ${rec.flips} sells</span>` : ""}
      </div>
      <div class="seg" id="range" style="margin:6px 0"><button data-r="5m">~1 day</button><button data-r="1h" class="on">~2 weeks</button><button data-r="6h">~3 months</button></div>
      <div class="legend"><span><i style="background:var(--series-1)"></i>Instant-buy price</span><span><i style="background:var(--series-2)"></i>Instant-sell price</span><span class="muted">bars: volume</span></div>
      <div id="item-chart"><div class="empty-state"><span class="spinner"></span></div></div>
      <div class="section-head"><h2>Why this pick</h2></div>
      ${br}
      <div class="row" style="margin-top:10px">
        <button class="btn primary ${isPractice() ? "practice" : ""}" data-place="${id}">${isPractice() ? "Practice buy" : "Record offer"}</button>
        <button class="btn" data-exclude="${id}">Never suggest</button>
        <a class="btn" href="https://prices.runescape.wiki/osrs/item/${id}" target="_blank" rel="noopener">Wiki prices ↗</a>
      </div>`,
    (root) => {
      const marks = r ? [{ price: r.buy, label: "your buy " + U.full(r.buy) }, { price: r.sell, label: "your sell " + U.full(r.sell) }] : [];
      const draw = async (step) => {
        try {
          const ts = await A.timeseries(id, step);
          CH.priceChart($("#item-chart", root), step === "1h" ? ts.slice(-336) : ts, { marks });
        } catch (e) {
          $("#item-chart", root).innerHTML = `<div class="empty-state">Couldn't load history: ${esc(e.message)}</div>`;
        }
      };
      root.querySelectorAll("#range button").forEach((b) => b.addEventListener("click", () => {
        root.querySelectorAll("#range button").forEach((x) => x.classList.toggle("on", x === b));
        draw(b.dataset.r);
      }));
      draw("1h");
    });
  }

  // ---------------------------------------------------------------- events
  function placeFromRec(id) {
    const r = S.result && S.result.recs.find((x) => x.id === id);
    const planned = S.result && S.result.alloc.plan.find((x) => x.id === id);
    const p = planned || r;
    offerDialog(p ? {
      itemId: id, side: "buy", price: p.buy, qty: p.qty,
      predicted: { buy: p.buy, sell: p.sell, margin: p.margin, qty: p.qty, hours: p.hours, expected: p.expected, grade: p.grade, at: Date.now() },
    } : { itemId: id, side: "buy" });
  }

  function sellFromHolding(id) {
    const h = L.positions(ledger().trades).holdings.get(id);
    if (!h) return;
    const listed = L.openOffers(ledger()).filter((o) => o.itemId === id && o.side === "sell").reduce((a, o) => a + o.qty - o.filled, 0);
    const m = S.orders.data && S.orders.data.holds.find((x) => x.itemId === id);
    offerDialog({ itemId: id, side: "sell", qty: h.qty - listed, price: m && m.act.price ? m.act.price : U.breakevenSellPrice(h.avgCost, id) });
  }

  function download(name, text, type) {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type }));
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }

  function onSettingChange(el) {
    const k = el.dataset.set, s = settings();
    if (el.type === "checkbox") s[k] = el.checked;
    else {
      const scale = +el.dataset.scale || 1;
      const v = U.parseGp(el.value);
      if (!isFinite(v) || v < 0) { toast("Not a valid number"); return renderSettings(); }
      s[k] = scale === 1 ? v : v / scale;
      if (k === "slots") s.slots = Math.max(1, Math.min(8, Math.round(s.slots)));
      if (k === "cashReservePct" || k === "maxPerItemPct") s[k] = U.clamp(s[k], 0, 1);
    }
    // Settings that change the plan: rebuild Orders next time it's opened.
    if (["minOrderProfit", "slots", "bankroll", "cashReservePct", "members"].includes(k) && S.orders.data) S.orders.data.at = 0;
    save();
    renderCapital();
    restartTimer();
    refresh({ quiet: true });
  }

  function bind() {
    document.addEventListener("click", (e) => {
      const t = e.target.closest("button, [data-item], [data-new-offer], label.btn");
      if (!t) return;
      const d = t.dataset;
      if (d.view) {
        S.view = d.view; renderAll(); window.scrollTo(0, 0);
        if (d.view === "bot") fetchBot();
        if (d.view === "orders" && (!S.orders.data || Date.now() - S.orders.data.at > 15 * 6e4)) loadOrders();
        return;
      }
      if (d.mode) { if (S.state.mode !== d.mode) { S.state.mode = d.mode; save(); renderAll(); refresh({ quiet: true }); } return; }
      if (d.strategy) { settings().strategy = d.strategy; save(); renderPicks(); refresh(); return; }
      if (d.sort) { S.sort = d.sort; renderPicks(); return; }
      if (d.orderBuy) return orderBuy(+d.orderBuy);
      if (d.orderSell) return orderSell(+d.orderSell, +d.price);
      if (d.place) { document.querySelectorAll(".overlay").forEach((o) => o.remove()); placeFromRec(+d.place); return; }
      if (d.fill) return fillDialog(d.fill);
      if (d.editOffer) return offerDialog({ offerId: d.editOffer });
      if (d.cancel) { L.cancelOffer(ledger(), d.cancel); save(); renderAll(); if (S.view === "orders") renderOrders(); return; }
      if (d.newOffer) return offerDialog({});
      if (d.sell) return sellFromHolding(+d.sell);
      if (d.logTrade) return tradeDialog({ itemId: d.itemId ? +d.itemId : null, side: d.side, qty: d.qty, price: d.price });
      if (d.delTrade) {
        if (confirm("Delete this trade?")) { L.removeTrade(ledger(), d.delTrade); save(); renderAll(); }
        return;
      }
      if (d.exclude) {
        const s = settings();
        s.excludedIds = [...new Set([...(s.excludedIds || []), +d.exclude])];
        save(); document.querySelectorAll(".overlay").forEach((o) => o.remove());
        toast(`${esc(itemName(+d.exclude))} won't be suggested`); refresh({ quiet: true }); return;
      }
      if (d.unexclude) { settings().excludedIds = settings().excludedIds.filter((x) => x !== +d.unexclude); save(); renderSettings(); refresh({ quiet: true }); return; }
      if (d.item && !t.matches("button.btn.primary")) return itemDialog(+d.item);
      switch (t.id) {
        case "tf-create": case "tf-open": case "tf-reconnect": case "tf-stop": return tradeFileAction(t.id);
        case "bot-refresh": return fetchBot();
        case "orders-refresh": return loadOrders();
        case "refresh": case "retry": return refresh({ force: true });
        case "sync": return refresh({ quiet: true });
        case "export-csv": return download(`ge-flipper-${S.state.mode}-trades.csv`, L.toCSV(ledger(), S.items), "text/csv");
        case "export-json": return download(`ge-flipper-backup-${new Date().toISOString().slice(0, 10)}.json`, ST.exportJSON(S.state), "application/json");
        case "reset-practice":
          if (confirm("Delete all practice trades and offers?")) { S.state.ledgers.practice = L.emptyLedger(); save(); renderAll(); refresh({ quiet: true }); }
          return;
      }
    });
    document.addEventListener("change", async (e) => {
      const el = e.target;
      if (el.dataset && el.dataset.set) return onSettingChange(el);
      if (el.id === "import-csv" && el.files[0]) {
        try {
          const trades = L.fromCSV(await el.files[0].text());
          ledger().trades.push(...trades); save(); renderAll(); toast(`Imported ${trades.length} trades`);
        } catch (err) { toast("Import failed: " + esc(err.message)); }
      }
      if (el.classList && el.classList.contains("import-fu") && el.files[0]) {
        try {
          const list = L.fromRuneLite(JSON.parse(await el.files[0].text()));
          const r = L.importOffers(ledger(), list);
          save(); renderAll(); if (S.view === "orders" && r.added) loadOrders(); // advice for what you now hold
          toast(r.added ? `Imported ${r.added} finished GE offer${r.added === 1 ? "" : "s"} from RuneLite${r.matched ? ` (${r.matched} matched your logged offers)` : ""}`
            : `Nothing new to import${r.skipped ? ` (${r.skipped} already imported)` : ""}`);
        } catch (err) { toast("Import failed: " + esc(err.message)); }
        el.value = "";
      }
      if (el.id === "import-json" && el.files[0]) {
        try {
          if (!confirm("Replace ALL data in this browser with the backup?")) return;
          S.state = ST.importJSON(await el.files[0].text()); save(); renderAll(); refresh({ quiet: true }); toast("Backup restored");
        } catch (err) { toast("Import failed: " + esc(err.message)); }
      }
    });
    document.addEventListener("visibilitychange", () => { if (document.hidden) TF.flush(); else refresh({ quiet: true }); });
    window.addEventListener("pagehide", () => TF.flush());
    window.addEventListener("resize", () => { if (S.view === "profit") renderProfit(); });
  }

  let timer = null;
  function restartTimer() {
    clearInterval(timer);
    timer = setInterval(() => {
      if (document.hidden) return;
      if (S.view === "bot") fetchBot(); else refresh({ quiet: true });
    }, Math.max(30, settings().refreshSeconds) * 1000);
  }

  function init() {
    bind();
    renderAll();
    TF.init(() => { renderFileBanner(); if (S.view === "settings") renderSettings(); })
      .then((st) => (st === "ok" ? syncTradeFile() : null));
    S.state.mode = "real";
    fetchBot();
    refresh();
    restartTimer();
  }

  OSF.app = { S, refresh, renderAll };
  if (typeof document !== "undefined" && document.readyState !== "loading") init();
  else document.addEventListener("DOMContentLoaded", init);
})(window.OSF = window.OSF || {});
