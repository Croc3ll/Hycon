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
      const r = await api('POST', '/api/login', { name: name.value, password: pw.value });
      S.csrf = r.csrf;
      await boot();
    } catch (err) { msg.textContent = err.message; pw.value = ''; }
  } } }, el('h2', {}, 'Log in'),
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
    el('span', {}, S.me.name + ' · ' + S.me.level),
    el('button', { on: { click: () => showPassword(false) } }, 'Password'),
    el('button', { on: { click: async () => { await api('POST', '/api/logout'); await boot(); } } }, 'Log out'));
}

function tabs() {
  const list = [['players', 'Players', 'VIEWER'], ['bans', 'Bans', 'VIEWER'], ['console', 'Console', 'MODERATOR']];
  for (const p of S.panels) list.push(['panel:' + p.id, p.title, 'VIEWER']);
  list.push(['audit', 'Audit log', 'ADMIN'], ['accounts', 'Accounts', 'OWNER']);
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
        el('td', { class: p.online ? 'good' : 'muted', title: p.last ? fmtTime(p.last) : '' }, p.online ? 'Online' : 'Last seen ' + ago(p.last)),
        el('td', { class: 'mono' }, p.uuid),
        el('td', { class: 'muted' }, p.first ? fmtTime(p.first) : ''),
        el('td', { class: 'actions' }, can('MODERATOR') ? [
          p.online ? el('button', { on: { click: () => modDialog('kick', p.name) } }, 'Kick') : null,
          el('button', { class: 'danger', on: { click: () => modDialog('ban', p.uuid) } }, 'Ban')] : null))));
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
    action === 'ban' ? [el('label', {}, 'How long'), dur] : null,
    el('p', { class: 'row' }, go, el('button', { on: { click: () => dlg.close() } }, 'Cancel')), msg);
  dlg.addEventListener('close', () => dlg.remove());
  document.body.append(dlg);
  dlg.showModal();
  reason.focus();
}

function bans(view) {
  const body = el('tbody');
  view.append(el('div', { class: 'card' }, el('h2', {}, 'Bans'),
    el('table', {}, el('thead', {}, el('tr', {}, el('th', {}, 'Player'), el('th', {}, 'Reason'), el('th', {}, 'By'), el('th', {}, 'Since'), el('th', {}, 'Ends'), el('th', {}))), body)));
  (async () => {
    try {
      const d = await api('GET', '/api/bans');
      body.replaceChildren(...d.bans.map((b) => el('tr', {}, el('td', {}, b.name || el('span', { class: 'mono' }, b.uuid)), el('td', {}, b.reason || ''),
        el('td', {}, b.by), el('td', {}, fmtTime(b.since)), el('td', {}, b.expires ? fmtTime(b.expires) : 'never'),
        el('td', { class: 'actions' }, can('MODERATOR') ? el('button', { on: { click: async () => {
          if (!confirm('Unban ' + (b.name || b.uuid) + '?')) return;
          try { toast((await api('POST', '/api/unban', { player: b.uuid })).message); render(); } catch (e) { toast(e.message, true); }
        } } }, 'Unban') : null))));
      if (!d.bans.length) body.replaceChildren(el('tr', {}, el('td', { colspan: 6, class: 'muted' }, 'No active bans.')));
    } catch (e) { toast(e.message, true); }
  })();
}

// ---------- Player profile ("dossier") ----------

function nameLink(name, uuid) {
  return el('a', { href: '#', on: { click: (e) => { e.preventDefault(); S.tab = 'profile:' + uuid; render(); } } }, name);
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
        el('div', { class: 'row' }, el('h2', {}, p.name),
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

// ---------- Live console ----------

function consoleView(view) {
  const box = el('div', { id: 'console', class: 'mono' });
  const input = el('input', { class: 'mono', placeholder: 'Command, e.g. who  (runs with full console rights)', autocomplete: 'off' });
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
    } } }, input, el('button', { class: 'primary', type: 'submit' }, 'Run')) : el('p', { class: 'muted' }, 'Running commands needs the ADMIN level.')));
  input.addEventListener('keydown', (e) => {
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

// ---------- Plugin dashboards (HyconPanel API) ----------

function dashboard(view, id) {
  let range = '6h';
  const out = el('div');
  const picker = el('div', { class: 'row' }, ...['1h', '6h', '24h', '7d', '30d'].map((r) =>
    el('button', { class: r === range ? 'primary' : '', on: { click: (e) => { range = r; [...picker.children].forEach((b) => { b.className = ''; }); e.target.className = 'primary'; load(); } } }, r)));
  view.append(el('div', { class: 'row' }, el('h2', { id: 'dash-title' }, ''), picker), out);
  async function load() {
    try {
      const d = await api('GET', '/api/panel?id=' + encodeURIComponent(id) + '&range=' + range);
      $('dash-title').textContent = d.title;
      out.replaceChildren(...blocks(d.blocks));
    } catch (e) { out.replaceChildren(el('p', { class: 'bad' }, e.message)); }
  }
  every(10000, load);
}

function blocks(list) {
  const nodes = []; let tiles = null;
  for (const b of list) {
    if (b.type === 'stat') {
      if (!tiles) { tiles = el('div', { class: 'tiles' }); nodes.push(tiles); }
      tiles.append(el('div', { class: 'tile' }, el('div', { class: 'h' }, b.label), el('div', { class: 'v' }, b.value), b.hint ? el('div', { class: 'h' }, b.hint) : null));
      continue;
    }
    tiles = null;
    if (b.type === 'note') nodes.push(el('p', { class: 'muted' }, b.text));
    else if (b.type === 'series') nodes.push(el('div', { class: 'card' }, el('h3', {}, b.title + (b.unit ? ' (' + b.unit + ')' : '')), chart(b)));
    else if (b.type === 'table') nodes.push(el('div', { class: 'card' }, el('h3', {}, b.title),
      el('table', {}, el('thead', {}, el('tr', {}, ...b.columns.map((c) => el('th', {}, c)))), el('tbody', {}, ...b.rows.map((r) => el('tr', {}, ...r.map((c) => el('td', {}, c))))))));
  }
  return nodes;
}

const COLORS = ['#2f6fde', '#e0803a', '#2f9e68', '#c2457a', '#8a63d2', '#c9a227'];

function chart(s) {
  const NS = 'http://www.w3.org/2000/svg';
  const W = 800, H = 200, L = 52, B = 22;
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  svg.setAttribute('class', 'chart');
  const mk = (tag, attrs, text) => { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); if (text !== undefined) e.textContent = text; svg.append(e); return e; };
  if (!s.points.length) { mk('text', { x: W / 2, y: H / 2, 'text-anchor': 'middle' }, 'No data yet'); return svg; }
  let t0 = Infinity, t1 = -Infinity, v0 = Infinity, v1 = -Infinity;
  for (const [, t, v] of s.points) { t0 = Math.min(t0, t); t1 = Math.max(t1, t); v0 = Math.min(v0, v); v1 = Math.max(v1, v); }
  if (v0 > 0 && v0 < v1 * 0.5) v0 = 0;
  if (v1 === v0) { v1 += 1; v0 = Math.max(0, v0 - 1); }
  if (t1 === t0) t1 = t0 + 1;
  const x = (t) => L + (t - t0) / (t1 - t0) * (W - L - 8);
  const y = (v) => 6 + (1 - (v - v0) / (v1 - v0)) * (H - B - 6);
  const short = (v) => Math.abs(v) >= 1e9 ? (v / 1e9).toFixed(1) + 'B' : Math.abs(v) >= 1e6 ? (v / 1e6).toFixed(1) + 'M' : Math.abs(v) >= 1e3 ? (v / 1e3).toFixed(1) + 'k' : String(Math.round(v * 100) / 100);
  for (let i = 0; i <= 4; i++) {
    const v = v0 + (v1 - v0) * i / 4;
    mk('line', { x1: L, x2: W - 8, y1: y(v), y2: y(v), class: 'grid' });
    mk('text', { x: L - 6, y: y(v) + 4, 'text-anchor': 'end' }, short(v));
  }
  const span = t1 - t0;
  for (let i = 0; i <= 4; i++) {
    const t = t0 + span * i / 4, d = new Date(t);
    mk('text', { x: x(t), y: H - 6, 'text-anchor': i === 0 ? 'start' : i === 4 ? 'end' : 'middle' },
      span > 2 * 864e5 ? d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }));
  }
  const byLine = new Map();
  for (const [li, t, v] of s.points) { if (!byLine.has(li)) byLine.set(li, []); byLine.get(li).push([t, v]); }
  for (const [li, pts] of byLine) {
    pts.sort((a, b) => a[0] - b[0]);
    mk('polyline', { points: pts.map(([t, v]) => x(t).toFixed(1) + ',' + y(v).toFixed(1)).join(' '), fill: 'none', stroke: COLORS[li % COLORS.length], 'stroke-width': 2 });
  }
  if (s.lines.length < 2) return svg;
  const wrap = el('div', {}, svg);
  wrap.append(el('div', { class: 'legend' }, ...s.lines.map((n, i) => { const sw = el('i'); sw.style.background = COLORS[i % COLORS.length]; return el('span', {}, sw, n); })));
  return wrap;
}

boot().catch((e) => { $('view').replaceChildren(el('p', { class: 'bad' }, 'Could not reach the server: ' + e.message)); });
