// The player globe: an orthographic canvas globe (no libraries; the panel loads nothing from other
// sites) with a dot where each player roughly connects from, coloured by ping, and a line to the
// server when hycon.json has ServerLocation. Drag to turn, scroll to zoom, click a dot for the profile.
'use strict';

const RAD = Math.PI / 180;

function pingColour(ms) {
  if (ms < 0) return '#8b909b';
  if (ms < 80) return '#4fbf7a';
  if (ms < 150) return '#e2b54b';
  return '#e5534b';
}

// opts: { players: [{uuid, name, lat, lon, ping, online, city, region, country}], server: {lat, lon} | null,
//         focus: player | null (profile view: centred and zoomed in, label always shown), height }
const pingClass = (ms) => ms < 0 ? 'pg-none' : ms < 80 ? 'pg-good' : ms < 150 ? 'pg-mid' : 'pg-bad';

function globe(opts) {
  const canvas = el('canvas', { class: 'globe' });
  const tipEl = el('div', { class: 'globetip', hidden: true });
  const wrap = el('div', { class: 'globewrap' }, canvas, tipEl);
  wrap.style.height = (opts.height || 520) + 'px';
  const players = opts.players || [];
  const focus = opts.focus || null;
  const start = focus || opts.server || { lat: 30, lon: -40 };
  const view = { lon: -start.lon, lat: Math.max(-60, Math.min(60, start.lat)), zoom: focus ? 2.6 : 1 };
  let spin = !focus, hover = null, drag = null, raf = 0, w = 0, h = 0, r = 0, cx = 0, cy = 0;
  const dpr = window.devicePixelRatio || 1;

  // Rotate (lon, lat) by the view and return screen x, y and z (z > 0 is the visible side).
  function project(lon, lat) {
    const l = (lon + view.lon) * RAD, p = lat * RAD, p0 = view.lat * RAD;
    const x = Math.cos(p) * Math.sin(l);
    const y = Math.cos(p0) * Math.sin(p) - Math.sin(p0) * Math.cos(p) * Math.cos(l);
    const z = Math.sin(p0) * Math.sin(p) + Math.cos(p0) * Math.cos(p) * Math.cos(l);
    return [cx + x * r, cy - y * r, z];
  }

  // Points along the great circle between two places, for the line to the server.
  function arc(a, b, n) {
    const v = (q) => [Math.cos(q.lat * RAD) * Math.cos(q.lon * RAD), Math.cos(q.lat * RAD) * Math.sin(q.lon * RAD), Math.sin(q.lat * RAD)];
    const A = v(a), B = v(b);
    const d = Math.acos(Math.max(-1, Math.min(1, A[0] * B[0] + A[1] * B[1] + A[2] * B[2])));
    const out = [];
    for (let i = 0; i <= n; i++) {
      const t = i / n, s = Math.sin(d) || 1;
      const k1 = d ? Math.sin((1 - t) * d) / s : 1 - t, k2 = d ? Math.sin(t * d) / s : t;
      const x = k1 * A[0] + k2 * B[0], y = k1 * A[1] + k2 * B[1], z = k1 * A[2] + k2 * B[2];
      out.push([Math.atan2(y, x) / RAD, Math.atan2(z, Math.hypot(x, y)) / RAD]);
    }
    return out;
  }

  function path(ctx, flat, close) {
    let pen = false;
    for (let i = 0; i < flat.length; i += 2) {
      let [x, y, z] = project(flat[i], flat[i + 1]);
      if (z < 0) {
        if (!close) { pen = false; continue; }
        if (!pen && !anyFront(flat)) return; // wholly on the back
        // A filled shape running round the back: pin the hidden part to the rim, so it closes
        // along the edge instead of cutting across the visible side.
        const d = Math.hypot(x - cx, y - cy) || 1;
        x = cx + (x - cx) / d * r; y = cy + (y - cy) / d * r;
      }
      if (pen) ctx.lineTo(x, y); else { ctx.moveTo(x, y); pen = true; }
    }
    if (close && pen) ctx.closePath();
  }

  function anyFront(flat) {
    for (let i = 0; i < flat.length; i += 2) if (project(flat[i], flat[i + 1])[2] >= 0) return true;
    return false;
  }

  function draw() {
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    // Gold halo, then the sphere.
    const halo = ctx.createRadialGradient(cx, cy, r * 0.96, cx, cy, r * 1.18);
    halo.addColorStop(0, 'rgba(226,181,75,.35)'); halo.addColorStop(1, 'rgba(226,181,75,0)');
    ctx.fillStyle = halo; ctx.beginPath(); ctx.arc(cx, cy, r * 1.18, 0, 7); ctx.fill();
    const sea = ctx.createRadialGradient(cx - r * 0.3, cy - r * 0.3, r * 0.1, cx, cy, r);
    sea.addColorStop(0, '#1d2028'); sea.addColorStop(1, '#0b0c10');
    ctx.fillStyle = sea; ctx.beginPath(); ctx.arc(cx, cy, r, 0, 7); ctx.fill();
    ctx.save(); ctx.beginPath(); ctx.arc(cx, cy, r, 0, 7); ctx.clip();
    // Graticule every 30 degrees.
    ctx.strokeStyle = 'rgba(255,255,255,.05)'; ctx.lineWidth = 1; ctx.beginPath();
    for (let lon = -180; lon < 180; lon += 30) { const f = []; for (let lat = -90; lat <= 90; lat += 3) f.push(lon, lat); path(ctx, f); }
    for (let lat = -60; lat <= 60; lat += 30) { const f = []; for (let lon = -180; lon <= 180; lon += 3) f.push(lon, lat); path(ctx, f); }
    ctx.stroke();
    ctx.fillStyle = '#22252d'; ctx.beginPath();
    for (const ring of WORLD.land) path(ctx, ring, true);
    ctx.fill();
    ctx.strokeStyle = 'rgba(226,181,75,.45)'; ctx.lineWidth = 0.7; ctx.beginPath();
    for (const line of WORLD.lines) path(ctx, line);
    ctx.stroke();
    // Lines to the server: faint ones from everyone so they meet at the server, the focused or hovered one bright.
    const lineTo = focus || hover;
    if (opts.server) {
      ctx.lineWidth = 1; ctx.globalAlpha = 0.22;
      for (const p of players) if (p !== lineTo) {
        ctx.strokeStyle = pingColour(p.ping); ctx.beginPath(); path(ctx, arc(p, opts.server, 32).flat()); ctx.stroke();
      }
      ctx.globalAlpha = 1;
    }
    if (opts.server && lineTo) {
      const pts = arc(lineTo, opts.server, 48);
      ctx.strokeStyle = pingColour(lineTo.ping); ctx.lineWidth = 2.5; ctx.globalAlpha = 0.85; ctx.beginPath();
      path(ctx, pts.flat()); ctx.stroke(); ctx.globalAlpha = 1;
      // a spark running along the line towards the server
      const q = pts[Math.floor(((performance.now() / 1400) % 1) * (pts.length - 1))];
      const [sx, sy, sz] = project(q[0], q[1]);
      if (sz > 0) { ctx.fillStyle = '#fff3c4'; ctx.beginPath(); ctx.arc(sx, sy, 3, 0, 7); ctx.fill(); }
    }
    if (focus) { // the lookup is city-level at best: a soft ~150 km circle says "around here"
      const c = []; const k = 150 / 111;
      for (let a = 0; a <= 360; a += 10) c.push(focus.lon + k * Math.cos(a * RAD) / Math.max(0.2, Math.cos(focus.lat * RAD)), focus.lat + k * Math.sin(a * RAD));
      ctx.fillStyle = 'rgba(226,181,75,.16)'; ctx.strokeStyle = 'rgba(226,181,75,.6)'; ctx.lineWidth = 1;
      ctx.beginPath(); path(ctx, c, true); ctx.fill(); ctx.stroke();
    }
    ctx.restore();
    // Players: offline ones small and dim, online ones bigger with a pulse.
    const t = performance.now() / 1000;
    for (const p of players) {
      const [x, y, z] = project(p.lon, p.lat);
      if (z < 0.02) { p._x = null; continue; }
      p._x = x; p._y = y;
      const col = pingColour(p.ping);
      const size = (p.online ? 4.5 : 3) * Math.min(1.6, Math.sqrt(view.zoom));
      if (p.online) {
        const ph = ((t * 0.8 + (p.lat + p.lon) / 50) % 1 + 1) % 1;
        ctx.strokeStyle = col; ctx.globalAlpha = 1 - ph; ctx.lineWidth = 1.5;
        ctx.beginPath(); ctx.arc(x, y, size + ph * 12, 0, 7); ctx.stroke(); ctx.globalAlpha = 1;
      }
      ctx.fillStyle = col; ctx.globalAlpha = p.online || p === hover || p === focus ? 1 : 0.6;
      ctx.beginPath(); ctx.arc(x, y, p === hover ? size + 2 : size, 0, 7); ctx.fill(); ctx.globalAlpha = 1;
      if (p === focus || p === hover) label(ctx, x, y, p.name, p.ping >= 0 ? p.ping + 'ms' : '');
    }
    if (opts.server) {
      const [x, y, z] = project(opts.server.lon, opts.server.lat);
      if (z > 0) {
        // the glow where every connection meets the server, with rings pulsing out
        const gr = 26 * Math.min(1.8, Math.sqrt(view.zoom));
        const g = ctx.createRadialGradient(x, y, 0, x, y, gr);
        g.addColorStop(0, 'rgba(243,210,124,.55)'); g.addColorStop(1, 'rgba(243,210,124,0)');
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, gr, 0, 7); ctx.fill();
        for (let i = 0; i < 2; i++) {
          const ph = (t * 0.6 + i / 2) % 1;
          ctx.strokeStyle = '#f3d27c'; ctx.globalAlpha = 0.8 * (1 - ph); ctx.lineWidth = 1.5;
          ctx.beginPath(); ctx.arc(x, y, 8 + ph * gr, 0, 7); ctx.stroke();
        }
        ctx.globalAlpha = 1;
        ctx.fillStyle = '#f3d27c'; ctx.strokeStyle = '#0b0c10'; ctx.lineWidth = 2;
        ctx.beginPath(); ctx.moveTo(x, y - 7); ctx.lineTo(x + 7, y); ctx.lineTo(x, y + 7); ctx.lineTo(x - 7, y); ctx.closePath(); ctx.fill(); ctx.stroke();
        label(ctx, x, y, 'SERVER', '');
      }
    }
  }

  function label(ctx, x, y, name, ping) {
    ctx.font = '600 12px ' + getComputedStyle(document.body).getPropertyValue('--mono');
    const nw = ctx.measureText(name + ' ').width;
    ctx.lineWidth = 3; ctx.strokeStyle = 'rgba(8,9,12,.85)';
    ctx.strokeText(name, x + 10, y - 8); ctx.fillStyle = '#e7e3d8'; ctx.fillText(name, x + 10, y - 8);
    if (ping) { ctx.strokeText(ping, x + 10 + nw, y - 8); ctx.fillStyle = '#e2b54b'; ctx.fillText(ping, x + 10 + nw, y - 8); }
  }

  function size() {
    w = wrap.clientWidth; h = wrap.clientHeight;
    canvas.width = w * dpr; canvas.height = h * dpr; canvas.style.width = w + 'px'; canvas.style.height = h + 'px';
    cx = w / 2; cy = h / 2; r = Math.min(w, h) * 0.42 * view.zoom;
  }

  function frame() {
    if (!wrap.isConnected) { cancelAnimationFrame(raf); return; } // the tab changed: stop
    if (spin) view.lon = (view.lon + 0.04) % 360;
    r = Math.min(w, h) * 0.42 * view.zoom;
    draw();
    raf = requestAnimationFrame(frame);
  }

  function nearest(e) {
    const b = canvas.getBoundingClientRect();
    const mx = e.clientX - b.left, my = e.clientY - b.top;
    let best = null, bd = 12;
    for (const p of players) if (p._x !== null && p._x !== undefined) {
      const d = Math.hypot(p._x - mx, p._y - my);
      if (d < bd) { bd = d; best = p; }
    }
    return best;
  }

  canvas.addEventListener('pointerdown', (e) => { drag = { x: e.clientX, y: e.clientY, lon: view.lon, lat: view.lat, moved: false }; spin = false; canvas.setPointerCapture(e.pointerId); });
  canvas.addEventListener('pointermove', (e) => {
    if (drag) {
      const k = 0.35 / view.zoom;
      if (Math.abs(e.clientX - drag.x) + Math.abs(e.clientY - drag.y) > 3) drag.moved = true;
      view.lon = drag.lon + (e.clientX - drag.x) * k;
      view.lat = Math.max(-89, Math.min(89, drag.lat + (e.clientY - drag.y) * k));
      return;
    }
    hover = nearest(e);
    canvas.style.cursor = hover ? 'pointer' : 'grab';
    if (hover) {
      const b = wrap.getBoundingClientRect();
      tipEl.replaceChildren(el('b', {}, hover.name), el('div', {}, [hover.city, hover.region, hover.country].filter(Boolean).join(', ') || 'Unknown place'),
        el('div', { class: 'muted' }, (hover.ping >= 0 ? hover.ping + ' ms median ping' : 'No ping yet') + (hover.online ? ' · online' : '')));
      tipEl.style.left = (e.clientX - b.left + 14) + 'px'; tipEl.style.top = (e.clientY - b.top + 14) + 'px';
      tipEl.hidden = false;
    } else tipEl.hidden = true;
  });
  canvas.addEventListener('pointerup', (e) => {
    const click = drag && !drag.moved;
    drag = null;
    const p = click && nearest(e);
    if (p && p !== focus) { S.tab = 'profile:' + p.uuid; render(); }
  });
  canvas.addEventListener('pointerleave', () => { hover = null; tipEl.hidden = true; });
  // Scrolling over the globe zooms it.
  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    view.zoom = Math.max(0.8, Math.min(12, view.zoom * Math.exp(-e.deltaY * 0.0015)));
  }, { passive: false });
  new ResizeObserver(size).observe(wrap);
  requestAnimationFrame(() => { size(); frame(); });
  return wrap;
}

// ---------- Analytics section ----------

function globeSection() {
  const out = el('div', {}, el('p', { class: 'muted' }, 'Loading globe…'));
  (async () => {
    const d = await api('GET', '/api/geo');
    const list = d.players;
    const online = list.filter((p) => p.online).length;
    const byCountry = new Map();
    for (const p of list) {
      const k = p.country || 'Unknown';
      const c = byCountry.get(k) || { n: 0, on: 0, pings: [] };
      c.n++; if (p.online) c.on++; if (p.ping >= 0) c.pings.push(p.ping);
      byCountry.set(k, c);
    }
    const med = (a) => { if (!a.length) return -1; const s = a.slice().sort((x, y) => x - y); return s[s.length >> 1]; };
    const rows = [...byCountry].sort((a, b) => b[1].n - a[1].n);
    const note = !d.ready ? 'The location database is still downloading (about 60 MB, once a month). Dots appear as players join after it has arrived.'
      : !list.length ? 'No locations yet. Players get a dot when they next join.' : null;
    out.replaceChildren(
      el('div', { class: 'globegrid' },
        el('div', { class: 'card' },
          el('div', { class: 'spread' }, el('h2', {}, 'Player globe', el('span', { class: 'sub' }, '  ' + list.length + ' located, ' + online + ' online')),
            el('div', { class: 'legend-row' }, el('span', {}, el('i', { class: 'lg pg-good' }), '< 80 ms'),
              el('span', {}, el('i', { class: 'lg pg-mid' }), '80–150'), el('span', {}, el('i', { class: 'lg pg-bad' }), '150+'))),
          note ? el('p', { class: 'muted' }, note) : null,
          globe({ players: list, server: d.server }),
          el('p', { class: 'muted small' }, 'Drag to turn, scroll to zoom. Location comes from each player’s last join address (city level at best) and may be off, especially on a VPN or mobile network. '
            + (d.server ? 'Hover a player for their line to the server.' : 'Set ServerLocation in hycon.json to draw lines to the server.')
            + ' Data: DB-IP (db-ip.com, CC BY 4.0).')),
        el('div', { class: 'card' }, el('h2', {}, 'Countries'),
          el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, 'Country'), el('th', {}, 'Ping'), el('th', {}, '#'))),
            el('tbody', {}, rows.length ? rows.map(([name, c]) => { const m = med(c.pings); return el('tr', {}, el('td', {}, name, c.on ? el('span', { class: 'good' }, ' • ' + c.on + ' on') : null),
              el('td', { class: pingClass(m) }, m >= 0 ? m + ' ms' : '–'), el('td', {}, String(c.n))); })
              : el('tr', {}, el('td', { colspan: 3, class: 'muted' }, 'Nobody yet.')))))));
  })().catch((e) => out.replaceChildren(el('p', { class: 'bad' }, e.message)));
  return out;
}

// ---------- Profile card ----------

function locationCard(uuid) {
  const body = el('div', {}, el('p', { class: 'muted' }, 'Loading…'));
  const card = el('div', { class: 'card' }, el('h2', {}, 'Location', el('span', { class: 'sub' }, '  rough, from the last join address')), body);
  (async () => {
    const d = await api('GET', '/api/geo/player?player=' + encodeURIComponent(uuid));
    const p = d.player;
    if (!p) { // still show the globe, so the card is easy to find; the dot comes on their next join
      body.replaceChildren(el('p', { class: 'muted' }, d.ready ? 'No location yet. It is looked up the next time they join.'
        : 'The location database is still downloading; their location is looked up on their next join after that.'),
        globe({ players: [], server: d.server, height: 360 }));
      return;
    }
    const km = d.server ? Math.round(6371 * Math.acos(Math.min(1, Math.sin(p.lat * RAD) * Math.sin(d.server.lat * RAD)
      + Math.cos(p.lat * RAD) * Math.cos(d.server.lat * RAD) * Math.cos((p.lon - d.server.lon) * RAD)))) : -1;
    // Light in fibre covers ~100 km per ms of round trip, so ping far below that means the place is wrong (a VPN, usually).
    const tooFast = km > 0 && p.ping >= 0 && p.ping < km / 100 * 0.8;
    body.replaceChildren(
      el('div', { class: 'globegrid' },
        globe({ players: [p], server: d.server, focus: p, height: 360 }),
        el('table', {}, el('tbody', {},
          el('tr', {}, el('th', {}, 'Place'), el('td', {}, [p.city, p.region].filter(Boolean).join(', ') || 'Unknown')),
          el('tr', {}, el('th', {}, 'Country'), el('td', {}, (p.country || 'Unknown') + (p.cc ? ' (' + p.cc + ')' : ''))),
          el('tr', {}, el('th', {}, 'Median ping'), el('td', { class: pingClass(p.ping) }, p.ping >= 0 ? p.ping + ' ms' : 'not measured yet')),
          km >= 0 ? el('tr', {}, el('th', {}, 'To server'), el('td', {}, km.toLocaleString() + ' km')) : null,
          el('tr', {}, el('th', {}, 'Looked up'), el('td', {}, fmtTime(p.seen)))))),
      tooFast ? el('div', { class: 'verdict warn' }, 'Ping is too low for that distance: they are probably closer than shown (VPN or proxy).') : null);
  })().catch((e) => body.replaceChildren(el('p', { class: 'bad' }, e.message)));
  return card;
}
