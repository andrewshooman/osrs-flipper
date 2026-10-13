/* ============================================================
   OSRS FLIPPER — ledger: trades, GE offers, FIFO profit, buy limits, capital

   There are two independent ledgers with identical shape — "practice" (mock
   trades, simulated fills) and "real". Nothing here touches storage or the
   network; every function is pure over plain objects so it's easy to test.

   ledger = {
     trades: [{ id, itemId, side: "buy"|"sell", qty, price, ts, offerId?, note?, noTax? }],
     offers: [{ id, itemId, side, qty, price, placedAt, filled, status: "open"|"done"|"cancelled",
                strategy?, predicted?: { buy, sell, margin, hours }, simCursor? }]
   }
   ============================================================ */
(function (OSF) {
  "use strict";
  const C = OSF.config, U = OSF.util;

  const emptyLedger = () => ({ trades: [], offers: [], imported: [] });

  const byTime = (a, b) => a.ts - b.ts || (a.side === "buy" ? -1 : 1) - (b.side === "buy" ? -1 : 1);

  /**
   * Replays trades oldest-first, matching each sell against the oldest unsold
   * buys of the same item (FIFO). Returns open lots and closed round trips.
   */
  function positions(trades) {
    const lots = new Map();      // itemId -> [{ qty, price, ts }]
    const closed = [];           // one entry per sell trade (the matched part)
    const unmatched = [];        // sells with no recorded buy behind them
    for (const t of [...trades].sort(byTime)) {
      if (t.side === "buy") {
        if (!lots.has(t.itemId)) lots.set(t.itemId, []);
        lots.get(t.itemId).push({ qty: t.qty, price: t.price, ts: t.ts });
        continue;
      }
      const q = lots.get(t.itemId) || [];
      let left = t.qty, cost = 0, matched = 0, firstBuyTs = null;
      while (left > 0 && q.length) {
        const lot = q[0], take = Math.min(left, lot.qty);
        cost += take * lot.price;
        matched += take;
        if (firstBuyTs == null) firstBuyTs = lot.ts;
        lot.qty -= take;
        left -= take;
        if (lot.qty === 0) q.shift();
      }
      if (matched > 0) {
        const tax = t.noTax ? 0 : U.taxPerItem(t.price, t.itemId) * matched; // alching pays no GE tax
        const proceeds = t.price * matched - tax;
        closed.push({
          tradeId: t.id, itemId: t.itemId, qty: matched,
          buyPrice: cost / matched, sellPrice: t.price,
          cost, proceeds, tax, profit: proceeds - cost, roi: (proceeds - cost) / cost,
          buyTs: firstBuyTs, sellTs: t.ts, holdHours: (t.ts - firstBuyTs) / 36e5,
        });
      }
      if (left > 0) unmatched.push({ tradeId: t.id, itemId: t.itemId, qty: left, price: t.price, ts: t.ts });
    }
    const holdings = new Map();
    for (const [itemId, q] of lots) {
      const qty = U.sum(q.map((l) => l.qty));
      if (qty <= 0) continue;
      const cost = U.sum(q.map((l) => l.qty * l.price));
      holdings.set(itemId, { itemId, qty, cost, avgCost: cost / qty, oldestTs: q[0].ts, lots: q });
    }
    return { holdings, closed, unmatched };
  }

  /**
   * GE buy limits: a 4-hour window opens on the first purchase of an item;
   * everything bought before it expires counts toward the limit.
   */
  function buyLimitUsage(trades, itemId, now = Date.now()) {
    let start = null, used = 0;
    const buys = trades.filter((t) => t.itemId === itemId && t.side === "buy").sort(byTime);
    for (const b of buys) {
      if (start == null || b.ts >= start + C.BUY_LIMIT_WINDOW_MS) { start = b.ts; used = 0; }
      used += b.qty;
    }
    if (start == null || now >= start + C.BUY_LIMIT_WINDOW_MS) return { used: 0, windowStart: null, resetsAt: null };
    return { used, windowStart: start, resetsAt: start + C.BUY_LIMIT_WINDOW_MS };
  }

  /** Quantity still buyable this window, counting what open buy offers will take. */
  function remainingLimit(ledger, itemId, limit, now = Date.now()) {
    if (!limit) return Infinity; // unknown limit — let the other caps decide
    const used = buyLimitUsage(ledger.trades, itemId, now).used;
    const pending = U.sum(openOffers(ledger).filter((o) => o.itemId === itemId && o.side === "buy").map((o) => o.qty - o.filled));
    return Math.max(0, limit - used - pending);
  }

  const openOffers = (ledger) => ledger.offers.filter((o) => o.status === "open");

  /**
   * Money picture. Equity is measured at cost (realised profit only), so the
   * reserve doesn't swing with every price tick.
   */
  function capital(ledger, settings, bankroll) {
    const { holdings, closed } = positions(ledger.trades);
    const realised = U.sum(closed.map((c) => c.profit));
    const holdingCost = U.sum([...holdings.values()].map((h) => h.cost));
    const inBuyOffers = U.sum(openOffers(ledger).filter((o) => o.side === "buy").map((o) => (o.qty - o.filled) * o.price));
    const equity = bankroll + realised;
    const cash = equity - holdingCost - inBuyOffers;
    const reserve = equity * settings.cashReservePct;
    const deployable = Math.max(0, Math.min(cash, equity - reserve - holdingCost - inBuyOffers));
    const slotsUsed = openOffers(ledger).length;
    return { equity, realised, holdingCost, inBuyOffers, cash, reserve, deployable, slotsUsed, slotsFree: Math.max(0, settings.slots - slotsUsed) };
  }

  // ---- mutations (return the same ledger object for convenience) -------
  function addTrade(ledger, t) {
    const trade = { id: U.uid(), ts: Date.now(), ...t };
    if (!(trade.qty > 0) || !(trade.price > 0)) throw new Error("Trade needs a positive quantity and price");
    ledger.trades.push(trade);
    return trade;
  }
  function removeTrade(ledger, id) {
    ledger.trades = ledger.trades.filter((t) => t.id !== id);
  }
  function placeOffer(ledger, o) {
    const offer = { id: U.uid(), placedAt: Date.now(), filled: 0, status: "open", ...o };
    if (!(offer.qty > 0) || !(offer.price > 0)) throw new Error("Offer needs a positive quantity and price");
    ledger.offers.push(offer);
    return offer;
  }
  /** Records `qty` filled on an offer at its price (GE fills at your price or better; we log yours). */
  function fillOffer(ledger, offerId, qty, ts = Date.now(), price) {
    const o = ledger.offers.find((x) => x.id === offerId);
    if (!o || o.status !== "open") return null;
    const q = Math.min(Math.floor(qty), o.qty - o.filled);
    if (q <= 0) return null;
    o.filled += q;
    if (o.filled >= o.qty) { o.status = "done"; o.closedAt = ts; }
    return addTrade(ledger, { itemId: o.itemId, side: o.side, qty: q, price: price || o.price, ts, offerId: o.id });
  }
  function cancelOffer(ledger, offerId) {
    const o = ledger.offers.find((x) => x.id === offerId);
    if (o && o.status === "open") { o.status = o.filled > 0 ? "done" : "cancelled"; o.closedAt = Date.now(); }
    return o;
  }

  // ---- reporting --------------------------------------------------------
  const dayKey = (ts) => {
    const d = new Date(ts);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  };

  function stats(ledger) {
    const { closed, holdings, unmatched } = positions(ledger.trades);
    const byItem = new Map(), byDay = new Map();
    for (const c of closed) {
      const it = byItem.get(c.itemId) || { itemId: c.itemId, profit: 0, qty: 0, cost: 0, tax: 0, flips: 0, wins: 0 };
      it.profit += c.profit; it.qty += c.qty; it.cost += c.cost; it.tax += c.tax; it.flips++; if (c.profit > 0) it.wins++;
      byItem.set(c.itemId, it);
      const k = dayKey(c.sellTs);
      byDay.set(k, (byDay.get(k) || 0) + c.profit);
    }
    const profit = U.sum(closed.map((c) => c.profit));
    const cost = U.sum(closed.map((c) => c.cost));
    return {
      closed, holdings, unmatched,
      profit, tax: U.sum(closed.map((c) => c.tax)),
      roi: cost ? profit / cost : NaN,
      flips: closed.length,
      winRate: closed.length ? closed.filter((c) => c.profit > 0).length / closed.length : NaN,
      avgHoldHours: closed.length ? U.mean(closed.map((c) => c.holdHours)) : NaN,
      today: byDay.get(dayKey(Date.now())) || 0,
      byItem: [...byItem.values()].sort((a, b) => b.profit - a.profit),
      byDay: [...byDay.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1)),
    };
  }

  /** CSV of all trades (for spreadsheets / backup). */
  function toCSV(ledger, items) {
    const rows = [["time", "item_id", "item", "side", "qty", "price", "total", "offer_id"]];
    for (const t of [...ledger.trades].sort(byTime)) {
      const name = (items && items.get(t.itemId) && items.get(t.itemId).name) || "";
      rows.push([new Date(t.ts).toISOString(), t.itemId, name, t.side, t.qty, t.price, t.qty * t.price, t.offerId || ""]);
    }
    return rows.map((r) => r.map((v) => (/[",\n]/.test(String(v)) ? `"${String(v).replace(/"/g, '""')}"` : v)).join(",")).join("\n");
  }

  /** Accepts the CSV produced above (item_id, side, qty, price, time columns are required). */
  function fromCSV(text) {
    const lines = text.trim().split(/\r?\n/);
    const head = lines.shift().split(",").map((h) => h.trim().toLowerCase());
    const col = (n) => head.indexOf(n);
    const need = ["time", "item_id", "side", "qty", "price"];
    if (need.some((n) => col(n) < 0)) throw new Error("CSV needs columns: " + need.join(", "));
    return lines.filter(Boolean).map((line) => {
      const cells = line.match(/("([^"]|"")*"|[^,]*)(,|$)/g).map((c) => c.replace(/,$/, "").replace(/^"|"$/g, "").replace(/""/g, '"'));
      return {
        id: U.uid(), ts: Date.parse(cells[col("time")]), itemId: +cells[col("item_id")],
        side: cells[col("side")].trim().toLowerCase(), qty: +cells[col("qty")], price: +cells[col("price")],
      };
    }).filter((t) => t.ts && t.itemId && (t.side === "buy" || t.side === "sell") && t.qty > 0 && t.price > 0);
  }

  // ---- RuneLite: the Flipping Utilities plugin's save file ------------------
  // ~/.runelite/flipping/<name>.json holds, per item, the latest state of each GE
  // offer: b = buy, cQIT = quantity filled, p = average price before tax, t = time,
  // s = slot, st = offer state. Only finished offers are used, so offers still
  // running are picked up once they finish and nothing is counted twice.
  const FU_FINISHED = new Set(["BOUGHT", "SOLD", "CANCELLED_BUY", "CANCELLED_SELL"]);
  function fuTime(t) {
    if (t == null) return NaN;
    if (typeof t === "object") return (t.seconds ?? t.epochSecond ?? NaN) * 1000 + Math.floor((t.nanos || 0) / 1e6);
    if (typeof t === "number") return t > 1e12 ? t : t * 1000;
    return Date.parse(t);
  }
  /** Finished GE offers from a Flipping Utilities save file, oldest first. */
  function fromFlippingUtilities(data) {
    if (!data || !Array.isArray(data.trades)) throw new Error("Not a Flipping Utilities file (.runelite/flipping/<name>.json)");
    const out = [];
    for (const it of data.trades) {
      for (const e of ((it && it.h) || {}).sO || []) {
        const qty = Number(e.cQIT), price = Number(e.p), ts = fuTime(e.t), itemId = Number(e.id ?? it.id);
        if (!FU_FINISHED.has(e.st) || !(qty > 0) || !(price > 0) || !isFinite(ts) || !(itemId > 0)) continue;
        const side = e.b ? "buy" : "sell";
        out.push({ itemId, side, qty, price, ts, key: ["fu", itemId, side, e.s, ts, qty, price].join(":") });
      }
    }
    return out.sort((a, b) => a.ts - b.ts);
  }
  /**
   * Adds imported offers to the ledger. Each fills the matching open offer you logged
   * (same item and side, placed before it finished) and closes it, since the in-game
   * offer is done; anything left over becomes a plain trade. Already-imported offers
   * are skipped. Returns { added, matched, skipped }.
   */
  function importOffers(ledger, list) {
    ledger.imported = ledger.imported || [];
    const seen = new Set(ledger.imported);
    let added = 0, matched = 0, skipped = 0;
    for (const x of list) {
      if (seen.has(x.key)) { skipped++; continue; }
      seen.add(x.key); ledger.imported.push(x.key);
      const o = ledger.offers.filter((y) => y.status === "open" && y.itemId === x.itemId && y.side === x.side && y.placedAt <= x.ts + 60e3)
        .sort((a, b) => a.placedAt - b.placedAt)[0];
      let left = x.qty;
      if (o) {
        const t = fillOffer(ledger, o.id, left, x.ts, x.price);
        if (t) left -= t.qty;
        if (o.status === "open") { o.status = o.filled > 0 ? "done" : "cancelled"; o.closedAt = x.ts; }
        matched++;
      }
      if (left > 0) addTrade(ledger, { itemId: x.itemId, side: x.side, qty: left, price: x.price, ts: x.ts, note: "runelite" });
      added++;
    }
    return { added, matched, skipped };
  }

  OSF.ledger = {
    emptyLedger, positions, buyLimitUsage, remainingLimit, openOffers, capital,
    addTrade, removeTrade, placeOffer, fillOffer, cancelOffer, stats, toCSV, fromCSV, dayKey,
    fromFlippingUtilities, importOffers,
  };
})(window.OSF = window.OSF || {});
