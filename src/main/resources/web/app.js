// Hycon panel. Every value from the server is put on the page with textContent (never innerHTML),
// so player names and log lines can't inject markup. Every POST carries the session's CSRF token.
'use strict';

const S = { csrf: null, me: null, tab: 'players', panels: [], timer: null, logAfter: -1 };
const RANK = { VIEWER: 1, MODERATOR: 2, ADMIN: 3, OWNER: 4 };
const $ = (id) => document.getElementById(id);

function el(tag, props, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (k === 'on') for (const [ev, fn] of Object.entries(v)) e.addEventListener(ev, fn);
    else if (k === 'class') e.className = v;
    else if (v !== undefined && v !== null && v !== false) e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat()) if (c !== null && c !== undefined) e.append(c instanceof Node ? c : String(c));
  return e;
}

async function api(method, path, body) {
  const opt = { method, credentials: 'same-origin', headers: {} };
  if (method === 'POST') {
    opt.headers['Content-Type'] = 'application/json';
    opt.headers['X-CSRF-Token'] = S.csrf || '';
    opt.body = JSON.stringify(body || {});
  }
  const res = await fetch(path, opt);
  let data = {};
  try { data = await res.json(); } catch (e) { /* empty body */ }
  if (res.status === 401 && path !== '/api/login') { await boot(); throw new Error(data.error || 'Not logged in'); }
  if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
  return data;
}

function toast(msg, bad) {
  const t = $('toast');
  t.textContent = msg;
  t.className = bad ? 'bad' : '';
  t.hidden = false;
  clearTimeout(toast.h);
  toast.h = setTimeout(() => { t.hidden = true; }, 3500);
}

const can = (level) => S.me && RANK[S.me.level] >= RANK[level];
const fmtTime = (ms) => new Date(ms).toLocaleString();

async function boot() {
  const s = await (await fetch('/api/session', { credentials: 'same-origin' })).json();
  S.csrf = s.csrf;
  S.me = s.loggedIn ? s : null;
  S.setup = s.setupName ? [s.setupName, s.setupPassword] : null;
  if (!S.me) return showLogin();
  if (S.me.mustChange) return showPassword(true);
  try { S.panels = (await api('GET', '/api/panels')).panels; } catch (e) { S.panels = []; }
  if (can('MODERATOR')) { try { S.openReports = (await api('GET', '/api/reports?status=open')).open; } catch (e) { S.openReports = 0; } }
  render();
}

function stop() { clearInterval(S.timer); S.timer = null; }

function showLogin() {
  stop();
  $('tabs').replaceChildren();
  $('me').replaceChildren();
  const name = el('input', { autocomplete: 'username', required: true });
  const pw = el('input', { type: 'password', autocomplete: 'current-password', required: true });
  const msg = el('p', { class: 'bad' });
  const form = el('form', { class: 'card narrow', on: { submit: async (e) => {
    e.preventDefault();
    msg.textContent = '';
    try {
      const r = await api('POST', '/api/login', { name: name.value.trim(), password: pw.value });
      S.csrf = r.csrf;
      await boot();
    } catch (err) { msg.textContent = err.message; pw.value = ''; }
  } } }, el('img', { src: 'logo.svg', class: 'login-logo', alt: '' }),
    el('div', { class: 'login-word' }, el('span', { class: 'brand' }, el('span', { class: 'word' }, 'Hycon'))),
    S.setup ? el('p', { class: 'secret' }, 'First start: log in as ', el('b', {}, S.setup[0]), ' with password ', el('b', { class: 'mono' }, S.setup[1]),
      ' from the server machine, then choose your own password.') : null,
    el('label', {}, 'Account'), name, el('label', {}, 'Password'), pw,
    el('p', {}, el('button', { class: 'primary', type: 'submit' }, 'Log in')), msg);
  $('view').replaceChildren(form);
  name.focus();
}

function showPassword(forced) {
  stop();
  if (forced) $('tabs').replaceChildren();
  const cur = el('input', { type: 'password', autocomplete: 'current-password' });
  const n1 = el('input', { type: 'password', autocomplete: 'new-password', minlength: 10 });
  const n2 = el('input', { type: 'password', autocomplete: 'new-password' });
  const msg = el('p', { class: 'bad' });
  const form = el('form', { class: 'card narrow', on: { submit: async (e) => {
    e.preventDefault();
    if (n1.value !== n2.value) { msg.textContent = 'The new passwords differ'; return; }
    try {
      const r = await api('POST', '/api/password', { current: cur.value, password: n1.value });
      S.csrf = r.csrf;
      toast('Password changed');
      await boot();
    } catch (err) { msg.textContent = err.message; }
  } } },
    el('h2', {}, forced ? 'Choose your own password' : 'Change password'),
    forced ? el('p', { class: 'muted' }, 'This account still has a password Hycon made up. Pick a new one (at least 10 characters) to continue.') : null,
    el('label', {}, 'Current password'), cur, el('label', {}, 'New password'), n1, el('label', {}, 'New password again'), n2,
    el('p', {}, el('button', { class: 'primary', type: 'submit' }, 'Save')), msg);
  renderMe();
  $('view').replaceChildren(form);
  cur.focus();
}

function renderMe() {
  $('me').replaceChildren(
    el('span', { class: 'who' }, S.me.name), el('span', { class: 'lvl' }, S.me.level),
    el('button', { on: { click: async () => { await api('POST', '/api/logout'); await boot(); } } }, 'Log out'));
}

function tabs() {
  const list = [['players', 'Players', 'VIEWER']];
  for (const p of S.panels) list.push(['panel:' + p.id, p.title, 'VIEWER']);
  list.push(['bans', 'Bans', 'VIEWER'], ['reports', S.openReports ? 'Reports (' + S.openReports + ')' : 'Reports', 'MODERATOR'],
    ['console', 'Console', 'MODERATOR']);
  list.push(['audit', 'Audit log', 'ADMIN'], ['sharing', 'Ban sharing', 'OWNER'], ['accounts', 'Accounts', 'OWNER']);
  return list.filter((t) => can(t[2]));
}

function render() {
  stop();
  renderMe();
  const list = tabs();
  if (!S.tab.startsWith('profile:') && !list.some((t) => t[0] === S.tab)) S.tab = 'players';
  $('tabs').replaceChildren(...list.map(([id, title]) =>
    el('button', { class: id === S.tab ? 'active' : '', on: { click: () => { S.tab = id; render(); } } }, title)));
  const view = $('view');
  view.replaceChildren();
  const tab = S.tab;
  if (tab === 'players') players(view);
  else if (tab === 'bans') bans(view);
  else if (tab === 'console') consoleView(view);
  else if (tab === 'audit') audit(view);
  else if (tab === 'accounts') accounts(view);
  else if (tab === 'sharing') sharing(view);
  else if (tab === 'reports') reportsView(view);
  else if (tab.startsWith('panel:')) dashboard(view, tab.slice(6));
  else if (tab.startsWith('profile:')) profile(view, tab.slice(8));
}

function every(ms, fn) { fn(); S.timer = setInterval(fn, ms); }

// ---------- Players / kick / ban ----------

function players(view) {
  const body = el('tbody');
  const count = el('span', { class: 'muted' });
  view.append(el('div', { class: 'card' }, el('div', { class: 'row' }, el('h2', {}, 'Online players '), count),
    el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, 'Name'), el('th', {}, 'World'), el('th', {}, 'UUID'), el('th', {}))), body)));
  view.append(directory());
  every(5000, async () => {
    try {
      const d = await api('GET', '/api/players');
      count.textContent = '(' + d.players.length + ')';
      body.replaceChildren(...d.players.map((p) => el('tr', {}, el('td', {}, nameLink(p.name, p.uuid)), el('td', {}, p.world), el('td', { class: 'mono' }, p.uuid),
        el('td', { class: 'actions' }, can('MODERATOR') ? [
          el('button', { on: { click: () => modDialog('warn', p.uuid, p.name) } }, 'Warn'),
          el('button', { on: { click: () => modDialog('kick', p.name) } }, 'Kick'),
          el('button', { class: 'danger', on: { click: () => modDialog('ban', p.name) } }, 'Ban')] : null))));
      if (!d.players.length) body.replaceChildren(el('tr', {}, el('td', { colspan: 4, class: 'muted' }, 'Nobody online.')));
    } catch (e) { count.textContent = e.message; }
  });
}

// Everyone who has been on the server, online or offline, searchable by name or UUID.
function directory() {
  const q = el('input', { placeholder: 'Search by name or UUID', autocomplete: 'off' });
  const body = el('tbody');
  const info = el('span', { class: 'muted' });
  let t = null;
  const ago = (ms) => { if (!ms) return 'unknown'; const m = Math.round((Date.now() - ms) / 60000);
    return m < 60 ? m + ' min ago' : m < 2880 ? Math.round(m / 60) + ' h ago' : Math.round(m / 1440) + ' days ago'; };
  async function load() {
    try {
      const d = await api('GET', '/api/directory?q=' + encodeURIComponent(q.value.trim()));
      info.textContent = '(' + d.total + ' known' + (d.players.length < d.total ? ', showing ' + d.players.length : '') + ')';
      body.replaceChildren(...d.players.map((p) => el('tr', {},
        el('td', {}, nameLink(p.name, p.uuid)),
        el('td', { class: p.banned ? 'bad' : p.online ? 'good' : 'muted', title: p.last ? fmtTime(p.last) : '' },
          p.banned ? 'Banned' + (p.banEnds ? ' · ' + left(p.banEnds) + ' left' : ' · permanent') : p.online ? 'Online' : 'Last seen ' + ago(p.last)),
        el('td', { class: 'mono' }, p.uuid),
        el('td', { class: 'muted' }, p.first ? fmtTime(p.first) : ''),
        el('td', { class: 'actions' }, can('MODERATOR') ? [
          el('button', { on: { click: () => modDialog('warn', p.uuid, p.name) } }, 'Warn'),
          p.online ? el('button', { on: { click: () => modDialog('kick', p.uuid, p.name) } }, 'Kick') : null,
          p.banned ? el('button', { on: { click: async () => {
            if (!confirm('Unban ' + p.name + '?')) return;
            try { toast((await api('POST', '/api/unban', { player: p.uuid })).message); render(); } catch (e) { toast(e.message, true); }
          } } }, 'Unban')
            : el('button', { class: 'danger', on: { click: () => modDialog('ban', p.uuid, p.name) } }, 'Ban')] : null))));
      if (!d.players.length) body.replaceChildren(el('tr', {}, el('td', { colspan: 5, class: 'muted' }, 'No player matches.')));
    } catch (e) { info.textContent = e.message; }
  }
  q.addEventListener('input', () => { clearTimeout(t); t = setTimeout(load, 250); });
  load();
  return el('div', { class: 'card' }, el('div', { class: 'row' }, el('h2', {}, 'All players '), info), q,
    el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, 'Name'), el('th', {}, 'Status'), el('th', {}, 'UUID'), el('th', {}, 'First seen'), el('th', {}))), body),
    el('p', { class: 'muted' }, 'Everyone who has joined, including players saved by the server before Hycon was installed. The UUID is the permanent ID of the account; it stays the same if the player changes their name.'));
}

function modDialog(action, player, shownAs) {
  const reason = el('input', { maxlength: 200, placeholder: 'Shown to the player and saved in the audit log' });
  const dur = el('select', {}, ...[['permanent', 'Permanent'], ['30m', '30 minutes'], ['1h', '1 hour'], ['12h', '12 hours'],
    ['1d', '1 day'], ['3d', '3 days'], ['7d', '7 days'], ['30d', '30 days']].map(([v, t]) => el('option', { value: v }, t)));
  const msg = el('p', { class: 'bad' });
  const dlg = el('dialog', {});
  const label = { ban: 'Ban', kick: 'Kick', warn: 'Warn' }[action];
  if (action === 'warn') reason.placeholder = 'Shown to the player on screen and saved in their profile';
  const go = el('button', { class: action === 'ban' ? 'primary danger' : 'primary', on: { click: async () => {
    go.disabled = true;
    try {
      const r = await api('POST', '/api/' + action, { player, reason: reason.value, duration: dur.value });
      toast(r.message);
      dlg.close();
      render();
    } catch (e) { msg.textContent = e.message; go.disabled = false; }
  } } }, label);
  dlg.append(el('h2', {}, label + ' ' + (shownAs || player)), el('label', {}, 'Reason'), reason,
    ...(action === 'ban' ? [el('label', {}, 'How long'), dur] : []), // the browser's own append() would print null
    el('p', { class: 'row' }, go, el('button', { on: { click: () => dlg.close() } }, 'Cancel')), msg);
  dlg.addEventListener('close', () => dlg.remove());
  document.body.append(dlg);
  dlg.showModal();
  reason.focus();
}

// Time left until a ban ends, e.g. "2d 4h", "35 min".
function left(ms) {
  const m = Math.max(0, Math.round((ms - Date.now()) / 60000));
  if (m < 60) return m + ' min';
  const h = Math.floor(m / 60);
  return h < 48 ? h + 'h ' + (m % 60) + 'm' : Math.floor(h / 24) + 'd ' + (h % 24) + 'h';
}

function bans(view) {
  let filter = 'all';
  const body = el('tbody');
  const seg = el('div', { class: 'seg' });
  const counts = el('span', { class: 'muted' });
  let data = [];
  const draw = () => {
    seg.replaceChildren(...[['all', 'All'], ['temp', 'Temporary'], ['perm', 'Permanent']].map(([k, t]) =>
      el('button', { class: k === filter ? 'on' : '', on: { click: () => { filter = k; draw(); } } }, t)));
    const temp = data.filter((b) => b.expires).length;
    counts.textContent = data.length + ' active · ' + temp + ' temporary · ' + (data.length - temp) + ' permanent';
    const rows = data.filter((b) => filter === 'all' || (filter === 'temp') === !!b.expires);
    body.replaceChildren(...rows.map((b) => el('tr', {},
      el('td', {}, nameLink(b.name || b.uuid, b.uuid)),
      el('td', {}, el('span', { class: b.expires ? 'badge' : 'badge red' }, b.expires ? 'Temporary' : 'Permanent')),
      el('td', { class: 'mono', title: b.expires ? fmtTime(b.expires) : '' }, b.expires ? left(b.expires) : '—'),
      el('td', {}, b.reason || ''), el('td', {}, b.by), el('td', {}, fmtTime(b.since)),
      el('td', { class: 'actions' }, can('MODERATOR') ? el('button', { on: { click: async () => {
        if (!confirm('Unban ' + (b.name || b.uuid) + '?')) return;
        try { toast((await api('POST', '/api/unban', { player: b.uuid })).message); render(); } catch (e) { toast(e.message, true); }
      } } }, 'Unban') : null))));
    if (!rows.length) body.replaceChildren(el('tr', {}, el('td', { colspan: 7, class: 'muted' }, 'No bans here.')));
  };
  const who = el('input', { placeholder: 'Exact name or UUID (online or offline)' });
  view.append(
    can('MODERATOR') ? el('div', { class: 'card' }, el('h3', {}, 'Ban a player'),
      el('div', { class: 'row' }, who, el('button', { class: 'danger', on: { click: () => who.value.trim() && modDialog('ban', who.value.trim()) } }, 'Ban…'))) : null,
    el('div', { class: 'card' }, el('div', { class: 'spread' }, el('div', {}, el('h2', {}, 'Bans'), counts), seg),
      el('div', { class: 'tablebox' }, el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, 'Player'), el('th', {}, 'Type'), el('th', {}, 'Time left'),
        el('th', {}, 'Reason'), el('th', {}, 'By'), el('th', {}, 'Since'), el('th', {}))), body))));
  // Refreshing every 30 s keeps "time left" counting down and drops bans that just ended.
  every(30000, async () => {
    try { data = (await api('GET', '/api/bans')).bans; draw(); } catch (e) { toast(e.message, true); }
  });
}

// ---------- Player profile ("dossier") ----------

function nameLink(name, uuid) {
  return el('a', { href: '#', class: 'who', on: { click: (e) => { e.preventDefault(); S.tab = 'profile:' + uuid; render(); } } },
    avatar(name, uuid, 22), name);
}

// A player's emblem: their initial in a gold-rimmed diamond, coloured from their UUID, so each
// player always gets the same look. (Hytale has no public image of a player's character.)
function avatar(name, uuid, size) {
  const NS = 'http://www.w3.org/2000/svg';
  let h = 0;
  for (const c of uuid || name) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '0 0 40 40'); svg.setAttribute('width', size); svg.setAttribute('height', size); svg.setAttribute('class', 'avatar');
  const mk = (tag, attrs, text) => { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); if (text) e.textContent = text; svg.append(e); return e; };
  mk('path', { d: 'M20 1 L39 20 L20 39 L1 20 Z', fill: '#e2b54b' });
  mk('path', { d: 'M20 4 L36 20 L20 36 L4 20 Z', fill: `hsl(${h % 360} 45% 28%)` });
  mk('path', { d: 'M20 4 L36 20 L20 20 Z', fill: 'rgba(255,255,255,.08)' });
  mk('text', { x: 20, y: 25.5, 'text-anchor': 'middle', 'font-size': 15, 'font-weight': 700, fill: '#f3e6c4', 'font-family': 'Georgia, serif' },
    (name || '?').replace(/[^A-Za-z0-9]/g, '').charAt(0).toUpperCase() || '?');
  return svg;
}

function profile(view, uuid) {
  const out = el('div');
  view.append(el('p', {}, el('a', { href: '#', on: { click: (e) => { e.preventDefault(); S.tab = 'players'; render(); } } }, '\u2190 Players')), out);
  const TYPE = { WARN: 'Warning', KICK: 'Kick', BAN: 'Ban', UNBAN: 'Unban', NOTE: 'Note' };
  (async () => {
    const p = await api('GET', '/api/profile?player=' + encodeURIComponent(uuid));
    const note = el('input', { maxlength: 500, placeholder: 'Staff note (never shown to the player)' });
    const unban = async () => {
      if (!confirm('Unban ' + p.name + '?')) return;
      try { toast((await api('POST', '/api/unban', { player: p.uuid })).message); render(); } catch (e) { toast(e.message, true); }
    };
    out.replaceChildren(
      el('div', { class: 'card' },
        el('div', { class: 'row' }, avatar(p.name, p.uuid, 56), el('h2', { class: 'big' }, p.name),
          el('span', { class: p.online ? 'good' : 'muted' }, p.online ? 'Online' + (p.world ? ' in ' + p.world : '') : 'Offline')),
        el('table', {}, el('tbody', {},
          el('tr', {}, el('th', {}, 'UUID'), el('td', { class: 'mono' }, p.uuid)),
          el('tr', {}, el('th', {}, 'First seen'), el('td', {}, p.first ? fmtTime(p.first) : 'unknown')),
          el('tr', {}, el('th', {}, 'Last seen'), el('td', {}, p.online ? 'now' : p.last ? fmtTime(p.last) : 'unknown')),
          el('tr', {}, el('th', {}, 'Last world'), el('td', {}, p.world || '')),
          el('tr', {}, el('th', {}, 'Record'), el('td', {}, p.warnings + ' warnings, ' + p.kicks + ' kicks, ' + p.bans + ' bans')))),
        p.ban ? el('p', { class: 'secret bad' }, 'Banned ' + (p.ban.expires ? 'until ' + fmtTime(p.ban.expires) : 'permanently')
          + ' by ' + p.ban.by + ': ' + (p.ban.reason || '')) : null,
        can('MODERATOR') ? el('p', { class: 'row' },
          el('button', { on: { click: () => modDialog('warn', p.uuid, p.name) } }, 'Warn'),
          p.online ? el('button', { on: { click: () => modDialog('kick', p.uuid, p.name) } }, 'Kick') : null,
          p.ban ? el('button', { on: { click: unban } }, 'Unban')
            : el('button', { class: 'danger', on: { click: () => modDialog('ban', p.uuid, p.name) } }, 'Ban')) : null),
      profileSections(p.uuid),
      pingCard(p.uuid),
      can('MODERATOR') ? connectionMap(p.uuid) : null,
      el('div', { class: 'card' }, el('h2', {}, 'History'),
        can('MODERATOR') ? el('form', { class: 'row', on: { submit: async (e) => {
          e.preventDefault();
          try { await api('POST', '/api/note', { player: p.uuid, text: note.value }); toast('Note saved'); render(); } catch (err) { toast(err.message, true); }
        } } }, note, el('button', { type: 'submit' }, 'Add note')) : null,
        el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, 'When'), el('th', {}, 'What'), el('th', {}, 'By'), el('th', {}, 'Reason'))),
          el('tbody', {}, p.history.length ? p.history.map((h) => el('tr', {}, el('td', {}, fmtTime(h.time)),
            el('td', { class: h.type === 'BAN' || h.type === 'WARN' ? 'bad' : '' }, TYPE[h.type] + (h.detail ? ' (' + h.detail + ')' : '')),
            el('td', {}, h.by), el('td', {}, h.reason)))
            : el('tr', {}, el('td', { colspan: 4, class: 'muted' }, 'Clean record.'))))));
  })().catch((e) => out.replaceChildren(el('p', { class: 'bad' }, e.message)));
}

// ---------- Ping (profile) ----------

// Server-measured ping for one player: summary chips, a STEADY/UNSTABLE badge, the chart with
// red bands on spikes, and a plain-language summary. Filled in once the numbers arrive.
function pingCard(uuid) {
  const body = el('div', {}, el('p', { class: 'muted' }, 'Loading ping…'));
  const badge = el('span');
  const card = el('div', { class: 'card' }, el('div', { class: 'spread' },
    el('h2', {}, 'Ping', el('span', { class: 'sub' }, '  server-measured, every minute while online')), badge), body);
  (async () => {
    const d = await api('GET', '/api/ping?player=' + encodeURIComponent(uuid));
    if (!d.samples) {
      body.replaceChildren(el('p', { class: 'muted' }, 'No ping recorded yet. Hycon measures it every minute while the player is online.'));
      return;
    }
    badge.className = d.steady ? 'pill good' : 'pill warn';
    badge.textContent = d.steady ? 'Steady' : 'Unstable';
    const chip = (v, unit, label, warn) => el('div', { class: 'chip-stat' + (warn ? ' warn' : '') },
      el('div', { class: 'v' }, String(v), unit ? el('span', {}, unit) : null), el('div', { class: 'l' }, label));
    const spikes = [];
    for (let i = 0; i < d.points.length; i++) {
      const [t, ms] = d.points[i];
      const calm = (k) => k < 0 || k >= d.points.length || d.points[k][1] < 150;
      if (ms >= 250 && calm(i - 1) && calm(i + 1)) spikes.push([t - 30000, t + 30000]);
    }
    const near = (t) => { let best = d.points[0]; for (const pt of d.points) if (Math.abs(pt[0] - t) < Math.abs(best[0] - t)) best = pt; return best[1]; };
    const byTime = new Map(d.kills.map((k) => [k.time, k]));
    const series = { title: 'Ping', unit: 'ms', area: true, lines: ['Ping'], gaps: spikes, zoomable: true, thresholds: [[150, 'warn'], [250, 'bad']], points: d.points.map(([t, ms]) => [0, t, ms]),
      marks: d.kills.map((k) => [k.time, k.kill && k.ping >= 0 ? k.ping : near(k.time), k.kill ? (k.ping >= 150 ? 'killhigh' : 'kill') : 'death']),
      markText: (t) => { const k = byTime.get(t); return (k.kill ? 'Killed ' : 'Killed by ') + k.other + (k.kill && k.ping >= 0 ? ' at ' + k.ping + ' ms' : ''); } };
    const verdict = d.killCount === 0 ? null
      : el('div', { class: 'verdict ' + (d.lagSuspect ? 'warn' : 'ok') }, d.lagSuspect
        ? d.highPingKills + ' of ' + d.killCount + ' kills came during high ping (over 150 ms), ' + d.spikeKills + ' on a spike. Worth an eye.'
        : 'Only ' + d.highPingKills + ' of ' + d.killCount + ' kills came during high ping - no sign of kills riding the lag.');
    body.replaceChildren(
      el('div', { class: 'chips-stat' },
        chip(d.median, 'ms', 'Median'), chip(d.average, 'ms', 'Average'), chip(d.p95, 'ms', '95% under', d.p95 >= 150),
        chip(d.worst, 'ms', 'Worst', d.worst >= 250), chip(d.best, 'ms', 'Best'), chip(d.jitter, 'ms', 'Jitter', d.jitter >= 30),
        chip(d.over150, '%', '≥ 150 ms', d.over150 > 5), chip(d.over250, '%', '≥ 250 ms', d.over250 > 1),
        chip(d.spikes, '', 'Spikes', d.spikes > 0), chip(d.samples, '', 'Samples')),
      el('p', { class: 'muted' }, 'Last ' + d.days + ' days. Each point is a one-minute average; red bands are spikes (a short lag burst of 250 ms or more). '
        + 'Click the chart, then scroll to zoom; drag to pan.'),
      chart(series),
      el('div', { class: 'legend-row' }, el('span', {}, el('i', { class: 'lg kill' }), 'Kill'), el('span', {}, el('i', { class: 'lg killhigh' }), 'Kill at high ping'),
        el('span', {}, el('i', { class: 'lg death' }), 'Death'), el('span', {}, el('i', { class: 'lg spike' }), 'Ping spike')),
      verdict,
      el('p', { class: 'muted' }, el('b', { class: d.spikes ? 'bad' : '' }, d.spikes + (d.spikes === 1 ? ' spike' : ' spikes')),
        ' over 250 ms · worst ', el('b', {}, d.worst + ' ms'), ' at ' + fmtTime(d.worstAt) + '. A spike is a brief lag burst; '
        + 'steady means 95% of minutes under 150 ms and little jitter.'));
  })().catch((e) => body.replaceChildren(el('p', { class: 'bad' }, e.message)));
  return card;
}

// ---------- Live console ----------

function consoleView(view) {
  const box = el('div', { id: 'console', class: 'mono' });
  const input = el('input', { class: 'mono', placeholder: 'Command, e.g. who  (runs with full console rights)', autocomplete: 'off' });
  // Tab completion: the server answers with full replacements for the last word.
  const picks = el('div', { class: 'complete-list', hidden: true });
  let cycle = null;   // { base, options, i } while pressing Tab repeatedly
  const lastWordStart = (v) => v.lastIndexOf(' ') + 1;
  const apply = (base, word) => { input.value = base + word; };
  async function tabComplete() {
    if (cycle) {            // Tab again: next choice
      cycle.i = (cycle.i + 1) % cycle.options.length;
      apply(cycle.base, cycle.options[cycle.i]);
      [...picks.children].forEach((c, k) => c.classList.toggle('on', k === cycle.i));
      return;
    }
    const v = input.value;
    let d;
    try { d = await api('GET', '/api/complete?line=' + encodeURIComponent(v)); } catch (e) { toast(e.message, true); return; }
    const base = v.slice(0, lastWordStart(v));
    const kinds = new Map(d.options.map((o) => [o.text, o.kind]));
    d.options = d.options.map((o) => o.text);
    if (!d.options.length) { toast('Nothing to complete'); return; }
    if (d.options.length === 1) { apply(base, d.options[0] + ' '); return; }
    cycle = { base, options: d.options, i: 0 };
    apply(base, d.options[0]);
    picks.replaceChildren(...d.options.map((o, k) => el('div', { class: (k === 0 ? 'on ' : '') + 'k-' + kinds.get(o), title: { online: 'Online', offline: 'Offline' }[kinds.get(o)] || '', on: { mousedown: (ev) => {
      ev.preventDefault(); apply(base, o + ' '); cycle = null; picks.hidden = true; input.focus();
    } } }, o)));
    picks.hidden = false;
  }
  input.addEventListener('blur', () => { picks.hidden = true; cycle = null; });
  const history = []; let hi = 0;
  S.logAfter = -1;
  view.append(el('div', { class: 'card' }, el('h2', {}, 'Console'), box,
    can('ADMIN') ? el('form', { class: 'row', on: { submit: async (e) => {
      e.preventDefault();
      const cmd = input.value.trim();
      if (!cmd) return;
      history.push(cmd); hi = history.length;
      input.value = '';
      try {
        const r = await api('POST', '/api/command', { command: cmd });
        if (r.error) toast(r.error, true);
        if (!r.finished) toast('Still running; later output appears in the log');
      } catch (err) { toast(err.message, true); }
    } } }, el('div', { class: 'complete-box' }, input, picks), el('button', { class: 'primary', type: 'submit' }, 'Run'))
      : el('p', { class: 'muted' }, 'Running commands needs the ADMIN level.')),
    can('ADMIN') ? el('p', { class: 'muted' }, 'Tab completes commands and player names; press it again to go through the choices.') : null);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Tab') { e.preventDefault(); tabComplete(); return; }
    if (e.key !== 'Shift') { cycle = null; picks.hidden = true; }
    if (e.key === 'Escape') return;
    if (e.key === 'ArrowUp' && hi > 0) { input.value = history[--hi]; e.preventDefault(); }
    if (e.key === 'ArrowDown') { hi = Math.min(history.length, hi + 1); input.value = history[hi] || ''; e.preventDefault(); }
  });
  every(1500, async () => {
    try {
      const d = await api('GET', '/api/log?after=' + S.logAfter);
      if (!d.lines.length) return;
      const stick = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
      for (const [seq, text] of d.lines) {
        S.logAfter = seq;
        const cls = text.startsWith('[Hycon]') ? 'mine' : /\[(SEVERE|ERROR)\]/.test(text) ? 'err' : /\[WARN/.test(text) ? 'warn' : '';
        box.append(el('div', { class: cls }, text));
      }
      while (box.childElementCount > 2000) box.firstChild.remove();
      if (stick) box.scrollTop = box.scrollHeight;
    } catch (e) { /* shown by the next successful poll */ }
  });
  if (can('ADMIN')) input.focus();
}

// ---------- Audit + accounts ----------

function audit(view) {
  const body = el('tbody');
  view.append(el('div', { class: 'card' }, el('h2', {}, 'Audit log'), el('p', { class: 'muted' }, 'Newest 200 entries. The full history is in audit.log in the Hycon folder.'),
    el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, 'Time'), el('th', {}, 'Account'), el('th', {}, 'Address'), el('th', {}, 'Action'), el('th', {}, 'Target'), el('th', {}, 'Detail'))), body)));
  (async () => {
    const d = await api('GET', '/api/audit');
    body.replaceChildren(...d.entries.map((e) => el('tr', {}, el('td', {}, fmtTime(e.time)), el('td', {}, e.admin), el('td', { class: 'mono' }, e.ip),
      el('td', { class: e.ok ? '' : 'bad' }, e.action + (e.ok ? '' : ' ✗')), el('td', {}, e.target || ''), el('td', {}, e.detail || ''))));
  })().catch((e) => toast(e.message, true));
}

function accounts(view) {
  const body = el('tbody');
  const secret = el('div');
  const name = el('input', { placeholder: 'New account name' });
  const lvl = el('select', {}, ...Object.keys(RANK).map((l) => el('option', { value: l }, l)));
  const showSecret = (who, pw) => secret.replaceChildren(el('div', { class: 'secret' }, 'Temporary password for ', el('b', {}, who), ': ',
    el('span', { class: 'mono' }, pw), el('div', { class: 'muted' }, 'Shown once. They must change it at first login.')));
  const levelSelect = (a) => {
    const s = el('select', { on: { change: async () => {
      try { await api('POST', '/api/accounts/level', { name: a.name, level: s.value }); toast('Level changed'); } catch (e) { toast(e.message, true); render(); }
    } } }, ...Object.keys(RANK).map((l) => el('option', { value: l, selected: l === a.level }, l)));
    return s;
  };
  view.append(el('div', { class: 'card' }, el('h2', {}, 'Panel accounts'),
    el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, 'Name'), el('th', {}, 'Level'), el('th', {}, 'Status'), el('th', {}))), body),
    el('h3', {}, 'Add account'),
    el('div', { class: 'row' }, name, lvl, el('button', { class: 'primary', on: { click: async () => {
      try { const r = await api('POST', '/api/accounts/create', { name: name.value.trim(), level: lvl.value }); showSecret(name.value.trim(), r.password); name.value = ''; load(); } catch (e) { toast(e.message, true); }
    } } }, 'Create')), secret,
    el('p', { class: 'muted' }, 'VIEWER sees players, bans and dashboards. MODERATOR also kicks, bans and reads the console. ADMIN also runs console commands and reads the audit log. OWNER also manages accounts.')));
  async function load() {
    const d = await api('GET', '/api/accounts');
    body.replaceChildren(...d.accounts.map((a) => el('tr', {}, el('td', {}, a.name), el('td', {}, levelSelect(a)),
      el('td', { class: 'muted' }, a.mustChange ? 'must change password' : ''),
      el('td', { class: 'actions' },
        el('button', { on: { click: async () => {
          if (!confirm('Give ' + a.name + ' a new temporary password? They are logged out.')) return;
          try { showSecret(a.name, (await api('POST', '/api/accounts/reset', { name: a.name })).password); load(); } catch (e) { toast(e.message, true); }
        } } }, 'Reset password'),
        el('button', { class: 'danger', on: { click: async () => {
          if (!confirm('Remove account ' + a.name + '?')) return;
          try { await api('POST', '/api/accounts/delete', { name: a.name }); load(); } catch (e) { toast(e.message, true); }
        } } }, 'Remove')))));
  }
  load().catch((e) => toast(e.message, true));
}

// ---------- Reports ----------

function reportsView(view) {
  let status = 'open';
  const body = el('tbody');
  const seg = el('div', { class: 'seg' });
  const drawSeg = () => seg.replaceChildren(...[['open', 'Open'], ['handled', 'Handled'], ['dismissed', 'Dismissed'], ['all', 'All']]
    .map(([k, t]) => el('button', { class: k === status ? 'on' : '', on: { click: () => { status = k; drawSeg(); load(); } } }, t)));
  drawSeg();
  view.append(el('div', { class: 'card' }, el('div', { class: 'spread' },
    el('div', {}, el('h2', {}, 'Reports'), el('span', { class: 'muted' }, 'Players report with /report <player> <reason>. Staff with hycon.reports.notify see new ones in chat.')), seg),
    el('div', { class: 'tablebox' }, el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, 'When'), el('th', {}, 'Reported'), el('th', {}, 'By'),
      el('th', {}, 'Reason'), el('th', {}, 'Status'), el('th', {}))), body))));
  async function close(r, st) {
    const note = st === 'handled' ? prompt('What was done? (optional, saved in their history)', '') : '';
    if (note === null) return;
    try { toast((await api('POST', '/api/reports/close', { id: r.id, status: st, note })).message); S.openReports = Math.max(0, (S.openReports || 1) - 1); render(); }
    catch (e) { toast(e.message, true); }
  }
  async function load() {
    try {
      const d = await api('GET', '/api/reports?status=' + status);
      S.openReports = d.open;
      body.replaceChildren(...d.reports.map((r) => el('tr', {},
        el('td', {}, fmtTime(r.time)),
        el('td', {}, nameLink(r.targetName, r.target), r.againstTotal > 1 ? el('span', { class: 'badge red', title: 'reports against this player' }, ' ' + r.againstTotal + '×') : null),
        el('td', {}, nameLink(r.reporterName, r.reporter)),
        el('td', {}, r.reason),
        el('td', {}, el('span', { class: r.status === 'OPEN' ? 'badge' : 'badge dim' }, r.status.toLowerCase()), r.closedBy ? el('div', { class: 'muted' }, r.closedBy + (r.note ? ': ' + r.note : '')) : null),
        el('td', { class: 'actions' }, r.status === 'OPEN' ? [
          el('button', { on: { click: () => modDialog('warn', r.target, r.targetName) } }, 'Warn'),
          el('button', { class: 'danger', on: { click: () => modDialog('ban', r.target, r.targetName) } }, 'Ban'),
          el('button', { on: { click: () => close(r, 'handled') } }, 'Handled'),
          el('button', { on: { click: () => close(r, 'dismissed') } }, 'Dismiss')] : null))));
      if (!d.reports.length) body.replaceChildren(el('tr', {}, el('td', { colspan: 6, class: 'muted' }, status === 'open' ? 'No open reports.' : 'Nothing here.')));
    } catch (e) { toast(e.message, true); }
  }
  load();
}

// ---------- Ban sharing ----------

function sharing(view) {
  const out = el('div');
  view.append(el('h2', { class: 'big' }, 'Ban sharing'),
    el('p', { class: 'muted' }, 'Share bans with other servers running Hycon. You swap one share code each way; '
      + 'nobody needs your panel address. For a global ban list, every server follows every other (or all follow one main server).'), out);
  const secret = el('div');
  const showCode = (forName, code) => secret.replaceChildren(el('div', { class: 'secret' }, 'Share code for ', el('b', {}, forName), ':',
    el('div', { class: 'mono code' }, code),
    el('p', { class: 'row' }, el('button', { on: { click: () => navigator.clipboard.writeText(code).then(() => toast('Copied')) } }, 'Copy'),
      el('span', { class: 'muted' }, 'Send it privately. They paste it under "Follow a server".'))));
  async function load() {
    const d = await api('GET', '/api/sharing');
    const act = async (path, body, okMsg) => {
      try { const r = await api('POST', path, body); toast(r.message || okMsg); await load(); return r; } catch (e) { toast(e.message, true); return null; }
    };
    const addr = el('input', { value: d.publicAddress || '', placeholder: 'e.g. play.example.com or 203.0.113.7 (same as players use)' });
    const keyName = el('input', { placeholder: 'Which server gets it, e.g. "Server 2"' });
    const subName = el('input', { placeholder: 'Name, e.g. "Server 1"' });
    const subCode = el('input', { placeholder: 'Their share code (hycon1_...)', class: 'mono' });
    out.replaceChildren(
      el('div', { class: 'card' },
        el('h3', {}, 'Follow a server'),
        el('p', { class: 'muted' }, 'Their bans are added here and lifted when they lift them, checked every ' + d.intervalMinutes
          + ' minutes. Bans made on this server are never changed. Unbanning a shared player here keeps them unbanned here.'),
        el('div', { class: 'row' }, subName, subCode, el('button', { class: 'primary', on: { click: async () => {
          if (await act('/api/sharing/sub/add', { name: subName.value.trim(), code: subCode.value.trim() }, 'Following')) { subName.value = subCode.value = ''; }
        } } }, 'Follow')),
        el('div', { class: 'tablebox' }, el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, 'Server'), el('th', {}, 'Status'),
          el('th', { class: 'num' }, 'Bans here'), el('th', {}))),
          el('tbody', {}, d.subscriptions.length ? d.subscriptions.map((x) => el('tr', {},
            el('td', {}, x.name),
            el('td', { class: x.error ? 'bad' : x.enabled ? 'good' : 'muted' },
              !x.enabled ? 'Paused' : x.error ? x.error : x.lastSync ? 'Synced ' + fmtTime(x.lastSync) : 'Waiting for first check'),
            el('td', { class: 'num' }, x.imported === undefined ? '' : String(x.imported)),
            el('td', { class: 'actions' },
              el('button', { on: { click: () => act('/api/sharing/sub/enable', { name: x.name, enabled: !x.enabled }, x.enabled ? 'Paused' : 'Resumed') } }, x.enabled ? 'Pause' : 'Resume'),
              el('button', { class: 'danger', on: { click: () => confirm('Stop following ' + x.name + '? Every ban that came from it is lifted here.')
                && act('/api/sharing/sub/remove', { name: x.name }, 'Removed') } }, 'Remove'))))
            : el('tr', {}, el('td', { colspan: 4, class: 'muted' }, 'Not following any server yet.'))))),
        el('p', {}, el('button', { on: { click: () => act('/api/sharing/sync', {}, 'Checking') } }, 'Check now'))),
      el('div', { class: 'card' },
        el('h3', {}, 'Share this server\'s bans'),
        el('label', {}, 'This server\'s address (how other servers reach it)'),
        el('div', { class: 'row' }, addr, el('button', { on: { click: () => act('/api/sharing/address', { address: addr.value }, 'Saved') } }, 'Save')),
        el('p', { class: d.shareError ? 'bad' : 'muted' }, d.shareError ? d.shareError
          : d.shareRunning ? 'Share port ' + d.sharePort + ' is open. Other servers must be able to reach this address on that port (open it in your firewall/router if needed).'
          : 'The share port (' + d.sharePort + ') opens when you create the first code.'),
        el('div', { class: 'tablebox' }, el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, 'Code for'), el('th', {}, 'Created'), el('th', {}))),
          el('tbody', {}, d.keys.length ? d.keys.map((k) => el('tr', {}, el('td', {}, k.name), el('td', {}, fmtTime(k.created)),
            el('td', { class: 'actions' },
              el('button', { on: { click: async () => { const r = await act('/api/sharing/key/code', { name: k.name }); if (r) showCode(k.name, r.code); } } }, 'Show code'),
              el('button', { class: 'danger', on: { click: () => confirm('Revoke the code for ' + k.name + '? They stop receiving your bans.')
                && act('/api/sharing/key/revoke', { name: k.name }, 'Revoked') } }, 'Revoke'))))
            : el('tr', {}, el('td', { colspan: 3, class: 'muted' }, 'No codes yet.'))))),
        el('h3', {}, 'New share code'),
        el('div', { class: 'row' }, keyName, el('button', { class: 'primary', on: { click: async () => {
          const r = await act('/api/sharing/key/create', { name: keyName.value.trim() }, 'Code created');
          if (r) { showCode(keyName.value.trim(), r.code); keyName.value = ''; }
        } } }, 'Create code')), secret));
  }
  load().catch((e) => out.replaceChildren(el('p', { class: 'bad' }, e.message)));
}

// ---------- Dashboards (HyconPanel API) ----------

const RANGES = [['24h', '24 hours'], ['7d', '7 days'], ['30d', '30 days'], ['all', 'All time']];

function dashboard(view, id) {
  let range = S.range || '24h';
  const out = el('div');
  const seg = el('div', { class: 'seg' });
  const drawSeg = () => seg.replaceChildren(...RANGES.map(([r, t]) => el('button', { class: r === range ? 'on' : '',
    on: { click: () => { range = S.range = r; drawSeg(); load(); } } }, t)));
  drawSeg();
  const title = el('h2', { class: 'big' });
  view.append(el('div', { class: 'spread' }, title, seg), out);
  // Chart/table choices survive the 15 s refresh.
  const modes = {};
  async function load() {
    try {
      const d = await api('GET', '/api/panel?id=' + encodeURIComponent(id) + '&range=' + range);
      title.textContent = d.title;
      out.replaceChildren(...blocks(d.blocks, modes));
    } catch (e) { out.replaceChildren(el('p', { class: 'bad' }, e.message)); }
  }
  every(15000, load);
}

function blocks(list, modes) {
  const nodes = []; let tiles = null; let grid = null; let notes = null;
  for (const b of list) {
    if (b.type !== 'note') notes = null;
    if (b.type === 'stat') {
      grid = null;
      if (!tiles) { tiles = el('div', { class: 'tiles' }); nodes.push(tiles); }
      tiles.append(el('div', { class: 'tile' }, el('div', { class: 'h' }, b.label), el('div', { class: 'v' }, b.value), b.hint ? el('div', { class: 's' }, b.hint) : null));
      continue;
    }
    tiles = null;
    if (b.type === 'bars' || b.type === 'columns' || b.type === 'pie') {
      if (!grid) { grid = el('div', { class: 'grid3' }); nodes.push(grid); }
      grid.append(b.type === 'bars' ? barsCard(b) : b.type === 'pie' ? pieCard(b) : columnsCard(b));
      continue;
    }
    grid = null;
    if (b.type === 'note') {
      // Consecutive notes share one boxed panel; a short line without a full stop is its heading.
      const heading = b.text.length <= 40 && !/[.!?:]$/.test(b.text.trim());
      if (!notes || heading) { notes = el('div', { class: 'notebox' }); nodes.push(notes); }
      notes.append(heading ? el('h3', {}, b.text) : el('p', {}, b.text));
      continue;
    }
    else if (b.type === 'series') nodes.push(seriesCard(b, modes));
    else if (b.type === 'heatmap') nodes.push(heatmapCard(b));
    else if (b.type === 'table') nodes.push(el('div', { class: 'card' }, el('h3', {}, b.title), tableBox(b.columns, b.rows)));
  }
  return nodes;
}

const cell = (c) => (typeof c === 'string' && /^@\d{9,}$/.test(c) ? fmtTime(+c.slice(1)) : c);

function tableBox(columns, rows) {
  return el('div', { class: 'tablebox' }, el('table', {}, el('thead', {}, el('tr', {}, ...columns.map((c) => el('th', {}, c)))),
    el('tbody', {}, rows.length ? rows.map((r) => el('tr', {}, ...r.map((c) => el('td', {}, cell(c)))))
      : el('tr', {}, el('td', { colspan: columns.length, class: 'muted' }, 'Nothing yet.')))));
}

const COLORS = ['#e2b54b', '#4fa3f7', '#e5534b', '#4fbf7a', '#a98bf0', '#f0884f'];
const short = (v) => Math.abs(v) >= 1e9 ? (v / 1e9).toFixed(1) + 'B' : Math.abs(v) >= 1e6 ? (v / 1e6).toFixed(1) + 'M'
  : Math.abs(v) >= 1e4 ? (v / 1e3).toFixed(1) + 'k' : String(Math.round(v * 10) / 10);

function seriesCard(s, modes) {
  const key = s.title;
  const mode = modes[key] || 'chart';
  const body = el('div');
  const seg = el('div', { class: 'seg' });
  const draw = () => {
    seg.replaceChildren(...['chart', 'table'].map((m) => el('button', { class: modes[key] === m || (!modes[key] && m === 'chart') ? 'on' : '',
      on: { click: () => { modes[key] = m; draw(); } } }, m)));
    if ((modes[key] || 'chart') === 'chart') body.replaceChildren(...[legend(s), chart(s)].filter(Boolean));
    else body.replaceChildren(tableBox(['Time', ...(s.lines.length ? s.lines : [s.title])],
      seriesRows(s).reverse().map(([t, vals]) => [fmtTime(t), ...vals.map((v) => (v === undefined ? '' : short(v)))])));
  };
  modes[key] = mode;
  draw();
  return el('div', { class: 'card' }, el('div', { class: 'spread' },
    el('h3', {}, s.title, s.subtitle ? el('span', { class: 'sub' }, '  ' + s.subtitle) : null), seg), body);
}

/** [time, [value per line]] rows, one per distinct time. */
function seriesRows(s) {
  const map = new Map();
  for (const [li, t, v] of s.points) { if (!map.has(t)) map.set(t, []); map.get(t)[li] = v; }
  return [...map.entries()].sort((a, b) => a[0] - b[0]);
}

function legend(s) {
  if (s.lines.length < 2) return null;
  const last = [];
  for (const [li, , v] of s.points) last[li] = v;
  return el('div', { class: 'chips' }, ...s.lines.map((n, i) => {
    const dot = el('i'); dot.style.background = COLORS[i % COLORS.length];
    return el('span', { class: 'chip' }, dot, n, el('span', {}, last[i] === undefined ? '' : short(last[i])));
  }));
}

// A chart; with s.zoomable it gets + / - / fit buttons, scroll-to-zoom (after a click) and drag-to-pan
// on the time axis, plus the visible range in the corner.
function chart(s) {
  if (!s.zoomable || !s.points.length) return drawChart(s);
  let full = [Infinity, -Infinity];
  for (const [, t] of s.points) { full[0] = Math.min(full[0], t); full[1] = Math.max(full[1], t); }
  if (full[1] - full[0] < 60000) return drawChart(s);
  let win = full.slice(), armed = false;
  const box = el('div', { class: 'zoomchart' });
  const label = el('div', { class: 'zoom-range' });
  const clamp = (a, b) => {
    const span = Math.min(full[1] - full[0], Math.max(10 * 60000, b - a));   // never closer than 10 minutes
    a = Math.max(full[0], Math.min(a, full[1] - span));
    return [a, a + span];
  };
  const zoom = (f, at) => { const c = at ?? (win[0] + win[1]) / 2; win = clamp(c - (c - win[0]) * f, c + (win[1] - c) * f); draw(); };
  const fmt = (t) => new Date(t).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  const tools = el('div', { class: 'zoom-tools' },
    el('button', { on: { click: () => zoom(0.5) } }, '+'), el('button', { on: { click: () => zoom(2) } }, '−'),
    el('button', { on: { click: () => { win = full.slice(); draw(); } } }, 'fit'));
  let svg;
  function draw() {
    const next = drawChart(s, win);
    if (svg) svg.replaceWith(next); else box.append(next);
    svg = next;
    label.textContent = fmt(win[0]) + ' → ' + fmt(win[1]);
    const toTime = (clientX) => { const r = svg.getBoundingClientRect(); return win[0] + ((clientX - r.left) / r.width * 1000 - 44) / (1000 - 54) * (win[1] - win[0]); };
    svg.addEventListener('mousedown', (e) => {
      armed = true;
      const start = e.clientX, w0 = win.slice(), r = svg.getBoundingClientRect();
      const mv = (m) => { const dt = (m.clientX - start) / r.width * (w0[1] - w0[0]); win = clamp(w0[0] - dt, w0[1] - dt); draw(); };
      const up = () => { removeEventListener('mousemove', mv); removeEventListener('mouseup', up); };
      addEventListener('mousemove', mv); addEventListener('mouseup', up);
    });
    svg.addEventListener('wheel', (e) => { if (!armed) return; e.preventDefault(); zoom(e.deltaY < 0 ? 0.8 : 1.25, toTime(e.clientX)); }, { passive: false });
    svg.addEventListener('mouseleave', () => { armed = false; });
  }
  box.append(tools, label);
  draw();
  return box;
}

function drawChart(s, win) {
  // A zoom window shows only what's inside it.
  if (win) s = { ...s, points: s.points.filter((p) => p[1] >= win[0] && p[1] <= win[1]),
    gaps: (s.gaps || []).filter((g) => g[1] >= win[0] && g[0] <= win[1]) };
  const NS = 'http://www.w3.org/2000/svg';
  const W = 1000, H = 230, L = 44, R = 10, T = 10, B = 24;
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  svg.setAttribute('preserveAspectRatio', 'none');
  svg.setAttribute('class', 'chart');
  const mk = (tag, attrs, text, parent) => { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]);
    if (text !== undefined) e.textContent = text; (parent || svg).append(e); return e; };
  if (!s.points.length) { mk('text', { x: W / 2, y: H / 2, 'text-anchor': 'middle' }, 'No data yet - samples are taken every 30 seconds'); return svg; }
  let t0 = Infinity, t1 = -Infinity, v1 = 0;
  for (const [, t, v] of s.points) { t0 = Math.min(t0, t); t1 = Math.max(t1, t); v1 = Math.max(v1, v); }
  for (const [a, b] of s.gaps || []) { t0 = Math.min(t0, a); t1 = Math.max(t1, b); }
  if (win) { t0 = win[0]; t1 = win[1]; }
  if (t1 === t0) t1 = t0 + 1;
  v1 = v1 <= 0 ? 1 : v1 * 1.1;
  // Threshold lines (e.g. 150 / 250 ms) stay in view even when the data is far below them.
  for (const [v] of s.thresholds || []) v1 = Math.max(v1, v * 1.15);
  const x = (t) => L + (t - t0) / (t1 - t0) * (W - L - R);
  const y = (v) => T + (1 - v / v1) * (H - T - B);
  for (let i = 0; i <= 4; i++) {
    const v = v1 * i / 4;
    mk('line', { x1: L, x2: W - R, y1: y(v), y2: y(v), class: 'grid' });
    mk('text', { x: L - 6, y: y(v) + 3, 'text-anchor': 'end' }, short(v));
  }
  const span = t1 - t0;
  for (let i = 0; i <= 6; i++) {
    const t = t0 + span * i / 6, d = new Date(t);
    mk('text', { x: x(t), y: H - 6, 'text-anchor': i === 0 ? 'start' : i === 6 ? 'end' : 'middle' },
      span > 2 * 864e5 ? d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }));
  }
  for (const [v, kind] of s.thresholds || []) {
    if (kind === 'bad') mk('rect', { x: L, y: T, width: W - L - R, height: Math.max(0, y(v) - T), class: 'zone-bad' });
    mk('line', { x1: L, x2: W - R, y1: y(v), y2: y(v), class: 'threshold ' + kind });
    mk('text', { x: L - 6, y: y(v) + 3, 'text-anchor': 'end', class: 'threshold-label ' + kind }, String(v));
  }
  for (const [a, b] of s.gaps || []) mk('rect', { x: x(a), y: T, width: Math.max(2, x(b) - x(a)), height: H - T - B, class: 'gap' });
  const byLine = new Map();
  for (const [li, t, v] of s.points) { if (!byLine.has(li)) byLine.set(li, []); byLine.get(li).push([t, v]); }
  const defs = mk('defs', {});
  for (const [li, pts] of byLine) {
    pts.sort((a, b) => a[0] - b[0]);
    const color = COLORS[li % COLORS.length];
    const path = pts.map(([t, v]) => x(t).toFixed(1) + ',' + y(v).toFixed(1)).join(' ');
    if (s.area && li === 0) {
      const gid = 'fill' + Math.random().toString(36).slice(2);
      const g = mk('linearGradient', { id: gid, x1: 0, y1: 0, x2: 0, y2: 1 }, undefined, defs);
      mk('stop', { offset: 0, 'stop-color': color, 'stop-opacity': 0.35 }, undefined, g);
      mk('stop', { offset: 1, 'stop-color': color, 'stop-opacity': 0.03 }, undefined, g);
      mk('polygon', { points: x(pts[0][0]).toFixed(1) + ',' + y(0) + ' ' + path + ' ' + x(pts[pts.length - 1][0]).toFixed(1) + ',' + y(0), fill: `url(#${gid})` });
    }
    mk('polyline', { points: path, fill: 'none', stroke: color, 'stroke-width': 2, 'vector-effect': 'non-scaling-stroke', 'stroke-linejoin': 'round' });
  }
  for (const [t, v, kind] of s.marks || []) {
    if (t < t0 || t > t1) continue;
    const dot = mk('circle', { cx: x(t), cy: y(Math.max(0, v)), r: 5, class: 'mark ' + kind });
    dot.addEventListener('mousemove', (e) => { e.stopPropagation(); tip(e, fmtTime(t) + '\n' + (s.markText ? s.markText(t, kind) : kind)); });
  }
  // Hover: a dashed line and the values at that time.
  const rows = seriesRows(s);
  const cursor = mk('line', { y1: T, y2: H - B, class: 'cursor', visibility: 'hidden' });
  svg.addEventListener('mousemove', (e) => {
    const r = svg.getBoundingClientRect();
    const t = t0 + ((e.clientX - r.left) / r.width * W - L) / (W - L - R) * (t1 - t0);
    let best = rows[0];
    for (const row of rows) if (Math.abs(row[0] - t) < Math.abs(best[0] - t)) best = row;
    cursor.setAttribute('x1', x(best[0])); cursor.setAttribute('x2', x(best[0])); cursor.setAttribute('visibility', 'visible');
    const names = s.lines.length ? s.lines : [s.title];
    tip(e, fmtTime(best[0]) + '\n' + best[1].map((v, i) => (v === undefined ? '' : names[i] + ': ' + short(v) + (s.unit ? ' ' + s.unit : ''))).filter(Boolean).join('\n'));
  });
  svg.addEventListener('mouseleave', () => { cursor.setAttribute('visibility', 'hidden'); tip(null); });
  return svg;
}

function tip(e, text) {
  let t = $('tip');
  if (!e) { if (t) t.hidden = true; return; }
  if (!t) { t = el('div', { id: 'tip' }); document.body.append(t); }
  t.hidden = false;
  t.replaceChildren(...text.split('\n').map((l, i) => el('div', { class: i ? '' : 'muted' }, l)));
  t.style.left = Math.min(e.clientX + 14, innerWidth - t.offsetWidth - 8) + 'px';
  t.style.top = (e.clientY + 14) + 'px';
}

function barsCard(b) {
  const max = Math.max(1e-9, ...b.items.map((i) => i[2]));
  return el('div', { class: 'card' }, el('h3', {}, b.title), b.items.length ? b.items.map(([label, note, v, text]) => {
    const fill = el('div', { class: 'fill' }); fill.style.width = Math.max(1, v / max * 100) + '%';
    return el('div', { class: 'bar' }, el('div', { class: 'top' }, el('span', {}, label, note ? el('span', { class: 'n' }, note) : null),
      el('span', { class: 't' }, text)), el('div', { class: 'track' }, fill));
  }) : el('p', { class: 'muted' }, 'Nothing yet.'));
}

// A donut chart with a legend: share and value per slice, largest first.
const PIE_COLORS = ['#e2b54b', '#4fa3f7', '#e5534b', '#4fbf7a', '#a98bf0', '#f0884f', '#5cc8c8', '#d97ab8', '#9aa0a8'];
function pieCard(p) {
  const items = [...p.items].sort((a, b) => b[1] - a[1]);
  const total = items.reduce((t, i) => t + i[1], 0);
  if (!total) return el('div', { class: 'card' }, el('h3', {}, p.title), el('p', { class: 'muted' }, 'Nothing yet.'));
  // Past 8 slices the rest go into "Other", so the chart stays readable.
  const shown = items.length > 9 ? [...items.slice(0, 8), ['Other', items.slice(8).reduce((t, i) => t + i[1], 0)]] : items;
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '-60 -60 120 120');
  svg.setAttribute('class', 'pie');
  let a0 = -Math.PI / 2;
  shown.forEach(([label, v], i) => {
    const frac = v / total, a1 = a0 + frac * Math.PI * 2;
    const path = document.createElementNS(NS, 'path');
    const r = 50, ri = 30, big = frac > 0.5 ? 1 : 0;
    const pt = (rad, a) => (rad * Math.cos(a)).toFixed(2) + ' ' + (rad * Math.sin(a)).toFixed(2);
    path.setAttribute('d', frac >= 0.9999
      ? `M 0 -50 A 50 50 0 1 1 -0.01 -50 L -0.01 -30 A 30 30 0 1 0 0 -30 Z`
      : `M ${pt(r, a0)} A ${r} ${r} 0 ${big} 1 ${pt(r, a1)} L ${pt(ri, a1)} A ${ri} ${ri} 0 ${big} 0 ${pt(ri, a0)} Z`);
    path.setAttribute('fill', PIE_COLORS[i % PIE_COLORS.length]);
    path.addEventListener('mousemove', (e) => tip(e, label + '\n' + short(v) + (p.unit ? ' ' + p.unit : '') + ' · ' + Math.round(frac * 100) + '%'));
    path.addEventListener('mouseleave', () => tip(null));
    svg.append(path);
    a0 = a1;
  });
  const center = document.createElementNS(NS, 'text');
  center.setAttribute('text-anchor', 'middle'); center.setAttribute('y', 4); center.setAttribute('class', 'pie-total');
  center.textContent = short(total);
  svg.append(center);
  return el('div', { class: 'card' }, el('h3', {}, p.title), el('div', { class: 'pie-wrap' }, svg,
    el('div', { class: 'pie-legend' }, ...shown.map(([label, v], i) => {
      const dot = el('i'); dot.style.background = PIE_COLORS[i % PIE_COLORS.length];
      return el('div', {}, dot, el('span', { class: 'pl' }, label), el('span', { class: 'pv' }, short(v) + ' · ' + Math.round(v / total * 100) + '%'));
    }))));
}

// A top-down world heatmap: cells coloured by value on a log scale (dark -> gold -> red), so a busy
// spawn doesn't wash out quieter places. North is up (smaller Z). Pins mark spots like "Last seen here".
function heatmapCard(h) {
  if (!h.cells.length) return el('div', { class: 'card' }, el('h3', {}, h.title), el('p', { class: 'muted' }, 'Nothing recorded yet. Positions are noted once a minute while players are online; join and move around for a few minutes.'));
  const NS = 'http://www.w3.org/2000/svg';
  let x0 = Infinity, x1 = -Infinity, z0 = Infinity, z1 = -Infinity, max = 1;
  for (const [x, z, v] of h.cells) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z); max = Math.max(max, v); }
  for (const [, bx, bz] of h.pins) { x0 = Math.min(x0, Math.floor(bx / h.cell)); x1 = Math.max(x1, Math.floor(bx / h.cell)); z0 = Math.min(z0, Math.floor(bz / h.cell)); z1 = Math.max(z1, Math.floor(bz / h.cell)); }
  x0 -= 2; z0 -= 2; x1 += 3; z1 += 3;
  const w = x1 - x0, hgt = z1 - z0;
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', `${x0} ${z0} ${w} ${hgt}`);
  svg.setAttribute('class', 'heat');
  svg.style.aspectRatio = String(Math.max(0.4, Math.min(3, w / hgt)));
  const mk = (tag, attrs, text) => { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); if (text !== undefined) e.textContent = text; svg.append(e); return e; };
  mk('rect', { x: x0, y: z0, width: w, height: hgt, class: 'heat-bg' });
  // The real world map underneath (Hytale's own map images, stitched by the server), if known.
  let mapImg = null;
  if (h.world) {
    const C = 32, cx = (b) => Math.floor(b / C);
    let cx0 = cx(x0 * h.cell), cx1 = cx(x1 * h.cell), cz0 = cx(z0 * h.cell), cz1 = cx(z1 * h.cell);
    // The server draws at most 48 chunks per side: keep the middle of a bigger area.
    const clip = (a, b) => { if (b - a < 48) return [a, b]; const m = Math.floor((a + b) / 2); return [m - 23, m + 24]; };
    [cx0, cx1] = clip(cx0, cx1); [cz0, cz1] = clip(cz0, cz1);
    mapImg = mk('image', { x: cx0 * C / h.cell, y: cz0 * C / h.cell, width: (cx1 - cx0 + 1) * C / h.cell, height: (cz1 - cz0 + 1) * C / h.cell,
      href: '/api/worldmap?world=' + encodeURIComponent(h.world) + '&cx0=' + cx0 + '&cz0=' + cz0 + '&cx1=' + cx1 + '&cz1=' + cz1,
      preserveAspectRatio: 'none', class: 'heat-map' });
    svg.classList.add('with-map');
  }
  const colour = (v) => {
    const t = Math.log(1 + v) / Math.log(1 + max);           // 0..1
    const stops = [[0, [40, 46, 54]], [0.45, [143, 113, 48]], [0.75, [226, 181, 75]], [1, [229, 83, 75]]];
    let i = 1; while (i < stops.length - 1 && t > stops[i][0]) i++;
    const [ta, ca] = stops[i - 1], [tb, cb] = stops[i], f = (t - ta) / (tb - ta || 1);
    return 'rgb(' + ca.map((c, k) => Math.round(c + (cb[k] - c) * f)).join(',') + ')';
  };
  for (const [x, z, v] of h.cells) {
    const r = mk('rect', { x, y: z, width: 1.02, height: 1.02, fill: colour(v) });
    r.addEventListener('mousemove', (e) => tip(e, 'X ' + x * h.cell + '..' + (x * h.cell + h.cell - 1) + ', Z ' + z * h.cell + '..' + (z * h.cell + h.cell - 1)
      + '\n' + short(v) + ' ' + (h.unit || '')));
    r.addEventListener('mouseleave', () => tip(null));
  }
  const size = Math.max(w, hgt) / 60;
  for (const [label, bx, bz] of h.pins) {
    mk('circle', { cx: bx / h.cell, cy: bz / h.cell, r: size, class: 'heat-pin' });
    mk('text', { x: bx / h.cell + size * 1.6, y: bz / h.cell + size * 0.6, class: 'heat-label', 'font-size': size * 1.7 }, label);
  }
  const total = h.cells.reduce((t, c) => t + c[2], 0);
  return el('div', { class: 'card' },
    el('div', { class: 'spread' }, el('h3', {}, h.title), mapImg ? el('button', { on: { click: (e) => {
      const on = svg.classList.toggle('with-map'); mapImg.style.display = on ? '' : 'none'; e.target.textContent = on ? 'Map: on' : 'Map: off';
    } } }, 'Map: on') : null, el('span', { class: 'muted mono' },
      'X ' + x0 * h.cell + '..' + x1 * h.cell + ' · Z ' + z0 * h.cell + '..' + z1 * h.cell + ' · ' + h.cell + '-block squares · ' + short(total) + ' ' + (h.unit || ''))),
    svg,
    el('div', { class: 'heat-scale' }, el('span', {}, 'less'), el('i'), el('span', {}, 'more'), el('span', { class: 'muted' }, '  north is up')));
}

// Plugin sections on a profile (Hycon's own Activity, PrisonCore's Prison, ...), with a range picker.
function profileSections(uuid) {
  const out = el('div');
  let range = S.range || '30d';
  const seg = el('div', { class: 'seg' });
  const drawSeg = () => seg.replaceChildren(...RANGES.map(([r, t]) => el('button', { class: r === range ? 'on' : '',
    on: { click: () => { range = S.range = r; drawSeg(); load(); } } }, t)));
  drawSeg();
  async function load() {
    try {
      const d = await api('GET', '/api/profile-sections?player=' + encodeURIComponent(uuid) + '&range=' + range);
      out.replaceChildren(...d.sections.map((sec) => el('div', { class: 'section' },
        el('h2', {}, sec.title), sec.error ? el('p', { class: 'bad' }, sec.error) : blocks(sec.blocks, {}))));
    } catch (e) { out.replaceChildren(el('p', { class: 'bad' }, e.message)); }
  }
  load();
  return el('div', {}, el('div', { class: 'spread' }, el('span', { class: 'muted' }, 'Player stats'), seg), out);
}

function columnsCard(c) {
  const NS = 'http://www.w3.org/2000/svg';
  const W = 400, H = 170, B = 18, n = Math.max(1, c.items.length);
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  svg.setAttribute('preserveAspectRatio', 'none');
  svg.setAttribute('class', 'cols');
  const max = Math.max(1e-9, ...c.items.map((i) => i[1]));
  const w = W / n;
  c.items.forEach(([label, v], i) => {
    const h = Math.max(v > 0 ? 2 : 1, v / max * (H - B - 6));
    const r = document.createElementNS(NS, 'rect');
    r.setAttribute('x', i * w + w * 0.15); r.setAttribute('width', w * 0.7); r.setAttribute('y', H - B - h); r.setAttribute('height', h);
    r.addEventListener('mousemove', (e) => tip(e, label + '\n' + short(v)));
    r.addEventListener('mouseleave', () => tip(null));
    svg.append(r);
    if (i % Math.ceil(n / 5) === 0 || i === n - 1) {
      const t = document.createElementNS(NS, 'text');
      t.setAttribute('x', i * w + w / 2); t.setAttribute('y', H - 4); t.setAttribute('text-anchor', 'middle'); t.textContent = label;
      svg.append(t);
    }
  });
  return el('div', { class: 'card' }, el('h3', {}, c.title), svg);
}

boot().catch((e) => { $('view').replaceChildren(el('p', { class: 'bad' }, 'Could not reach the server: ' + e.message)); });
