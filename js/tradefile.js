/* ============================================================
   GE FLIPPER — trade file on your computer
   Keeps a copy of everything (trades, offers, settings) in a JSON file you
   pick once, on any drive. Every save rewrites it, and the app restores from
   it if this browser's storage is ever cleared. Uses the File System Access
   API (Chrome and Edge on a computer); other browsers keep using backups.
   ============================================================ */
(function (OSF) {
  "use strict";
  const DB = "osf-files", STORE = "handles", KEY = "tradefile";
  const W = typeof window !== "undefined" ? window : {};
  const supported = typeof W.showSaveFilePicker === "function" && typeof indexedDB !== "undefined";

  // ---- IndexedDB: the file handle survives page reloads -------------------
  function idb(mode, fn) {
    return new Promise((resolve, reject) => {
      const open = indexedDB.open(DB, 1);
      open.onupgradeneeded = () => open.result.createObjectStore(STORE);
      open.onerror = () => reject(open.error);
      open.onsuccess = () => {
        const tx = open.result.transaction(STORE, mode);
        const req = fn(tx.objectStore(STORE));
        tx.oncomplete = () => { open.result.close(); resolve(req && req.result); };
        tx.onerror = () => { open.result.close(); reject(tx.error); };
      };
    });
  }
  const idbGet = () => idb("readonly", (s) => s.get(KEY));
  const idbSet = (h) => idb("readwrite", (s) => s.put(h, KEY));
  const idbDel = () => idb("readwrite", (s) => s.delete(KEY));

  // status: unsupported | off | ok | needs-permission | error
  const T = { handle: null, status: supported ? "off" : "unsupported", lastWrite: 0, error: null, onChange: () => {} };
  const set = (status, error = null) => { T.status = status; T.error = error; T.onChange(); };

  const count = (st) => ["real", "practice"].reduce((n, k) => {
    const l = (st && st.ledgers && st.ledgers[k]) || {};
    return n + (l.trades || []).length + (l.offers || []).length;
  }, 0);

  /**
   * What to do when this browser's copy and the file's copy meet.
   * restore: the browser has nothing (storage cleared, new device) → load the file.
   * ask: the file was saved more recently and holds different data → let the user pick.
   * write: otherwise the browser's copy wins and the file is brought up to date.
   */
  function reconcile(local, file) {
    const nl = count(local), nf = count(file);
    if (!file || nf === 0) return { action: "write", local: nl, file: nf };
    if (nl === 0) return { action: "restore", local: nl, file: nf };
    if ((file.savedAt || 0) > (local.savedAt || 0) && JSON.stringify(file.ledgers) !== JSON.stringify(local.ledgers))
      return { action: "ask", local: nl, file: nf };
    return { action: "write", local: nl, file: nf };
  }

  async function init(onChange) {
    if (onChange) T.onChange = onChange;
    if (!supported) return T.status;
    try {
      T.handle = (await idbGet()) || null;
      if (!T.handle) { set("off"); return T.status; }
      const p = await T.handle.queryPermission({ mode: "readwrite" });
      set(p === "granted" ? "ok" : "needs-permission");
    } catch (e) { set("error", e.message); }
    return T.status;
  }

  async function adopt(handle) {
    const p = await handle.requestPermission({ mode: "readwrite" });
    if (p !== "granted") throw new Error("Permission to write the file was not given");
    T.handle = handle;
    await idbSet(handle);
    set("ok");
  }
  /** New file (Save dialog: pick any folder, e.g. D:\GE-Tycoon). */
  async function create() {
    const h = await W.showSaveFilePicker({
      suggestedName: "ge-tycoon-trades.json",
      types: [{ description: "GE Tycoon trades", accept: { "application/json": [".json"] } }],
    });
    await adopt(h);
  }
  /** An existing file, e.g. after reinstalling the browser or on a new PC. */
  async function openExisting() {
    const [h] = await W.showOpenFilePicker({ types: [{ description: "GE Tycoon trades", accept: { "application/json": [".json"] } }] });
    await adopt(h);
  }
  async function reconnect() {
    if (!T.handle) return T.status;
    const p = await T.handle.requestPermission({ mode: "readwrite" });
    set(p === "granted" ? "ok" : "needs-permission");
    return T.status;
  }
  async function disconnect() {
    clearTimeout(timer);
    T.handle = null;
    try { await idbDel(); } catch (e) { /* nothing stored */ }
    set(supported ? "off" : "unsupported");
  }
  /** The file's contents as a state object, or null when it's empty. */
  async function read() {
    const text = await (await T.handle.getFile()).text();
    return text.trim() ? OSF.store.importJSON(text) : null;
  }

  // ---- writes: debounced; a write in flight is followed by the newest state --
  let timer = null, pending = null, writing = false;
  function schedule(state, delay = 400) {
    if (T.status !== "ok" || !T.handle) return;
    pending = state;
    clearTimeout(timer);
    timer = setTimeout(flush, delay);
  }
  async function flush() {
    if (writing || !pending || !T.handle) return;
    const text = OSF.store.exportJSON(pending);
    pending = null;
    writing = true;
    try {
      const w = await T.handle.createWritable();
      await w.write(text);
      await w.close();
      T.lastWrite = Date.now();
      if (T.status !== "ok") set("ok"); else T.onChange();
    } catch (e) {
      set(e && e.name === "NotAllowedError" ? "needs-permission" : "error", e && e.message);
    } finally {
      writing = false;
      if (pending) flush();
    }
  }

  OSF.tradefile = {
    supported, T, init, create, openExisting, reconnect, disconnect, read, schedule, flush, reconcile, count,
    fileName: () => (T.handle && T.handle.name) || "",
  };
})(window.OSF = window.OSF || {});
