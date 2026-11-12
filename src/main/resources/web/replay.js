// Hycon replay: players moving through a stretch of time, drawn on a canvas. Top view (x, z) with each
// player's trail, a dot and a short line for where they look; a height strip below (time across, y up) shows
// jumps, falls and flying. Events (hits) flash as lines between players, red when flagged. Play, pause, a speed
// choice and a time slider. The panel refreshes every few seconds; a replay with the same data keeps its card,
// so playback doesn't restart. Uses el() and tip() from app.js.
'use strict';

const REPLAY_CARDS = new Map();
const REPLAY_COLOURS = ['#f5c542', '#4fa3f7', '#5fd38d', '#f07c6c', '#b07cf0', '#f29bd1', '#7fd6e0', '#c9c9c9'];
// How long an event stays drawn, and how much trail is shown behind each player, in milliseconds.
const REPLAY_EVENT_MS = 600, REPLAY_TRAIL_MS = 4000;

function replayCard(r) {
  const actors = (r.actors || []).filter((a) => a.samples.length);
  if (!actors.length) {
    return el('div', { class: 'card' }, el('h3', {}, r.title), el('p', { class: 'muted' }, 'Nothing recorded yet.'));
  }
  let t0 = Infinity, t1 = -Infinity;
  for (const a of actors) { t0 = Math.min(t0, a.samples[0][0]); t1 = Math.max(t1, a.samples[a.samples.length - 1][0]); }
  const key = r.title + '|' + t0 + '|' + t1 + '|' + actors.length;
  const cached = REPLAY_CARDS.get(key);
  if (cached) return cached;
  for (const k of REPLAY_CARDS.keys()) if (k.startsWith(r.title + '|')) REPLAY_CARDS.delete(k);

  // Bounds in blocks, with a margin, kept square so distances look right.
  let x0 = Infinity, x1 = -Infinity, z0 = Infinity, z1 = -Infinity, y0 = Infinity, y1 = -Infinity;
  for (const a of actors) for (const [, x, y, z] of a.samples) {
    x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
  }
  const span = Math.max(8, x1 - x0, z1 - z0) + 6, cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
  y0 -= 1; y1 += 2;

  const top = el('canvas', { class: 'replay' }), side = el('canvas', { class: 'replay side' });
  const slider = el('input', { type: 'range', min: 0, max: 1000, value: 0, class: 'replay-slider' });
  const playBtn = el('button', { class: 'btn small' }, 'Play');
  const speed = el('select', { class: 'small' }, el('option', { value: 1 }, '1x'), el('option', { value: 0.5 }, '0.5x'), el('option', { value: 0.25 }, '0.25x'));
  const clock = el('span', { class: 'muted replay-clock' });
  const legend = el('div', { class: 'replay-legend' }, actors.map((a, i) =>
    el('span', {}, el('i', { style: 'background:' + REPLAY_COLOURS[i % REPLAY_COLOURS.length] }), a.name + (a.main ? ' (this player)' : ''))));
  let now = t0, playing = false, last = 0;

  // The sample at or just before time t, blended with the next one.
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
    const w = c.clientWidth || 700, h = Math.round(w * ratio);
    if (c.width !== w * devicePixelRatio) { c.width = w * devicePixelRatio; c.height = h * devicePixelRatio; c.style.height = h + 'px'; }
  }

  function draw() {
    size(top, 0.6); size(side, 0.18);
    const ctx = top.getContext('2d'), W = top.width, H = top.height, k = Math.min(W, H) / span;
    const sx = (x) => W / 2 + (x - cx) * k, sz = (z) => H / 2 + (z - cz) * k;
    ctx.fillStyle = '#0b0c0f'; ctx.fillRect(0, 0, W, H);
    // A one-block grid every 4 blocks, for a sense of distance.
    ctx.strokeStyle = 'rgba(255,255,255,0.05)'; ctx.lineWidth = 1;
    for (let g = Math.floor((cx - span) / 4) * 4; g < cx + span; g += 4) { ctx.beginPath(); ctx.moveTo(sx(g), 0); ctx.lineTo(sx(g), H); ctx.stroke(); }
    for (let g = Math.floor((cz - span) / 4) * 4; g < cz + span; g += 4) { ctx.beginPath(); ctx.moveTo(0, sz(g)); ctx.lineTo(W, sz(g)); ctx.stroke(); }
    const where = new Map();
    actors.forEach((a, i) => {
      const colour = REPLAY_COLOURS[i % REPLAY_COLOURS.length];
      ctx.strokeStyle = colour; ctx.globalAlpha = 0.45; ctx.lineWidth = (a.main ? 2.5 : 1.5) * devicePixelRatio;
      ctx.beginPath();
      let started = false;
      for (const s of a.samples) {
        if (s[0] < now - REPLAY_TRAIL_MS || s[0] > now) continue;
        if (!started) { ctx.moveTo(sx(s[1]), sz(s[3])); started = true; } else ctx.lineTo(sx(s[1]), sz(s[3]));
      }
      ctx.stroke(); ctx.globalAlpha = 1;
      const p = at(a, now);
      where.set(a.name, p);
      const px = sx(p[1]), pz = sz(p[3]), yaw = p[4];
      ctx.strokeStyle = colour; ctx.lineWidth = 2 * devicePixelRatio;
      ctx.beginPath(); ctx.moveTo(px, pz); ctx.lineTo(px - Math.sin(yaw) * k * 2.5, pz - Math.cos(yaw) * k * 2.5); ctx.stroke();
      ctx.fillStyle = colour; ctx.beginPath(); ctx.arc(px, pz, (a.main ? 6 : 4.5) * devicePixelRatio, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = '#fff'; ctx.font = (11 * devicePixelRatio) + 'px system-ui'; ctx.fillText(a.name, px + 8 * devicePixelRatio, pz - 8 * devicePixelRatio);
    });
    for (const [t, from, to, bad] of r.events || []) {
      if (t > now || t < now - REPLAY_EVENT_MS) continue;
      const a = where.get(from), b = to ? where.get(to) : null;
      if (!a) continue;
      ctx.globalAlpha = 1 - (now - t) / REPLAY_EVENT_MS;
      ctx.strokeStyle = bad ? '#ff4d4d' : '#ffffff'; ctx.lineWidth = (bad ? 3 : 2) * devicePixelRatio;
      if (b) { ctx.beginPath(); ctx.moveTo(sx(a[1]), sz(a[3])); ctx.lineTo(sx(b[1]), sz(b[3])); ctx.stroke(); }
      else { ctx.beginPath(); ctx.arc(sx(a[1]), sz(a[3]), 12 * devicePixelRatio, 0, Math.PI * 2); ctx.stroke(); }
      ctx.globalAlpha = 1;
    }

    // Height over time, with a line at now.
    const s2 = side.getContext('2d'), SW = side.width, SH = side.height;
    const tx = (t) => ((t - t0) / ((t1 - t0) || 1)) * SW, ty = (y) => SH - ((y - y0) / ((y1 - y0) || 1)) * SH;
    s2.fillStyle = '#0b0c0f'; s2.fillRect(0, 0, SW, SH);
    actors.forEach((a, i) => {
      s2.strokeStyle = REPLAY_COLOURS[i % REPLAY_COLOURS.length]; s2.lineWidth = (a.main ? 2 : 1.2) * devicePixelRatio;
      s2.beginPath();
      a.samples.forEach((s, j) => (j ? s2.lineTo(tx(s[0]), ty(s[2])) : s2.moveTo(tx(s[0]), ty(s[2]))));
      s2.stroke();
    });
    for (const [t, , , bad] of r.events || []) {
      if (!bad) continue;
      s2.fillStyle = '#ff4d4d'; s2.fillRect(tx(t) - devicePixelRatio, 0, 2 * devicePixelRatio, SH);
    }
    s2.strokeStyle = '#fff'; s2.lineWidth = devicePixelRatio; s2.beginPath(); s2.moveTo(tx(now), 0); s2.lineTo(tx(now), SH); s2.stroke();

    slider.value = Math.round(((now - t0) / ((t1 - t0) || 1)) * 1000);
    clock.textContent = ((now - t0) / 1000).toFixed(1) + ' s of ' + ((t1 - t0) / 1000).toFixed(1) + ' s';
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
  top.addEventListener('mousemove', (e) => {
    const rect = top.getBoundingClientRect(), k = Math.min(top.width, top.height) / span;
    const mx = (e.clientX - rect.left) * devicePixelRatio, mz = (e.clientY - rect.top) * devicePixelRatio;
    for (const [t, from, to, bad, label] of r.events || []) {
      if (!label || Math.abs(t - now) > REPLAY_EVENT_MS) continue;
      const a = actors.find((x) => x.name === from);
      if (!a) continue;
      const p = at(a, t);
      if (Math.hypot(top.width / 2 + (p[1] - cx) * k - mx, top.height / 2 + (p[3] - cz) * k - mz) < 24 * devicePixelRatio) {
        tip(e, (bad ? 'Flagged' : 'Hit') + '\n' + label); return;
      }
    }
    tip(null);
  });
  top.addEventListener('mouseleave', () => tip(null));
  new ResizeObserver(() => draw()).observe(top);

  const card = el('div', { class: 'card' }, el('h3', {}, r.title), r.subtitle ? el('p', { class: 'muted' }, r.subtitle) : null,
    top, side, el('div', { class: 'replay-controls' }, playBtn, speed, slider, clock), legend);
  REPLAY_CARDS.set(key, card);
  requestAnimationFrame(draw);
  return card;
}
