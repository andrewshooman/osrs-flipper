/* ============================================================
   OSRS FLIPPER — constants & default settings
   Every module attaches to window.OSF so the app runs from a plain
   <script> tag (works when index.html is opened directly from disk)
   and loads under Node for the test harness.
   ============================================================ */
(function (OSF) {
  "use strict";

  OSF.config = {
    API_BASE: "https://prices.runescape.wiki/api/v1/osrs",
    // Paper-trading bot state. Served locally by research/run_bot.py when the app
    // runs on your own computer; otherwise the copy published to the `live` branch.
    BOT_STATE_URL: typeof location !== "undefined" && /^(localhost|127\.0\.0\.1)$/.test(location.hostname)
      ? "bot-data/live/state.json"
      : "https://raw.githubusercontent.com/andrewshooman/osrs-flipper/live/state.json",

    // Grand Exchange tax (since 29 May 2025): 2% of the sell price, per item,
    // rounded down, capped at 5M per item. A handful of items are exempt.
    TAX_RATE: 0.02,
    TAX_CAP_PER_ITEM: 5_000_000,
    // Exempt from GE tax (OSRS Wiki "Grand Exchange" → Exempt from tax): bonds,
    // energy potions, low-level ammo/runes/food, teleport tablets, games
    // necklace(8), ring of dueling(8) and basic tools. Keep in sync with research/backtest.py.
    TAX_EXEMPT_IDS: new Set([233, 315, 329, 347, 351, 355, 361, 365, 379, 558, 806, 807, 808, 882, 884, 886, 952, 1733, 1735, 1755, 1785, 1891, 2140, 2142, 2309, 2327, 2347, 2552, 3008, 3010, 3012, 3014, 3853, 5325, 5329, 5331, 5341, 5343, 8007, 8008, 8009, 8010, 8011, 8013, 8794, 13190, 28790, 28824]),

    BUY_LIMIT_WINDOW_MS: 4 * 60 * 60 * 1000,
    NATURE_RUNE_ID: 561,

    // How long fetched data stays fresh (ms).
    CACHE_TTL: {
      mapping: 24 * 60 * 60 * 1000,
      latest: 60 * 1000,
      "5m": 2 * 60 * 1000,
      "1h": 10 * 60 * 1000,
      "24h": 30 * 60 * 1000,
      ts5m: 10 * 60 * 1000,
      ts1h: 30 * 60 * 1000,
    },
    MAX_CONCURRENT_REQUESTS: 6,
    // How many prefiltered candidates get the full (history-backed) analysis.
    DEEP_CANDIDATES: 40,
  };

  // Strategy presets tune the model's horizon and risk appetite.
  OSF.config.STRATEGIES = {
    active: {
      label: "Active flips",
      blurb: "You're online: quick turnover, offers checked every few minutes.",
      horizonHours: 2,        // target time to buy AND sell
      captureShare: 0.12,     // share of the market's volume you can expect to get
      minDailyVolume: 2_000,
      maxStaleMinutes: 20,    // both sides must have traded this recently
      riskAversion: 1.0,
    },
    overnight: {
      label: "Overnight / AFK",
      blurb: "Set offers before logging off; slower items, bigger quantities.",
      horizonHours: 10,
      captureShare: 0.08,
      minDailyVolume: 300,
      maxStaleMinutes: 90,
      riskAversion: 1.5,      // longer holds are riskier, so weigh risk more
    },
  };

  OSF.config.DEFAULT_SETTINGS = {
    members: true,
    bankroll: 11_000_000,       // starting gp (real ledger)
    practiceBankroll: 11_000_000,
    cashReservePct: 0.5,        // keep half the stack untouched
    maxPerItemPct: 0.25,        // of the deployable capital
    slots: 7,                   // GE slots the tool plans for; the 8th stays free for your own trades
    minOrderProfit: 10_000,     // only suggest buys expected to make at least this (gp, after tax); walk-forward r12
    slotsDefault7: true,        // marks settings that already got the 7-slot default (see store.js)
    stopLossPct: 0.03,
    maxHoldHours: 12,
    strategy: "active",
    minMarginGp: 1,
    minRoiPct: 0.4,             // after tax
    excludedIds: [],            // items you never want suggested
    refreshSeconds: 60,
  };
})(window.OSF = window.OSF || {});
