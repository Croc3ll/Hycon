// Hycon connection map: a force-directed web of the players tied to one player.
// Uses el(), api(), toast() and nameLink() from app.js. All names go in as text, never markup.
'use strict';

const TIE = {
  same: { label: 'Same connection', cls: 'same' },
  cojoin: { label: 'Joins together', cls: 'cojoin' },
  report: { label: 'Reported', cls: 'report' },
  kill: { label: 'Killed', cls: 'kill' },
};

function connectionMap(uuid) {
  const NS = 'http://www.w3.org/2000/svg';
  const summary = el('p', { class: 'muted' }, 'Loading connections…');
  const side = el('div', { class: 'map-side', hidden: true });
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('class', 'map');
  const world = document.createElementNS(NS, 'g');
  const edgeLayer = document.createElementNS(NS, 'g');
  const nodeLayer = document.createElementNS(NS, 'g');
  world.append(edgeLayer, nodeLayer);
  svg.append(world);
  const stats = el('span', { class: 'map-stats' });

  const nodes = new Map();   // id -> node
  const edges = new Map();   // key -> edge
  let view = { x: 0, y: 0, k: 1 };
  let alpha = 0, raf = 0, focus = null, wheelOn = false;

  const btn = (text, fn) => el('button', { on: { click: fn } }, text);
  const tools = el('div', { class: 'map-tools' },
    btn('↻ Settle', () => heat(0.8)), btn('⛶ Fit', fit),
    btn('● Unpin', () => { for (const n of nodes.values()) if (n.id !== uuid) n.pinned = false; heat(0.5); }),
    btn('✕', () => setFocus(null)));

  const legend = el('div', { class: 'legend-row' },
    ...[['center', 'This player'], ['player', 'Player'], ['online', 'Online'], ['banned', 'Banned'], ['shared', 'Shared ban'], ['reported', 'Reported']]
      .map(([c, t]) => el('span', {}, el('i', { class: 'lg node-' + c }), t)),
    ...Object.values(TIE).map((t) => el('span', {}, el('i', { class: 'lg tie-' + t.cls }), t.label)));

  const card = el('div', { class: 'card' },
    el('h2', {}, 'Connection map', el('span', { class: 'sub' }, '  who this player is tied to, and what those players are')),
    summary,
    el('div', { class: 'map-wrap' }, svg, tools, stats, side,
      el('div', { class: 'map-hint' }, 'Click, then scroll = zoom · drag background = pan · drag node = pin')),
    legend);

  // ---------- data ----------
  async function load(id, depth) {
    const d = await api('GET', '/api/graph?player=' + encodeURIComponent(id) + '&depth=' + depth);
    const origin = nodes.get(id);
    for (const n of d.nodes) {
      const old = nodes.get(n.id);
      if (old) { Object.assign(old, n, { level: Math.min(old.level, n.level) }); continue; }
      // New players start near whoever pulled them in, so the web grows outward.
      const a = Math.random() * Math.PI * 2, r = 60 + Math.random() * 60;
      const ox = origin ? origin.x : 0, oy = origin ? origin.y : 0;
      nodes.set(n.id, Object.assign(n, { x: ox + Math.cos(a) * r, y: oy + Math.sin(a) * r, vx: 0, vy: 0, pinned: n.id === uuid && !origin }));
    }
    for (const e of d.edges) edges.set(e.type + ':' + e.a + '>' + e.b, e);
    if (id === uuid) {
      summary.replaceChildren(el('b', {}, String(d.direct)), ' direct, ', el('b', {}, String(d.further)),
        ' further out (players tied to the players they are tied to) · among them ',
        el('b', { class: 'bad' }, d.banned + ' banned'), ', ', el('b', { class: 'warnc' }, d.reported + ' reported'),
        '. Click any player to read their ties, click a tie to walk across, Expand to pull in that player\'s own network.'
        + (d.capped ? ' (Showing the first 150 players.)' : ''));
    }
    draw();
    heat(1);
  }

  // ---------- simulation ----------
  function heat(a) { alpha = Math.max(alpha, a); if (!raf) raf = requestAnimationFrame(tick); }

  function tick() {
    raf = 0;
    const list = [...nodes.values()];
    // Repulsion between every pair (fine for a few hundred players).
    for (let i = 0; i < list.length; i++) {
      for (let j = i + 1; j < list.length; j++) {
        const a = list[i], b = list[j];
        let dx = b.x - a.x, dy = b.y - a.y, d2 = dx * dx + dy * dy || 0.01;
        if (d2 > 250000) continue;
        const f = 1800 / d2, d = Math.sqrt(d2);
        dx /= d; dy /= d;
        a.vx -= dx * f; a.vy -= dy * f; b.vx += dx * f; b.vy += dy * f;
      }
    }
    for (const e of edges.values()) {
      const a = nodes.get(e.a), b = nodes.get(e.b);
      if (!a || !b) continue;
      const dx = b.x - a.x, dy = b.y - a.y, d = Math.sqrt(dx * dx + dy * dy) || 0.01;
      const f = (d - 110) * 0.02;
      a.vx += dx / d * f; a.vy += dy / d * f; b.vx -= dx / d * f; b.vy -= dy / d * f;
    }
    for (const n of list) {
      n.vx -= n.x * 0.002; n.vy -= n.y * 0.002;   // gentle pull to the middle
      if (n.pinned || n === dragging) { n.vx = n.vy = 0; continue; }
      n.x += Math.max(-20, Math.min(20, n.vx * alpha));
      n.y += Math.max(-20, Math.min(20, n.vy * alpha));
      n.vx *= 0.6; n.vy *= 0.6;
    }
    alpha *= 0.97;
    position();
    if (alpha > 0.02) raf = requestAnimationFrame(tick);
  }

  // ---------- drawing ----------
  const degree = () => { const d = new Map(); for (const e of edges.values()) { d.set(e.a, (d.get(e.a) || 0) + 1); d.set(e.b, (d.get(e.b) || 0) + 1); } return d; };

  function draw() {
    const deg = degree();
    edgeLayer.replaceChildren(...[...edges.values()].filter((e) => nodes.has(e.a) && nodes.has(e.b)).map((e) => {
      const line = document.createElementNS(NS, 'line');
      line.setAttribute('class', 'tie tie-' + TIE[e.type].cls);
      line.style.strokeWidth = String(Math.min(4, 1 + Math.log2(e.weight || 1)));
      line.addEventListener('click', (ev) => {
        ev.stopPropagation();
        // Walk across: focus whichever end isn't focused now.
        setFocus(focus && focus.id === e.a ? nodes.get(e.b) : nodes.get(e.a));
      });
      const title = document.createElementNS(NS, 'title');
      title.textContent = nameOf(e.a) + (e.type === 'report' ? ' reported ' : e.type === 'kill' ? ' killed ' : ' ↔ ') + nameOf(e.b)
        + (e.weight > 1 ? ' (' + e.weight + '×)' : '') + ' · ' + TIE[e.type].label;
      line.append(title);
      e.el = line;
      return line;
    }));
    nodeLayer.replaceChildren(...[...nodes.values()].map((n) => {
      const g = document.createElementNS(NS, 'g');
      const cls = ['node'];
      if (n.id === uuid) cls.push('center');
      if (n.sharedBan) cls.push('shared'); else if (n.banned) cls.push('banned');
      else if (n.reports > 0) cls.push('reported');
      if (n.online) cls.push('online');
      if (focus && focus.id === n.id) cls.push('focus');
      g.setAttribute('class', cls.join(' '));
      const r = n.id === uuid ? 12 : 5 + Math.min(7, Math.sqrt(deg.get(n.id) || 1) * 1.6);
      const ring = document.createElementNS(NS, 'circle'); ring.setAttribute('r', r + 4); ring.setAttribute('class', 'ring');
      const dot = document.createElementNS(NS, 'circle'); dot.setAttribute('r', r); dot.setAttribute('class', 'dot');
      const label = document.createElementNS(NS, 'text'); label.setAttribute('x', r + 7); label.setAttribute('y', 4); label.textContent = n.name;
      g.append(ring, dot, label);
      g.addEventListener('mousedown', (ev) => startDrag(ev, n));
      g.addEventListener('click', (ev) => { ev.stopPropagation(); if (!moved) setFocus(n); });
      n.el = g;
      return g;
    }));
    stats.textContent = nodes.size + ' players · ' + edges.size + ' ties';
    // Redrawn lines are new elements: light up the focused player's ties again.
    if (focus) for (const e of edges.values()) if (e.el) e.el.classList.toggle('lit', e.a === focus.id || e.b === focus.id);
    position();
  }

  function position() {
    world.setAttribute('transform', `translate(${view.x},${view.y}) scale(${view.k})`);
    for (const e of edges.values()) {
      const a = nodes.get(e.a), b = nodes.get(e.b);
      if (!e.el || !a || !b) continue;
      e.el.setAttribute('x1', a.x); e.el.setAttribute('y1', a.y); e.el.setAttribute('x2', b.x); e.el.setAttribute('y2', b.y);
    }
    for (const n of nodes.values()) if (n.el) n.el.setAttribute('transform', `translate(${n.x},${n.y})`);
  }

  function fit() {
    const r = svg.getBoundingClientRect();
    if (!nodes.size || !r.width) return;
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const n of nodes.values()) { x0 = Math.min(x0, n.x); y0 = Math.min(y0, n.y); x1 = Math.max(x1, n.x); y1 = Math.max(y1, n.y); }
    const k = Math.min(2, Math.min((r.width - 160) / Math.max(1, x1 - x0), (r.height - 80) / Math.max(1, y1 - y0)));
    view = { k, x: r.width / 2 - (x0 + x1) / 2 * k, y: r.height / 2 - (y0 + y1) / 2 * k };
    position();
  }

  const nameOf = (id) => (nodes.get(id) || { name: id.slice(0, 8) }).name;

  // ---------- focus panel ----------
  function setFocus(n) {
    focus = n;
    for (const m of nodes.values()) if (m.el) m.el.classList.toggle('focus', m === n);
    for (const e of edges.values()) if (e.el) e.el.classList.toggle('lit', !!n && (e.a === n.id || e.b === n.id));
    if (!n) { side.hidden = true; return; }
    const ties = [...edges.values()].filter((e) => e.a === n.id || e.b === n.id);
    side.hidden = false;
    side.replaceChildren(
      el('div', { class: 'spread' }, el('b', {}, n.name), el('button', { on: { click: () => setFocus(null) } }, '✕')),
      el('div', { class: 'muted' }, [n.online ? 'Online' : 'Offline', n.banned ? (n.sharedBan ? 'shared ban' : 'banned') : null,
        n.reports ? n.reports + ' report' + (n.reports > 1 ? 's' : '') : null].filter(Boolean).join(' · ')),
      el('p', { class: 'row' },
        el('button', { class: 'primary', on: { click: () => load(n.id, 1).catch((e) => toast(e.message, true)) } }, 'Expand'),
        el('button', { on: { click: () => { openProfile(n.id); } } }, 'Open profile')),
      el('div', { class: 'map-ties' }, ties.length ? ties.map((e) => {
        const other = e.a === n.id ? e.b : e.a;
        const dir = e.type === 'report' ? (e.a === n.id ? 'reported ' : 'reported by ') : e.type === 'kill' ? (e.a === n.id ? 'killed ' : 'killed by ') : '';
        return el('div', { class: 'map-tie', on: { click: () => setFocus(nodes.get(other)) } },
          el('i', { class: 'lg tie-' + TIE[e.type].cls }),
          (dir ? dir : TIE[e.type].label + ' · ') + nameOf(other) + (e.weight > 1 ? ' ×' + e.weight : ''));
      }) : el('p', { class: 'muted' }, 'No ties on the map yet. Expand to look further.')));
  }

  // ---------- pan, zoom, drag ----------
  let dragging = null, panning = null, moved = false;
  function startDrag(ev, n) {
    ev.stopPropagation();
    dragging = n; moved = false;
    const start = { x: ev.clientX, y: ev.clientY, nx: n.x, ny: n.y };
    const mv = (e) => {
      moved = moved || Math.abs(e.clientX - start.x) + Math.abs(e.clientY - start.y) > 3;
      n.x = start.nx + (e.clientX - start.x) / view.k; n.y = start.ny + (e.clientY - start.y) / view.k;
      n.pinned = true; heat(0.3); position();
    };
    const up = () => { dragging = null; removeEventListener('mousemove', mv); removeEventListener('mouseup', up); };
    addEventListener('mousemove', mv); addEventListener('mouseup', up);
  }
  svg.addEventListener('mousedown', (ev) => {
    wheelOn = true;
    panning = { x: ev.clientX, y: ev.clientY, vx: view.x, vy: view.y };
    const mv = (e) => { view.x = panning.vx + e.clientX - panning.x; view.y = panning.vy + e.clientY - panning.y; position(); };
    const up = () => { removeEventListener('mousemove', mv); removeEventListener('mouseup', up); };
    addEventListener('mousemove', mv); addEventListener('mouseup', up);
  });
  svg.addEventListener('click', () => setFocus(null));
  // Zoom only after a click on the map, so scrolling the page never gets stuck in it.
  svg.addEventListener('wheel', (ev) => {
    if (!wheelOn) return;
    ev.preventDefault();
    const r = svg.getBoundingClientRect(), mx = ev.clientX - r.left, my = ev.clientY - r.top;
    const k = Math.max(0.2, Math.min(4, view.k * (ev.deltaY < 0 ? 1.15 : 1 / 1.15)));
    view.x = mx - (mx - view.x) * k / view.k; view.y = my - (my - view.y) * k / view.k; view.k = k;
    position();
  }, { passive: false });
  svg.addEventListener('mouseleave', () => { wheelOn = false; });

  load(uuid, 2).then(() => setTimeout(fit, 900)).catch((e) => summary.replaceChildren(el('span', { class: 'bad' }, e.message)));
  return card;
}
