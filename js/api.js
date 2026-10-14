/* ============================================================
   GE TYCOON — OSRS Wiki real-time prices client
   Docs: https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices

   Field meanings (wiki terminology):
     high / avgHighPrice  — price of INSTANT BUYS (what a seller's offer fills at)
     low  / avgLowPrice   — price of INSTANT SELLS (what a buyer's offer fills at)
     highPriceVolume      — units instantly bought  → volume your SELL offers can fill against
     lowPriceVolume       — units instantly sold    → volume your BUY offers can fill against

   Browsers don't let pages set a User-Agent, so requests carry the browser's
   own. Keep the request rate modest: everything is cached and the expensive
   per-item history is only fetched for the shortlisted candidates.
   ============================================================ */
(function (OSF) {
  "use strict";
  const C = OSF.config;

  const mem = new Map(); // key -> { at, data }
  let active = 0;
  const queue = [];

  function runLimited(fn) {
    return new Promise((resolve, reject) => {
      const go = () => {
        active++;
        fn().then(resolve, reject).finally(() => {
          active--;
          if (queue.length) queue.shift()();
        });
      };
      if (active < C.MAX_CONCURRENT_REQUESTS) go(); else queue.push(go);
    });
  }

  function readPersisted(key) {
    try {
      const raw = localStorage.getItem("osf:cache:" + key);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }
  function writePersisted(key, entry) {
    try { localStorage.setItem("osf:cache:" + key, JSON.stringify(entry)); } catch (e) { /* quota / private mode */ }
  }

  /**
   * GET a path under the API with caching.
   * opts.ttl — freshness in ms; opts.persist — also cache in localStorage.
   */
  async function get(path, opts = {}) {
    const key = path;
    const ttl = opts.ttl ?? 60_000;
    const now = Date.now();
    let hit = mem.get(key);
    if (!hit && opts.persist) {
      hit = readPersisted(key);
      if (hit) mem.set(key, hit);
    }
    if (hit && now - hit.at < ttl && !opts.force) return hit.data;

    const fetcher = OSF.api.fetchImpl || ((u) => fetch(u, { headers: { Accept: "application/json" } }));
    try {
      const data = await runLimited(async () => {
        const res = await fetcher(C.API_BASE + path);
        if (!res.ok) throw new Error(`HTTP ${res.status} for ${path}`);
        return res.json();
      });
      const entry = { at: Date.now(), data };
      mem.set(key, entry);
      if (opts.persist) writePersisted(key, entry);
      return data;
    } catch (err) {
      if (hit) return hit.data; // serve stale rather than nothing
      throw err;
    }
  }

  const T = C.CACHE_TTL;

  /** Item metadata: Map<id, {id,name,members,limit,value,highalch,lowalch,icon,examine}> */
  async function mapping() {
    const list = await get("/mapping", { ttl: T.mapping, persist: true });
    const m = new Map();
    for (const it of list) m.set(it.id, it);
    return m;
  }
  const latest = async (force) => (await get("/latest", { ttl: T.latest, force })).data;
  const avg5m = async (force) => (await get("/5m", { ttl: T["5m"], force })).data;
  const avg1h = async (force) => (await get("/1h", { ttl: T["1h"], force })).data;
  /** 24h averages. Not every deployment of the API has this route, so fall back to null. */
  async function avg24h() {
    try { return (await get("/24h", { ttl: T["24h"] })).data; } catch (e) { return null; }
  }
  /** Snapshot of the whole market for the 5m / 1h bucket starting at `unixSeconds`. */
  const snapshot = async (step, unixSeconds) =>
    (await get(`/${step}?timestamp=${unixSeconds}`, { ttl: 365 * 864e5 })).data;
  /** Up to 365 points of history for one item. step: 5m | 1h | 6h | 24h */
  async function timeseries(id, step, ttlOverride) {
    const ttl = ttlOverride ?? (step === "5m" ? T.ts5m : T.ts1h);
    return (await get(`/timeseries?timestep=${step}&id=${id}`, { ttl })).data || [];
  }

  /** Everything the screener needs in one call. */
  async function market(force) {
    const [items, lt, m5, h1, d1] = await Promise.all([mapping(), latest(force), avg5m(force), avg1h(force), avg24h()]);
    return { items, latest: lt, m5, h1, d1, fetchedAt: Date.now() };
  }

  OSF.api = { get, mapping, latest, avg5m, avg1h, avg24h, snapshot, timeseries, market, fetchImpl: null, _cache: mem };
})(window.OSF = window.OSF || {});
