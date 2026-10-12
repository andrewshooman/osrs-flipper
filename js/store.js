/* ============================================================
   OSRS FLIPPER — persistence (localStorage) + JSON backup
   Data lives only in this browser. Use Settings → Export to back it up
   or move it between devices.
   ============================================================ */
(function (OSF) {
  "use strict";
  const KEY = "osf:state:v1";

  function fresh() {
    return {
      version: 1,
      mode: "practice", // practice first: prove the model before spending real gp
      settings: { ...OSF.config.DEFAULT_SETTINGS },
      ledgers: { practice: OSF.ledger.emptyLedger(), real: OSF.ledger.emptyLedger() },
    };
  }

  function normalise(s) {
    const base = fresh();
    if (!s || typeof s !== "object") return base;
    return {
      ...base,
      ...s,
      settings: { ...base.settings, ...(s.settings || {}) },
      ledgers: {
        practice: { ...OSF.ledger.emptyLedger(), ...((s.ledgers || {}).practice || {}) },
        real: { ...OSF.ledger.emptyLedger(), ...((s.ledgers || {}).real || {}) },
      },
    };
  }

  function load() {
    try { return normalise(JSON.parse(localStorage.getItem(KEY))); } catch (e) { return fresh(); }
  }
  function save(state) {
    try { localStorage.setItem(KEY, JSON.stringify(state)); return true; } catch (e) { return false; }
  }
  const exportJSON = (state) => JSON.stringify({ app: "osrs-flipper", exportedAt: new Date().toISOString(), ...state }, null, 1);
  function importJSON(text) {
    const parsed = JSON.parse(text);
    if (!parsed || !parsed.ledgers) throw new Error("Not an OSRS Flipper backup");
    return normalise(parsed);
  }

  OSF.store = { load, save, fresh, normalise, exportJSON, importJSON, KEY };
})(window.OSF = window.OSF || {});
