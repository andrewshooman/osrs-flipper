/* ============================================================
   GE FLIPPER — tiny canvas charts (no dependencies)
   - priceChart: instant-buy vs instant-sell average price, crosshair tooltip,
     with a separate volume strip underneath (own scale, never a 2nd y-axis).
   - barChart: daily realised profit, per-bar tooltip.
   Colours come from CSS tokens so both themes work.
   ============================================================ */
(function (OSF) {
  "use strict";
  const U = OSF.util;

  const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

  function setup(container, height) {
    container.innerHTML = "";
    container.classList.add("chart");
    const canvas = document.createElement("canvas");
    const tip = document.createElement("div");
    tip.className = "tip hidden";
    container.append(canvas, tip);
    const dpr = window.devicePixelRatio || 1;
    const w = container.clientWidth || 600;
    canvas.width = w * dpr; canvas.height = height * dpr;
    canvas.style.height = height + "px";
    const ctx = canvas.getContext("2d");
    ctx.scale(dpr, dpr);
    return { canvas, tip, ctx, w, h: height };
  }

  function niceTicks(min, max, n = 4) {
    if (!(max > min)) return [min];
    const step0 = (max - min) / n, mag = 10 ** Math.floor(Math.log10(step0));
    const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= step0) || step0;
    const out = [];
    for (let v = Math.ceil(min / step) * step; v <= max + 1e-9; v += step) out.push(v);
    return out;
  }

  function showTip(tip, html, x, y, w) {
    tip.innerHTML = html;
    tip.classList.remove("hidden");
    const tw = tip.offsetWidth;
    tip.style.left = Math.min(Math.max(0, x + 12), w - tw) + "px";
    tip.style.top = Math.max(0, y - 10) + "px";
  }

  const fmtTime = (sec, span) => {
    const d = new Date(sec * 1000);
    return span > 2 * 86400
      ? d.toLocaleDateString(undefined, { month: "short", day: "numeric" })
      : d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  };

  /**
   * points: [{timestamp, avgHighPrice, avgLowPrice, highPriceVolume, lowPriceVolume}]
   * opts.marks: [{price, label}] horizontal reference lines (your buy/sell).
   */
  function priceChart(container, points, opts = {}) {
    const pts = points.filter((p) => p.avgHighPrice || p.avgLowPrice);
    const H = 220, VH = 56, padL = 56, padR = 12, padT = 10, gap = 18;
    const { canvas, tip, ctx, w } = setup(container, H + gap + VH + 18);
    if (pts.length < 2) {
      ctx.fillStyle = css("--text-muted"); ctx.font = "13px system-ui";
      ctx.fillText("Not enough price history", padL, 40);
      return;
    }
    const c1 = css("--series-1"), c2 = css("--series-2"), grid = css("--grid"), muted = css("--text-muted"), surface = css("--surface-1");
    const t0 = pts[0].timestamp, t1 = pts[pts.length - 1].timestamp, span = t1 - t0;
    const vals = pts.flatMap((p) => [p.avgHighPrice, p.avgLowPrice]).filter(Boolean)
      .concat((opts.marks || []).map((m) => m.price));
    let lo = Math.min(...vals), hi = Math.max(...vals);
    const pad = (hi - lo) * 0.08 || hi * 0.01 || 1; lo -= pad; hi += pad;
    const X = (t) => padL + ((t - t0) / (span || 1)) * (w - padL - padR);
    const Y = (v) => padT + (1 - (v - lo) / (hi - lo)) * (H - padT - 16);

    function draw(hoverIdx) {
      ctx.clearRect(0, 0, w, canvas.height);
      ctx.font = "11px system-ui"; ctx.textBaseline = "middle";
      // grid + y labels
      for (const v of niceTicks(lo, hi)) {
        ctx.strokeStyle = grid; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(padL, Y(v)); ctx.lineTo(w - padR, Y(v)); ctx.stroke();
        ctx.fillStyle = muted; ctx.textAlign = "right"; ctx.fillText(U.gp(v), padL - 6, Y(v));
      }
      // x labels
      ctx.textAlign = "center";
      for (let i = 0; i <= 4; i++) {
        const t = t0 + (span * i) / 4;
        ctx.fillStyle = muted; ctx.fillText(fmtTime(t, span), Math.min(Math.max(X(t), padL + 20), w - padR - 20), H - 4);
      }
      // reference marks
      for (const m of opts.marks || []) {
        ctx.setLineDash([4, 4]); ctx.strokeStyle = muted; ctx.beginPath(); ctx.moveTo(padL, Y(m.price)); ctx.lineTo(w - padR, Y(m.price)); ctx.stroke(); ctx.setLineDash([]);
        ctx.fillStyle = muted; ctx.textAlign = "left"; ctx.fillText(m.label, padL + 4, Y(m.price) - 8);
      }
      // series
      const line = (key, color) => {
        ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.lineJoin = "round"; ctx.beginPath();
        let started = false;
        for (const p of pts) {
          if (!p[key]) { started = false; continue; }
          if (!started) { ctx.moveTo(X(p.timestamp), Y(p[key])); started = true; } else ctx.lineTo(X(p.timestamp), Y(p[key]));
        }
        ctx.stroke();
      };
      line("avgHighPrice", c1);
      line("avgLowPrice", c2);
      // volume strip
      const vTop = H + gap, maxV = Math.max(...pts.map((p) => (p.highPriceVolume || 0) + (p.lowPriceVolume || 0))) || 1;
      const bw = Math.max(1, (w - padL - padR) / pts.length - 1);
      ctx.fillStyle = muted; ctx.textAlign = "right"; ctx.fillText("Vol", padL - 6, vTop + 8);
      ctx.globalAlpha = 0.55;
      for (const p of pts) {
        const v = (p.highPriceVolume || 0) + (p.lowPriceVolume || 0), bh = (v / maxV) * VH;
        ctx.fillStyle = muted; ctx.fillRect(X(p.timestamp) - bw / 2, vTop + VH - bh, bw, bh);
      }
      ctx.globalAlpha = 1;
      // crosshair
      if (hoverIdx != null) {
        const p = pts[hoverIdx], x = X(p.timestamp);
        ctx.strokeStyle = muted; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(x, padT); ctx.lineTo(x, vTop + VH); ctx.stroke();
        for (const [k, c] of [["avgHighPrice", c1], ["avgLowPrice", c2]]) {
          if (!p[k]) continue;
          ctx.fillStyle = c; ctx.strokeStyle = surface; ctx.lineWidth = 2;
          ctx.beginPath(); ctx.arc(x, Y(p[k]), 4.5, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
        }
      }
    }
    draw(null);
    const onMove = (ev) => {
      const r = canvas.getBoundingClientRect(), x = (ev.touches ? ev.touches[0].clientX : ev.clientX) - r.left;
      const t = t0 + ((x - padL) / (w - padL - padR)) * span;
      let best = 0;
      for (let i = 1; i < pts.length; i++) if (Math.abs(pts[i].timestamp - t) < Math.abs(pts[best].timestamp - t)) best = i;
      draw(best);
      const p = pts[best];
      showTip(tip,
        `<div class="muted">${new Date(p.timestamp * 1000).toLocaleString()}</div>` +
        `<div><i style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${c1}"></i> Instant-buy ${U.full(p.avgHighPrice)}</div>` +
        `<div><i style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${c2}"></i> Instant-sell ${U.full(p.avgLowPrice)}</div>` +
        `<div class="muted">Volume ${U.full((p.highPriceVolume || 0) + (p.lowPriceVolume || 0))}</div>`,
        x, (ev.touches ? ev.touches[0].clientY : ev.clientY) - r.top, w);
    };
    canvas.addEventListener("mousemove", onMove);
    canvas.addEventListener("touchmove", onMove, { passive: true });
    canvas.addEventListener("mouseleave", () => { draw(null); tip.classList.add("hidden"); });
  }

  /** rows: [[label, value]] — positive bars blue, negative red. */
  function barChart(container, rows) {
    const H = 180, padL = 56, padR = 8, padT = 10, padB = 22;
    const { canvas, tip, ctx, w } = setup(container, H);
    if (!rows.length) {
      ctx.fillStyle = css("--text-muted"); ctx.font = "13px system-ui"; ctx.fillText("No closed flips yet", padL, 40);
      return;
    }
    const pos = css("--series-1"), neg = css("--neg"), grid = css("--grid"), muted = css("--text-muted"), text = css("--text-secondary");
    const vals = rows.map((r) => r[1]);
    let lo = Math.min(0, ...vals), hi = Math.max(0, ...vals);
    if (lo === hi) hi = 1;
    const Y = (v) => padT + (1 - (v - lo) / (hi - lo)) * (H - padT - padB);
    const slot = (w - padL - padR) / rows.length, bw = Math.max(3, Math.min(36, slot - 2));
    const X = (i) => padL + slot * i + (slot - bw) / 2;

    function draw(hover) {
      ctx.clearRect(0, 0, w, H);
      ctx.font = "11px system-ui"; ctx.textBaseline = "middle";
      for (const v of niceTicks(lo, hi)) {
        ctx.strokeStyle = grid; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(padL, Y(v)); ctx.lineTo(w - padR, Y(v)); ctx.stroke();
        ctx.fillStyle = muted; ctx.textAlign = "right"; ctx.fillText(U.gp(v), padL - 6, Y(v));
      }
      rows.forEach(([label, v], i) => {
        const y0 = Y(0), y1 = Y(v), top = Math.min(y0, y1), h = Math.max(1, Math.abs(y1 - y0));
        ctx.fillStyle = v >= 0 ? pos : neg;
        ctx.globalAlpha = hover == null || hover === i ? 1 : 0.55;
        const r = Math.min(4, bw / 2, h);
        ctx.beginPath();
        if (v >= 0) { ctx.moveTo(X(i), y0); ctx.lineTo(X(i), top + r); ctx.arcTo(X(i), top, X(i) + r, top, r); ctx.lineTo(X(i) + bw - r, top); ctx.arcTo(X(i) + bw, top, X(i) + bw, top + r, r); ctx.lineTo(X(i) + bw, y0); }
        else { ctx.moveTo(X(i), y0); ctx.lineTo(X(i), y1 - r); ctx.arcTo(X(i), y1, X(i) + r, y1, r); ctx.lineTo(X(i) + bw - r, y1); ctx.arcTo(X(i) + bw, y1, X(i) + bw, y1 - r, r); ctx.lineTo(X(i) + bw, y0); }
        ctx.fill();
        ctx.globalAlpha = 1;
      });
      ctx.strokeStyle = muted; ctx.beginPath(); ctx.moveTo(padL, Y(0)); ctx.lineTo(w - padR, Y(0)); ctx.stroke();
      ctx.fillStyle = text; ctx.textAlign = "center";
      const every = Math.ceil(rows.length / 7);
      rows.forEach(([label], i) => { if (i % every === 0 || i === rows.length - 1) ctx.fillText(label.slice(5), X(i) + bw / 2, H - 8); });
    }
    draw(null);
    canvas.addEventListener("mousemove", (ev) => {
      const r = canvas.getBoundingClientRect(), x = ev.clientX - r.left;
      const i = Math.floor((x - padL) / slot);
      if (i < 0 || i >= rows.length) { draw(null); tip.classList.add("hidden"); return; }
      draw(i);
      showTip(tip, `<div class="muted">${rows[i][0]}</div><div>${U.gp(rows[i][1], { plus: true })} profit</div>`, x, ev.clientY - r.top, w);
    });
    canvas.addEventListener("mouseleave", () => { draw(null); tip.classList.add("hidden"); });
  }

  OSF.charts = { priceChart, barChart };
})(window.OSF = window.OSF || {});
