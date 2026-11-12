// Hycon replay: players moving through a stretch of time, seen from above over the real world map (when the
// replay names its world). The camera follows the player the replay is about; + and - zoom. Each player is a
// dot with an arrowhead for where they look and a fading trail. Hits flash as arrows from attacker to target
// with the distance written on them, red when flagged. A height strip below shows jumps, falls and flying over
// the whole replay, with flagged moments marked. The panel refreshes every few seconds; a replay with the same
// data keeps its card, so playback doesn't restart. Uses el() and tip() from app.js.
'use strict';

const REPLAY_CARDS = new Map();
const REPLAY_COLOURS = ['#f5c542', '#4fa3f7', '#5fd38d', '#f07c6c', '#b07cf0', '#f29bd1', '#7fd6e0', '#c9c9c9'];
// How long a hit stays drawn, and how much trail shows behind each player, in milliseconds.
const REPLAY_EVENT_MS = 700, REPLAY_TRAIL_MS = 3000;
// Blocks around the followed player to fit on screen, before zoom, and the zoom steps allowed.
const REPLAY_MIN_VIEW = 10, REPLAY_MAX_VIEW = 40, REPLAY_ZOOMS = [0.5, 0.7, 1, 1.4, 2, 2.8];
const REPLAY_FLAG = '#ff4d4d';

function replayCard(r) {
  const actors = (r.actors || []).filter((a) => a.samples.length);
  if (!actors.length) {
    return el('div', { class: 'card' }, el('h3', {}, r.title), el('p', { class: 'muted' }, 'Nothing recorded yet.'));
  }
  let t0 = Infinity, t1 = -Infinity;
  for (const a of actors) { t0 = Math.min(t0, a.samples[0][0]); t1 = Math.max(t1, a.samples[a.samples.length - 1][0]); }
  const main = actors.find((a) => a.main) || actors[0];
  // The same data keeps its card (playback goes on); the ground arriving after the first render counts as new
  // data, as does any change in heights (demo players are lifted onto the ground once it's in).
  const ground = r.terrain ? r.terrain.x0 + ',' + r.terrain.z0 + ',' + r.terrain.w + 'x' + r.terrain.d : 'flat';
  const key = r.title + '|' + t0 + '|' + t1 + '|' + actors.length + '|' + ground + '|' + main.samples[0][2];
  const cached = REPLAY_CARDS.get(key);
  if (cached) return cached;
  for (const k of REPLAY_CARDS.keys()) if (k.startsWith(r.title + '|')) REPLAY_CARDS.delete(k);

  const colourOf = new Map(actors.map((a, i) => [a.name, REPLAY_COLOURS[i % REPLAY_COLOURS.length]]));
  const events = (r.events || []).slice().sort((a, b) => a[0] - b[0]);

  // Whole-replay bounds: the map image covers them; the height strip uses the height range.
  let x0 = Infinity, x1 = -Infinity, z0 = Infinity, z1 = -Infinity, y0 = Infinity, y1 = -Infinity;
  for (const a of actors) for (const [, x, y, z] of a.samples) {
    x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
  }
  y0 = Math.floor(y0 - 1); y1 = Math.ceil(y1 + 1);

  // The starting zoom fits the people the watched player hits (or is hit by): how far they get, at most.
  // Bystanders who wander off don't shrink the fight; zoom out to see them.
  const involved = new Set(events.flatMap(([, from, to]) => (from === main.name ? [to] : to === main.name ? [from] : [])));
  let reachOut = REPLAY_MIN_VIEW;
  for (const a of actors) if (a !== main && (involved.size === 0 || involved.has(a.name))) for (const s of a.samples) {
    const p = at(main, s[0]);
    reachOut = Math.max(reachOut, Math.hypot(s[1] - p[1], s[3] - p[3]) + 4);
  }
  reachOut = Math.min(REPLAY_MAX_VIEW, reachOut);
  let zoom = 2; // index into REPLAY_ZOOMS

  // The real world map under the replay, from the same images as the heatmap.
  let mapImg = null, mapBox = null;
  if (r.world) {
    const ch = (b) => Math.floor(b / 32);
    let cx0 = ch(x0 - 48), cx1 = ch(x1 + 48), cz0 = ch(z0 - 48), cz1 = ch(z1 + 48);
    const clip = (a, b) => { if (b - a < 24) return [a, b]; const m = Math.floor((a + b) / 2); return [m - 11, m + 12]; };
    [cx0, cx1] = clip(cx0, cx1); [cz0, cz1] = clip(cz0, cz1);
    mapBox = [cx0 * 32, cz0 * 32, (cx1 + 1) * 32, (cz1 + 1) * 32];
    mapImg = new Image();
    mapImg.onload = () => draw();
    mapImg.src = '/api/worldmap?world=' + encodeURIComponent(r.world) + '&cx0=' + cx0 + '&cz0=' + cz0 + '&cx1=' + cx1 + '&cz1=' + cz1;
  }

  const top = el('canvas', { class: 'replay' }), side = el('canvas', { class: 'replay side' });
  // The 3D view (replay3d.js) shows first; the Map button switches to the flat view from above.
  const box3d = el('div', { class: 'replay3d-box' });
  const viewBtn = el('button', { class: 'btn small', title: 'Switch between the 3D view and the map from above' }, 'Map view');
  const resetBtn = el('button', { class: 'btn small', title: 'Put the camera back behind the watched player' }, 'Reset camera');
  let mode = '3d', v3 = null;
  top.hidden = true;
  box3d.append(el('p', { class: 'muted' }, 'Loading the 3D view...'));
  replay3d(box3d, r, actors, main, colourOf, at, events, REPLAY_FLAG, REPLAY_EVENT_MS, REPLAY_TRAIL_MS).then((view) => {
    v3 = view;
    box3d.querySelector('p')?.remove();
    draw();
  }).catch((err) => {
    box3d.replaceChildren(el('p', { class: 'muted' }, 'The 3D view could not load here (' + (err && err.message ? err.message : err)
      + '), so the map view is shown.'));
    mode = 'map'; top.hidden = false; viewBtn.hidden = true; draw();
  });
  viewBtn.addEventListener('click', () => {
    mode = mode === '3d' ? 'map' : '3d';
    top.hidden = mode !== 'map';
    box3d.hidden = mode !== '3d';
    viewBtn.textContent = mode === '3d' ? 'Map view' : '3D view';
    zoomIn.hidden = zoomOut.hidden = mode === '3d';
    resetBtn.hidden = mode !== '3d';
    if (mode === '3d' && v3) v3.resize();
    draw();
  });
  const slider = el('input', { type: 'range', min: 0, max: 1000, value: 0, class: 'replay-slider' });
  const playBtn = el('button', { class: 'btn small' }, 'Play');
  const speed = el('select', { class: 'small' }, el('option', { value: 1 }, '1x'), el('option', { value: 0.5 }, '0.5x'), el('option', { value: 0.25 }, '0.25x'));
  const zoomIn = el('button', { class: 'btn small', title: 'Zoom in' }, '+'), zoomOut = el('button', { class: 'btn small', title: 'Zoom out' }, '−');
  const clock = el('span', { class: 'muted replay-clock' });
  const legend = el('div', { class: 'replay-legend' },
    actors.map((a) => el('span', {}, el('i', { style: 'background:' + colourOf.get(a.name) }), a.name + (a.main ? ' (watched)' : ''))),
    el('span', { class: 'muted' }, 'Faces show where they look · solid arrow: a melee hit · dashed: a shot · with the distance · ',
      el('b', { style: 'color:' + REPLAY_FLAG }, 'red: flagged')));
  let now = t0, playing = false, last = 0;

  // The sample at time t, blended between the two around it.
  function at(a, t) {
    const s = a.samples;
    if (t <= s[0][0]) return s[0];
    if (t >= s[s.length - 1][0]) return s[s.length - 1];
    let lo = 0, hi = s.length - 1;
    while (hi - lo > 1) { const m = (lo + hi) >> 1; if (s[m][0] <= t) lo = m; else hi = m; }
    const p = s[lo], q = s[hi], f = (t - p[0]) / ((q[0] - p[0]) || 1);
    return [t, p[1] + (q[1] - p[1]) * f, p[2] + (q[2] - p[2]) * f, p[3] + (q[3] - p[3]) * f, q[4]];
  }

  function size(c, ratio) {
    const w = c.clientWidth || 700, h = Math.round(Math.min(560, w * ratio));
    if (c.width !== w * devicePixelRatio || c.height !== h * devicePixelRatio) {
      c.width = w * devicePixelRatio; c.height = h * devicePixelRatio; c.style.height = h + 'px';
    }
  }

  // Text with a dark pill behind it, so it reads over the map.
  function label(ctx, text, x, y, colour) {
    ctx.font = '600 ' + (12 * devicePixelRatio) + 'px system-ui';
    const w = ctx.measureText(text).width, pad = 4 * devicePixelRatio, h = 16 * devicePixelRatio;
    ctx.fillStyle = 'rgba(10,11,14,0.78)';
    ctx.fillRect(x - pad, y - h + 4 * devicePixelRatio, w + pad * 2, h);
    ctx.fillStyle = colour;
    ctx.fillText(text, x, y);
  }

  function arrowHead(ctx, x, y, angle, length, colour) {
    ctx.fillStyle = colour;
    ctx.beginPath();
    ctx.moveTo(x + Math.cos(angle) * length, y + Math.sin(angle) * length);
    ctx.lineTo(x + Math.cos(angle + 2.5) * length * 0.6, y + Math.sin(angle + 2.5) * length * 0.6);
    ctx.lineTo(x + Math.cos(angle - 2.5) * length * 0.6, y + Math.sin(angle - 2.5) * length * 0.6);
    ctx.closePath();
    ctx.fill();
  }

  function draw() {
    size(side, 0.14);
    if (mode === '3d') {
      if (v3) v3.draw(now);
      drawHeight();
      slider.value = Math.round(((now - t0) / ((t1 - t0) || 1)) * 1000);
      clock.textContent = ((now - t0) / 1000).toFixed(1) + ' s of ' + ((t1 - t0) / 1000).toFixed(1) + ' s';
      return;
    }
    size(top, 0.5);
    const ctx = top.getContext('2d'), W = top.width, H = top.height, dpr = devicePixelRatio;
    const centre = at(main, now);
    const k = Math.min(W, H) / (reachOut * 2 / REPLAY_ZOOMS[zoom]);
    const sx = (x) => W / 2 + (x - centre[1]) * k, sz = (z) => H / 2 + (z - centre[3]) * k;

    ctx.fillStyle = '#0b0c0f'; ctx.fillRect(0, 0, W, H);
    ctx.imageSmoothingEnabled = false;
    if (mapImg && mapImg.complete && mapImg.naturalWidth) {
      ctx.globalAlpha = 0.7;
      ctx.drawImage(mapImg, sx(mapBox[0]), sz(mapBox[1]), (mapBox[2] - mapBox[0]) * k, (mapBox[3] - mapBox[1]) * k);
      ctx.globalAlpha = 1;
      ctx.fillStyle = 'rgba(0,0,0,0.25)'; ctx.fillRect(0, 0, W, H); // keeps the players and lines standing out
    }

    const where = new Map();
    for (const a of actors) {
      const colour = colourOf.get(a.name);
      ctx.strokeStyle = colour; ctx.lineWidth = (a === main ? 3 : 2) * dpr; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      let prev = null;
      for (const s of a.samples) {
        if (s[0] > now) break;
        if (s[0] < now - REPLAY_TRAIL_MS) continue;
        if (prev) {
          ctx.globalAlpha = 0.15 + 0.6 * (1 - (now - s[0]) / REPLAY_TRAIL_MS);
          ctx.beginPath(); ctx.moveTo(sx(prev[1]), sz(prev[3])); ctx.lineTo(sx(s[1]), sz(s[3])); ctx.stroke();
        }
        prev = s;
      }
      ctx.globalAlpha = 1;
      where.set(a.name, at(a, now));
    }

    for (const [t, from, to, bad, , kind] of events) {
      if (t > now || t < now - REPLAY_EVENT_MS) continue;
      const a = where.get(from), b = to ? where.get(to) : null;
      if (!a) continue;
      const fade = 1 - (now - t) / REPLAY_EVENT_MS, colour = bad ? REPLAY_FLAG : '#ffffff';
      ctx.globalAlpha = 0.35 + 0.65 * fade;
      if (b) {
        const ax = sx(a[1]), az = sz(a[3]), bx = sx(b[1]), bz = sz(b[3]), angle = Math.atan2(bz - az, bx - ax);
        ctx.strokeStyle = colour; ctx.lineWidth = (bad ? 3.5 : 2.5) * dpr;
        ctx.setLineDash(kind === 'shot' ? [10 * dpr, 7 * dpr] : []); // shots dashed, melee solid
        ctx.beginPath(); ctx.moveTo(ax, az); ctx.lineTo(bx - Math.cos(angle) * 9 * dpr, bz - Math.sin(angle) * 9 * dpr); ctx.stroke();
        ctx.setLineDash([]);
        arrowHead(ctx, bx - Math.cos(angle) * 7 * dpr, bz - Math.sin(angle) * 7 * dpr, angle, 11 * dpr, colour);
        const dist = Math.hypot(b[1] - a[1], b[2] - a[2], b[3] - a[3]);
        label(ctx, dist.toFixed(1) + ' blocks', (ax + bx) / 2 + 6 * dpr, (az + bz) / 2 - 6 * dpr, colour);
      } else {
        ctx.strokeStyle = colour; ctx.lineWidth = 3 * dpr;
        ctx.beginPath(); ctx.arc(sx(a[1]), sz(a[3]), 14 * dpr, 0, Math.PI * 2); ctx.stroke();
      }
      ctx.globalAlpha = 1;
    }

    // Whoever was just hit glows red around their dot, fading out (bright for a flagged hit).
    const glow = new Map();
    for (const [t, , to, bad] of events) {
      if (!to || t > now || t < now - REPLAY_EVENT_MS) continue;
      glow.set(to, Math.max(glow.get(to) || 0, (1 - (now - t) / REPLAY_EVENT_MS) * (bad ? 1 : 0.5)));
    }
    for (const a of actors) {
      const p = where.get(a.name), colour = colourOf.get(a.name), px = sx(p[1]), pz = sz(p[3]);
      const r0 = (a === main ? 8 : 6) * dpr;
      const g = glow.get(a.name) || 0;
      if (g > 0) {
        const halo = ctx.createRadialGradient(px, pz, r0, px, pz, r0 * 3.2);
        halo.addColorStop(0, 'rgba(255,60,60,' + (0.85 * g).toFixed(3) + ')');
        halo.addColorStop(1, 'rgba(255,60,60,0)');
        ctx.fillStyle = halo;
        ctx.beginPath(); ctx.arc(px, pz, r0 * 3.2, 0, Math.PI * 2); ctx.fill();
      }
      // Hytale's look: yaw 0 faces -Z (up on the map); x is -sin(yaw), z is -cos(yaw).
      const lookAngle = Math.atan2(-Math.cos(p[4]), -Math.sin(p[4]));
      arrowHead(ctx, px + Math.cos(lookAngle) * r0 * 1.4, pz + Math.sin(lookAngle) * r0 * 1.4, lookAngle, r0 * 1.3, colour);
      ctx.fillStyle = colour; ctx.strokeStyle = '#0b0c0f'; ctx.lineWidth = 2 * dpr;
      ctx.beginPath(); ctx.arc(px, pz, r0, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
      // The watched player's name above their dot, everyone else's below, so close players don't cover each other.
      label(ctx, a.name + '  Y ' + Math.round(p[2]), px + r0 + 6 * dpr, a === main ? pz - r0 : pz + r0 + 14 * dpr, '#ffffff');
    }

    // Scale bar: a round number of blocks about a sixth of the width.
    const want = (W / 6) / k, steps = [1, 2, 5, 10, 20, 50, 100];
    const blocks = steps.find((s) => s >= want) || 100, bar = blocks * k, bx0 = 14 * dpr, by0 = H - 16 * dpr;
    ctx.strokeStyle = '#ffffff'; ctx.lineWidth = 2 * dpr;
    ctx.beginPath(); ctx.moveTo(bx0, by0 - 5 * dpr); ctx.lineTo(bx0, by0); ctx.lineTo(bx0 + bar, by0); ctx.lineTo(bx0 + bar, by0 - 5 * dpr); ctx.stroke();
    label(ctx, blocks + (blocks === 1 ? ' block' : ' blocks'), bx0 + bar + 8 * dpr, by0, '#ffffff');

    drawHeight();
    slider.value = Math.round(((now - t0) / ((t1 - t0) || 1)) * 1000);
    clock.textContent = ((now - t0) / 1000).toFixed(1) + ' s of ' + ((t1 - t0) / 1000).toFixed(1) + ' s';
  }

  // Height over the whole replay: one line per player, flagged moments as red marks on top, a line at now.
  function drawHeight() {
    const s2 = side.getContext('2d'), SW = side.width, SH = side.height, dpr = devicePixelRatio;
    const left = 44 * dpr, pad = 6 * dpr, top0 = 16 * dpr;
    const tx = (t) => left + ((t - t0) / ((t1 - t0) || 1)) * (SW - left - pad);
    const ty = (y) => SH - pad - ((y - y0) / ((y1 - y0) || 1)) * (SH - top0 - pad);
    s2.fillStyle = '#0b0c0f'; s2.fillRect(0, 0, SW, SH);
    s2.font = (11 * dpr) + 'px system-ui'; s2.fillStyle = '#a1a1a6';
    s2.fillText('Height', 6 * dpr, 12 * dpr);
    s2.fillText('Y ' + y1, 6 * dpr, top0 + 10 * dpr);
    s2.fillText('Y ' + y0, 6 * dpr, SH - pad);
    s2.strokeStyle = 'rgba(255,255,255,0.08)'; s2.lineWidth = dpr;
    for (let sec = 0; sec * 1000 <= t1 - t0; sec += 5) {
      const x = tx(t0 + sec * 1000);
      s2.beginPath(); s2.moveTo(x, top0); s2.lineTo(x, SH - pad); s2.stroke();
      s2.fillText(sec + ' s', x + 2 * dpr, 12 * dpr);
    }
    for (const a of actors) {
      s2.strokeStyle = colourOf.get(a.name); s2.lineWidth = (a === main ? 2.5 : 1.5) * dpr;
      s2.globalAlpha = a === main ? 1 : 0.6;
      s2.beginPath();
      a.samples.forEach((s, j) => (j ? s2.lineTo(tx(s[0]), ty(s[2])) : s2.moveTo(tx(s[0]), ty(s[2]))));
      s2.stroke();
    }
    s2.globalAlpha = 1;
    s2.fillStyle = REPLAY_FLAG;
    for (const [t, , , bad] of events) {
      if (!bad) continue;
      const x = tx(t);
      s2.beginPath(); s2.moveTo(x - 4 * dpr, top0 - 2 * dpr); s2.lineTo(x + 4 * dpr, top0 - 2 * dpr); s2.lineTo(x, top0 + 5 * dpr); s2.fill();
    }
    s2.strokeStyle = '#ffffff'; s2.lineWidth = 1.5 * dpr;
    s2.beginPath(); s2.moveTo(tx(now), top0); s2.lineTo(tx(now), SH - pad); s2.stroke();
  }

  function frame(ts) {
    if (!playing) return;
    if (last) now += (ts - last) * Number(speed.value);
    last = ts;
    if (now >= t1) { now = t1; playing = false; playBtn.textContent = 'Play again'; }
    draw();
    if (playing) requestAnimationFrame(frame);
  }

  playBtn.addEventListener('click', () => {
    if (now >= t1) now = t0;
    playing = !playing;
    playBtn.textContent = playing ? 'Pause' : 'Play';
    last = 0;
    if (playing) requestAnimationFrame(frame);
  });
  slider.addEventListener('input', () => { now = t0 + (Number(slider.value) / 1000) * (t1 - t0); draw(); });
  zoomIn.hidden = zoomOut.hidden = true; // the 3D view zooms with the mouse wheel
  resetBtn.addEventListener('click', () => { if (v3) { v3.reset(); draw(); } });
  zoomIn.addEventListener('click', () => { zoom = Math.min(REPLAY_ZOOMS.length - 1, zoom + 1); draw(); });
  zoomOut.addEventListener('click', () => { zoom = Math.max(0, zoom - 1); draw(); });
  // Clicking the height strip jumps there.
  side.addEventListener('click', (e) => {
    const rect = side.getBoundingClientRect(), left = 44;
    const f = Math.max(0, Math.min(1, (e.clientX - rect.left - left) / (rect.width - left - 6)));
    now = t0 + f * (t1 - t0);
    draw();
  });
  top.addEventListener('mousemove', (e) => {
    const rect = top.getBoundingClientRect(), dpr = devicePixelRatio;
    const centre = at(main, now), k = Math.min(top.width, top.height) / (reachOut * 2 / REPLAY_ZOOMS[zoom]);
    const mx = (e.clientX - rect.left) * dpr, mz = (e.clientY - rect.top) * dpr;
    for (const [t, from, , bad, label] of events) {
      if (!label || t > now || now - t > REPLAY_EVENT_MS) continue;
      const a = actors.find((x) => x.name === from);
      if (!a) continue;
      const p = at(a, t);
      if (Math.hypot(top.width / 2 + (p[1] - centre[1]) * k - mx, top.height / 2 + (p[3] - centre[3]) * k - mz) < 28 * dpr) {
        tip(e, (bad ? 'Flagged' : 'Hit') + '\n' + label); return;
      }
    }
    tip(null);
  });
  top.addEventListener('mouseleave', () => tip(null));
  new ResizeObserver(() => { if (v3 && mode === '3d') v3.resize(); draw(); }).observe(box3d);
  new ResizeObserver(() => draw()).observe(top);

  const card = el('div', { class: 'card' }, el('h3', {}, r.title), r.subtitle ? el('p', { class: 'muted' }, r.subtitle) : null,
    box3d, top, side, el('div', { class: 'replay-controls' }, playBtn, speed, viewBtn, resetBtn, zoomOut, zoomIn, slider, clock), legend,
    el('p', { class: 'muted replay-help' }, 'In 3D: drag to turn the camera, scroll to zoom. The camera follows the watched player.'));
  REPLAY_CARDS.set(key, card);
  requestAnimationFrame(draw);
  return card;
}
