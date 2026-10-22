// Hycon heatmap: an interactive top-down map drawn on a canvas.
// Layers: the real world map (Hytale's map images, from /api/worldmap), smooth heat coloured
// blue (little time) -> green -> yellow -> red (most time), the path travelled (each stretch coloured
// by the heat where it runs), markers for blocks broken / placed / used, and pins.
// Zoom with the buttons or the wheel (after a click), drag to pan, hover for details.
// Uses el(), short(), fmtTime() and tip() from app.js.
'use strict';

const HEAT_STOPS = [[0, [40, 70, 200]], [0.3, [40, 170, 230]], [0.5, [60, 200, 90]], [0.7, [240, 220, 60]], [1, [235, 60, 45]]];
const MARK_STYLE = { broken: ['#e2b54b', 'B'], placed: ['#4fa3f7', 'P'], used: ['#b07cf0', 'U'] };
// Zoom and layer choices survive a refresh of the same map.
const HEAT_VIEWS = new Map();

function heatColour(t) {
  t = Math.max(0, Math.min(1, t));
  let i = 1;
  while (i < HEAT_STOPS.length - 1 && t > HEAT_STOPS[i][0]) i++;
  const [ta, ca] = HEAT_STOPS[i - 1], [tb, cb] = HEAT_STOPS[i], f = (t - ta) / (tb - ta || 1);
  return ca.map((c, k) => Math.round(c + (cb[k] - c) * f));
}

function heatmapCard(h) {
  if (!h.cells.length && !(h.path || []).length) {
    return el('div', { class: 'card' }, el('h3', {}, h.title), el('p', { class: 'muted' },
      'Nothing recorded yet. Positions are noted every 15 seconds while players are online; join and move around for a minute.'));
  }
  const C = h.cell;
  // Everything in block coordinates.
  let bx0 = Infinity, bx1 = -Infinity, bz0 = Infinity, bz1 = -Infinity, max = 0;
  const grow = (x, z) => { bx0 = Math.min(bx0, x); bx1 = Math.max(bx1, x); bz0 = Math.min(bz0, z); bz1 = Math.max(bz1, z); };
  for (const [cx, cz, v] of h.cells) { grow(cx * C, cz * C); grow(cx * C + C, cz * C + C); max = Math.max(max, v); }
  for (const seg of h.path || []) for (let i = 0; i < seg.length; i += 2) grow(seg[i], seg[i + 1]);
  for (const [, x, z] of h.pins) grow(x, z);
  bx0 -= 24; bz0 -= 24; bx1 += 24; bz1 += 24;
  const heatAt = new Map(h.cells.map(([cx, cz, v]) => [cx + ',' + cz, v]));
  const valueAt = (x, z) => heatAt.get(Math.floor(x / C) + ',' + Math.floor(z / C)) || 0;
  const norm = (v) => (max ? Math.log(1 + v) / Math.log(1 + max) : 0);

  const key = h.title;
  const saved = HEAT_VIEWS.get(key);
  const layers = saved ? saved.layers : { map: !!h.world, heat: true, path: true, marks: true };
  const canvas = el('canvas', { class: 'heatmap' });
  const ctx = canvas.getContext('2d');
  // view: block coordinate at the canvas centre, and pixels per block.
  let view = saved ? saved.view : null;

  // ---------- world map image ----------
  let mapImg = null, mapBox = null;
  if (h.world) {
    const ch = (b) => Math.floor(b / 32);
    let cx0 = ch(bx0), cx1 = ch(bx1), cz0 = ch(bz0), cz1 = ch(bz1);
    const clip = (a, b) => { if (b - a < 48) return [a, b]; const m = Math.floor((a + b) / 2); return [m - 23, m + 24]; };
    [cx0, cx1] = clip(cx0, cx1); [cz0, cz1] = clip(cz0, cz1);
    mapBox = [cx0 * 32, cz0 * 32, (cx1 + 1) * 32, (cz1 + 1) * 32];
    mapImg = new Image();
    mapImg.onload = () => draw();
    mapImg.src = '/api/worldmap?world=' + encodeURIComponent(h.world) + '&cx0=' + cx0 + '&cz0=' + cz0 + '&cx1=' + cx1 + '&cz1=' + cz1;
  }

  // ---------- smooth heat, built once at one pixel per cell, then blurred and coloured ----------
  const gw = Math.ceil((bx1 - bx0) / C) + 1, gh = Math.ceil((bz1 - bz0) / C) + 1;
  const heatLayer = document.createElement('canvas');
  const SCALE = 4;   // heat texture pixels per cell: room for a soft blur
  heatLayer.width = gw * SCALE; heatLayer.height = gh * SCALE;
  {
    const g = heatLayer.getContext('2d');
    const gray = document.createElement('canvas');
    gray.width = heatLayer.width; gray.height = heatLayer.height;
    const gg = gray.getContext('2d');
    for (const [cx, cz, v] of h.cells) {
      gg.fillStyle = 'rgba(0,0,0,' + (0.15 + 0.85 * norm(v)).toFixed(3) + ')';
      gg.fillRect((cx - Math.floor(bx0 / C)) * SCALE, (cz - Math.floor(bz0 / C)) * SCALE, SCALE, SCALE);
    }
    g.filter = 'blur(' + SCALE * 0.9 + 'px)';
    g.drawImage(gray, 0, 0);
    g.filter = 'none';
    const img = g.getImageData(0, 0, heatLayer.width, heatLayer.height);
    const d = img.data;
    for (let i = 0; i < d.length; i += 4) {
      const a = d[i + 3] / 255;
      if (a < 0.03) { d[i + 3] = 0; continue; }
      const [r, gc, b] = heatColour(a);
      d[i] = r; d[i + 1] = gc; d[i + 2] = b; d[i + 3] = Math.round(Math.min(1, a * 1.6) * 200);
    }
    g.putImageData(img, 0, 0);
  }

  // ---------- drawing ----------
  const toScreen = (x, z) => [canvas.width / 2 + (x - view.x) * view.k, canvas.height / 2 + (z - view.z) * view.k];
  const toWorld = (sx, sy) => [view.x + (sx - canvas.width / 2) / view.k, view.z + (sy - canvas.height / 2) / view.k];

  function fit() {
    const k = Math.min(canvas.width / (bx1 - bx0), canvas.height / (bz1 - bz0));
    view = { x: (bx0 + bx1) / 2, z: (bz0 + bz1) / 2, k };
  }

  function draw() {
    const w = canvas.clientWidth || 800, hh = Math.round(Math.min(620, Math.max(320, w * 0.55)));
    if (canvas.width !== w * devicePixelRatio || canvas.height !== hh * devicePixelRatio) {
      canvas.width = w * devicePixelRatio; canvas.height = hh * devicePixelRatio; canvas.style.height = hh + 'px';
    }
    if (!view) fit();
    HEAT_VIEWS.set(key, { view, layers });
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#0b0c0f';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.imageSmoothingEnabled = false;
    if (layers.map && mapImg && mapImg.complete && mapImg.naturalWidth) {
      const [a, b] = toScreen(mapBox[0], mapBox[1]), [c, d] = toScreen(mapBox[2], mapBox[3]);
      ctx.globalAlpha = layers.heat ? 0.8 : 1;
      ctx.drawImage(mapImg, a, b, c - a, d - b);
      ctx.globalAlpha = 1;
    }
    if (layers.heat) {
      const [a, b] = toScreen(Math.floor(bx0 / C) * C, Math.floor(bz0 / C) * C);
      ctx.imageSmoothingEnabled = true;
      ctx.drawImage(heatLayer, a, b, gw * C * view.k, gh * C * view.k);
    }
    if (layers.path) {
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.lineWidth = Math.max(1.5, Math.min(4, view.k * 0.8)) * devicePixelRatio;
      for (const seg of h.path || []) {
        for (let i = 2; i < seg.length; i += 2) {
          const [a, b] = toScreen(seg[i - 2], seg[i - 1]), [c, d] = toScreen(seg[i], seg[i + 1]);
          const [r, g, bl] = heatColour(norm(valueAt((seg[i - 2] + seg[i]) / 2, (seg[i - 1] + seg[i + 1]) / 2)));
          ctx.strokeStyle = 'rgba(' + r + ',' + g + ',' + bl + ',0.95)';
          ctx.beginPath(); ctx.moveTo(a, b); ctx.lineTo(c, d); ctx.stroke();
        }
      }
    }
    if (layers.marks) {
      let mmax = 1;
      for (const m of h.marks || []) mmax = Math.max(mmax, m[3]);
      for (const [kind, x, z, n] of h.marks || []) {
        const [sx, sy] = toScreen(x, z);
        if (sx < -20 || sy < -20 || sx > canvas.width + 20 || sy > canvas.height + 20) continue;
        const [colour, letter] = MARK_STYLE[kind] || ['#ddd', '•'];
        const r = (4 + 6 * Math.sqrt(n / mmax)) * devicePixelRatio;
        ctx.fillStyle = colour; ctx.strokeStyle = '#0b0c0f'; ctx.lineWidth = 1.5 * devicePixelRatio;
        ctx.beginPath(); ctx.arc(sx, sy, r, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
        if (r > 7 * devicePixelRatio) {
          ctx.fillStyle = '#111'; ctx.font = 'bold ' + Math.round(r * 1.1) + 'px sans-serif';
          ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillText(letter, sx, sy + 0.5);
        }
      }
    }
    for (const [label, x, z] of h.pins) {
      const [sx, sy] = toScreen(x, z);
      ctx.fillStyle = '#fff'; ctx.strokeStyle = '#0b0c0f'; ctx.lineWidth = 2 * devicePixelRatio;
      ctx.beginPath(); ctx.arc(sx, sy, 6 * devicePixelRatio, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
      ctx.font = (12 * devicePixelRatio) + 'px ui-monospace, monospace'; ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
      ctx.strokeText(label, sx + 10 * devicePixelRatio, sy); ctx.fillText(label, sx + 10 * devicePixelRatio, sy);
    }
    // North arrow and scale bar.
    ctx.fillStyle = '#e7e3d8'; ctx.font = (11 * devicePixelRatio) + 'px ui-monospace, monospace'; ctx.textAlign = 'right';
    ctx.fillText('N ↑', canvas.width - 10 * devicePixelRatio, 16 * devicePixelRatio);
    const blocks = [8, 16, 32, 64, 128, 256, 512, 1024].find((b) => b * view.k > 70 * devicePixelRatio) || 1024;
    const y = canvas.height - 12 * devicePixelRatio, x0 = 12 * devicePixelRatio;
    ctx.fillRect(x0, y, blocks * view.k, 3 * devicePixelRatio);
    ctx.textAlign = 'left'; ctx.fillText(blocks + ' blocks', x0, y - 6 * devicePixelRatio);
  }

  // ---------- interaction ----------
  let armed = false;
  const zoomAt = (f, sx, sy) => {
    const [wx, wz] = sx === undefined ? [view.x, view.z] : toWorld(sx, sy);
    const k = Math.max(0.05, Math.min(24, view.k * f));
    view = { k, x: wx - (wx - view.x) * view.k / k, z: wz - (wz - view.z) * view.k / k };
    draw();
  };
  canvas.addEventListener('mousedown', (e) => {
    armed = true;
    const start = { x: e.clientX, y: e.clientY, vx: view.x, vz: view.z };
    const mv = (m) => {
      view = { ...view, x: start.vx - (m.clientX - start.x) * devicePixelRatio / view.k, z: start.vz - (m.clientY - start.y) * devicePixelRatio / view.k };
      draw();
    };
    const up = () => { removeEventListener('mousemove', mv); removeEventListener('mouseup', up); };
    addEventListener('mousemove', mv); addEventListener('mouseup', up);
  });
  canvas.addEventListener('wheel', (e) => {
    if (!armed) return;
    e.preventDefault();
    const r = canvas.getBoundingClientRect();
    zoomAt(e.deltaY < 0 ? 1.25 : 0.8, (e.clientX - r.left) * devicePixelRatio, (e.clientY - r.top) * devicePixelRatio);
  }, { passive: false });
  canvas.addEventListener('mouseleave', () => { armed = false; tip(null); });
  canvas.addEventListener('mousemove', (e) => {
    const r = canvas.getBoundingClientRect();
    const sx = (e.clientX - r.left) * devicePixelRatio, sy = (e.clientY - r.top) * devicePixelRatio;
    const [wx, wz] = toWorld(sx, sy);
    const lines = ['X ' + Math.floor(wx) + ', Z ' + Math.floor(wz)];
    const v = valueAt(wx, wz);
    if (v) lines.push(short(v) + ' ' + (h.unit || '') + ' around here');
    if (layers.marks) {
      for (const [kind, x, z, n, label] of h.marks || []) {
        const [mx, my] = toScreen(x, z);
        if (Math.hypot(mx - sx, my - sy) < 10 * devicePixelRatio) lines.push(label + ' ×' + short(n));
      }
    }
    for (const [label, x, z] of h.pins) {
      const [px, py] = toScreen(x, z);
      if (Math.hypot(px - sx, py - sy) < 10 * devicePixelRatio) lines.push(label);
    }
    tip(e, lines.join('\n'));
  });
  new ResizeObserver(() => draw()).observe(canvas);

  const toggle = (name, label) => {
    const b = el('button', { class: layers[name] ? 'on' : '', on: { click: () => { layers[name] = !layers[name]; b.className = layers[name] ? 'on' : ''; draw(); } } }, label);
    return b;
  };
  const tools = el('div', { class: 'heat-tools' },
    el('div', { class: 'seg' }, el('button', { on: { click: () => zoomAt(1.5) } }, '+'), el('button', { on: { click: () => zoomAt(1 / 1.5) } }, '−'),
      el('button', { on: { click: () => { fit(); draw(); } } }, 'Fit')),
    el('div', { class: 'seg layers' }, h.world ? toggle('map', 'Map') : null, toggle('heat', 'Heat'),
      (h.path || []).length ? toggle('path', 'Path') : null, (h.marks || []).length ? toggle('marks', 'Marks') : null));
  const total = h.cells.reduce((t, c) => t + c[2], 0);
  setTimeout(draw, 0);
  return el('div', { class: 'card' },
    el('div', { class: 'spread' }, el('h3', {}, h.title), el('span', { class: 'muted mono' }, short(total) + ' ' + (h.unit || '') + ' recorded')),
    tools, canvas,
    el('div', { class: 'heat-scale' }, el('span', {}, 'less time'), el('i'), el('span', {}, 'most time'),
      (h.marks || []).length ? el('span', { class: 'heat-keys' }, ...Object.entries(MARK_STYLE).map(([k, [c, l]]) => {
        const dot = el('b', {}, l); dot.style.background = c; return el('span', {}, dot, k);
      })) : null,
      el('span', { class: 'muted' }, 'click the map, then scroll to zoom · drag to move')));
}
