// Demo mode for promo shots: answers the panel's /api calls with a made-up busy server, so the
// art shows every feature without real player names or addresses. Loaded before app.js by
// tools/promo/make_promo.py; never shipped in the jar.
(() => {
  const NOW = Date.now(), MIN = 60000, HOUR = 60 * MIN, DAY = 24 * HOUR;
  let seed = 7;
  const rnd = () => { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646; };
  const uuid = (i) => ('0000000' + (i * 2654435761 >>> 0).toString(16)).slice(-8) + '-4c1a-4b7e-9d2f-' + ('00000000000' + (i * 40503 * 977).toString(16)).slice(-12);

  const NAMES = ['Kaelith', 'BrickByte', 'Morrowind_Max', 'Sylvie', 'Thornwick', 'PixelPip', 'Ravenmoor', 'Juniper', 'Oakhart',
    'Lumen', 'Grimsby', 'NovaFox', 'Tidecaller', 'Bramble', 'Quillon', 'Ashgrove', 'Wren', 'Cobalt_Kid', 'Mistral', 'Fennick',
    'Hollowbark', 'Saffron', 'Ironvale', 'Pebble', 'Vesper', 'Driftwood', 'Emberly', 'Corvid', 'Marrow', 'Tansy'];
  const WORLDS = ['skyworld', 'skyworld', 'skyworld', 'hub', 'hideouts', 'arena', 'mines'];
  const PLACES = [['Toronto', 'Ontario', 'Canada', 'CA', 43.65, -79.38, 18], ['New York', 'New York', 'United States', 'US', 40.71, -74.0, 24],
    ['Chicago', 'Illinois', 'United States', 'US', 41.88, -87.63, 31], ['Dallas', 'Texas', 'United States', 'US', 32.78, -96.8, 46],
    ['Los Angeles', 'California', 'United States', 'US', 34.05, -118.24, 72], ['London', 'England', 'United Kingdom', 'GB', 51.51, -0.13, 88],
    ['Berlin', 'Berlin', 'Germany', 'DE', 52.52, 13.4, 102], ['Paris', 'Ile-de-France', 'France', 'FR', 48.86, 2.35, 96],
    ['Stockholm', 'Stockholm', 'Sweden', 'SE', 59.33, 18.07, 118], ['Sao Paulo', 'Sao Paulo', 'Brazil', 'BR', -23.55, -46.63, 142],
    ['Sydney', 'New South Wales', 'Australia', 'AU', -33.87, 151.21, 212], ['Warsaw', 'Mazovia', 'Poland', 'PL', 52.23, 21.01, 110],
    ['Vancouver', 'British Columbia', 'Canada', 'CA', 49.28, -123.12, 64], ['Miami', 'Florida', 'United States', 'US', 25.76, -80.19, 38],
    ['Manila', 'Metro Manila', 'Philippines', 'PH', 14.6, 120.98, 238], ['Mexico City', 'CDMX', 'Mexico', 'MX', 19.43, -99.13, 61]];

  const players = NAMES.map((name, i) => {
    const place = PLACES[i % PLACES.length];
    return { name, uuid: uuid(i + 1), online: i < 18, world: WORLDS[i % WORLDS.length], first: NOW - (40 - i) * DAY - i * 3 * HOUR,
      last: i < 18 ? NOW : NOW - (i - 17) * 5 * HOUR, place, ping: Math.round(place[6] * (0.85 + rnd() * 0.3)) };
  });
  const hero = players[0];

  // Players online through the day: quiet at night, busiest in the evening.
  const onlineAt = (t) => {
    const h = new Date(t).getHours() + new Date(t).getMinutes() / 60;
    return Math.max(2, Math.round(26 + 18 * Math.sin((h - 13) / 24 * Math.PI * 2) + 3 * Math.sin(t / (37 * MIN)) + 2 * Math.sin(t / (11 * MIN))));
  };
  const series = (span, step, fn) => { const out = []; for (let t = NOW - span; t <= NOW; t += step) out.push([0, t, fn(t)]); return out; };

  const LOG = [
    ['[INFO]', '[HytaleServer]', 'Hytale Server Booted! [Multiplayer] took 14sec 220ms'],
    ['[INFO]', '[PrisonCore|P]', 'Loaded 12 mines, 48 ranks and 6 crates'],
    ['[INFO]', '[Hycon]', 'Panel ready on port 8096'],
    ['[INFO]', '[World|skyworld]', 'Player Kaelith joined (ping 18 ms)'],
    ['[INFO]', '[PrisonCore|P]', 'Kaelith ranked up to J'],
    ['[INFO]', '[World|hub]', 'Player NovaFox joined (ping 64 ms)'],
    ['[WARN]', '[PrisonCore|P]', 'Economy Guard: Grimsby earned x6.2 the rank average in the last hour'],
    ['[INFO]', '[Arena]', 'Ranked 2v2 started: Thornwick, Sylvie vs Bramble, Wren'],
    ['[INFO]', '[PrisonCore|P]', 'Crate opened by PixelPip: Legendary Key'],
    ['[INFO]', '[Hycon]', 'admin ran: broadcast Double sell weekend starts now!'],
    ['[INFO]', '[World|skyworld]', 'Player Ravenmoor joined (ping 88 ms)'],
    ['[SEVERE]', '[ChunkSaver]', 'Slow save: region -2,4 took 2140 ms'],
    ['[INFO]', '[Arena]', 'Ranked 2v2 won by Thornwick, Sylvie (+24 rating)'],
    ['[INFO]', '[PrisonCore|P]', 'Mine B reset (2.1M blocks)'],
    ['[INFO]', '[World|hideouts]', 'Gang Ironvale upgraded their Smelter to level 4'],
    ['[WARN]', '[Hycon]', 'Report: Corvid reported Marrow for "fly hacking"'],
    ['[INFO]', '[World|skyworld]', 'Player Tidecaller joined (ping 46 ms)'],
    ['[INFO]', '[PrisonCore|P]', 'Juniper prestiged to P3'],
  ];
  const logLines = LOG.map(([lvl, src, msg], i) => {
    const d = new Date(NOW - (LOG.length - i) * 47000);
    const time = [d.getHours(), d.getMinutes(), d.getSeconds()].map((n) => String(n).padStart(2, '0')).join(':');
    return [i, time + ' ' + lvl + ' ' + src + ' ' + msg];
  });

  const stat = (label, value, hint) => ({ type: 'stat', label, value, hint: hint || null });
  const fmtH = (ms) => Math.floor(ms / HOUR) + 'h ' + Math.floor(ms % HOUR / MIN) + 'm';

  const analytics = () => {
    const gaps = [[NOW - 9 * HOUR - 6 * MIN, NOW - 9 * HOUR + 4 * MIN]];
    const top = players.slice(0, 8).map((p, i) => [p.name, (420 - i * 37) + ' sessions', (96 - i * 9) * HOUR, fmtH((96 - i * 9) * HOUR)]);
    const heat = []; for (let x = -14; x <= 14; x++) for (let z = -14; z <= 14; z++) {
      const d = Math.hypot(x - 2, z + 1), d2 = Math.hypot(x + 7, z - 6);
      const v = 60 * Math.exp(-d * d / 30) + 28 * Math.exp(-d2 * d2 / 12) + (rnd() < 0.25 ? rnd() * 3 : 0);
      if (v > 0.4) heat.push([x, z, Math.round(v * 4) / 4]);
    }
    return { title: 'Analytics', blocks: [
      stat('Online now', '18'), stat('Unique players', '412'), stat('Peak', '47'), stat('Average', '29'),
      stat('Uptime', '99.6%', 'running 6d 4h'), stat('Memory', '3,184 MB', 'of 8192 MB'),
      stat('New players', '37', 'first time in this period'), stat('Returning', '375', 'played here before'),
      stat('Came back', '68%', 'played again on a later day (last 60 days)'),
      { type: 'series', title: 'Players online', subtitle: 'most online per 5 min · red bands are outages', unit: 'players', area: true,
        lines: ['Players'], gaps, points: series(DAY, 5 * MIN, onlineAt) },
      { type: 'series', title: 'Memory used', subtitle: 'average per 5 min', unit: 'MB', area: false, lines: ['Memory'], gaps: [],
        points: series(DAY, 5 * MIN, (t) => Math.round(2600 + 500 * Math.sin(t / (3 * HOUR)) + 60 * Math.sin(t / (17 * MIN)))) },
      { type: 'columns', title: 'Most players by hour', items: Array.from({ length: 24 }, (_, h) => [String(h).padStart(2, '0'),
        Math.round(26 + 18 * Math.sin((h - 13) / 24 * Math.PI * 2) + 4)]) },
      { type: 'bars', title: 'Most playtime', items: top },
      { type: 'table', title: 'Most active players', columns: ['Player', 'Playtime', 'Sessions', 'First seen', 'Last seen'],
        rows: players.slice(0, 6).map((p, i) => [p.name, fmtH((96 - i * 9) * HOUR), String(420 - i * 37), '@' + p.first, p.online ? 'online now' : '@' + p.last]) },
      { type: 'heatmap', title: 'Where players spend time - skyworld', world: 'skyworld', unit: 'player-min', cell: 16, cells: heat, marks: [], path: [],
        pins: players.slice(0, 5).map((p, i) => [p.name, 40 - i * 60, -20 + i * 45]) },
    ] };
  };

  const geoOf = (p) => ({ name: p.name, uuid: p.uuid, online: p.online, ping: p.ping, city: p.place[0], region: p.place[1],
    country: p.place[2], cc: p.place[3], lat: p.place[4] + (rnd() - 0.5), lon: p.place[5] + (rnd() - 0.5) });
  const SERVER = { lat: 43.65, lon: -79.38 };

  const profile = (p) => ({ name: p.name, uuid: p.uuid, online: p.online, world: p.world, first: p.first, last: p.last,
    warnings: 1, kicks: 0, bans: 0, ban: null,
    risk: { score: 12, level: 'Low', reasons: [{ points: 12, what: 'Fast rank-ups', detail: '3 ranks in an hour, once' }] },
    history: [{ time: NOW - 6 * DAY, type: 'NOTE', by: 'admin', reason: 'Helped new players at spawn all evening', detail: '' },
      { time: NOW - 19 * DAY, type: 'WARN', by: 'Mod_Sage', reason: 'Spamming trade chat', detail: '' }] });

  const sections = () => ({ sections: [
    { id: 'activity', title: 'Activity', blocks: [stat('Playtime', '96h 12m', 'since Hycon was installed'), stat('Sessions', '420', 'avg 14 min'),
      stat('Days played', '27 of 30', 'last 30 days'), stat('PvP (14 days)', '41 / 12', 'kills / deaths'), stat('Warnings', '1'), stat('Reported', '0x'),
      { type: 'columns', title: 'Minutes online per day (last 14 days)', items: Array.from({ length: 14 }, (_, i) => {
        const d = new Date(NOW - (13 - i) * DAY); return [d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }), Math.round(120 + rnd() * 260)]; }) },
      { type: 'pie', title: 'Where they play', items: [['skyworld', 61], ['hideouts', 18], ['arena', 12], ['hub', 9]] }] },
    { id: 'prison', title: 'Prison', blocks: [stat('Money', '$4,812,350'), stat('Tokens', '182,440'), stat('Rank', 'J', 'prestige 3'),
      stat('Next rank', '$5,200,000'), stat('Blocks mined', '1.24M'), stat('Arena rating', '1,412', '2v2 ranked')] },
  ] });

  const pingData = () => {
    const points = []; for (let t = NOW - 14 * DAY; t <= NOW; t += 10 * MIN) points.push([t, Math.round(16 + rnd() * 8 + (rnd() < 0.01 ? 120 : 0))]);
    return { days: 14, samples: points.length, kills: [], killCount: 0, highPingKills: 0, spikeKills: 0, lagSuspect: false, median: 18, average: 20,
      p95: 27, worst: 141, worstAt: NOW - 3 * DAY, best: 14, jitter: 3, over150: 0, over250: 0, spikes: 2, steady: true, points };
  };

  const graph = (id) => {
    const p = players.find((x) => x.uuid === id) || hero;
    const ring = players.slice(1, 8);
    return { nodes: [{ id: p.uuid, name: p.name, level: 0, online: p.online }, ...ring.map((q, i) => ({ id: q.uuid, name: q.name, level: 1,
      online: q.online, reported: i === 4 }))],
      edges: ring.map((q, i) => ({ type: ['cojoin', 'cojoin', 'kill', 'cojoin', 'report', 'same', 'kill'][i], a: p.uuid, b: q.uuid, weight: 1 + i % 3 })) };
  };

  const ROUTES = {
    '/api/session': () => ({ loggedIn: true, name: 'admin', level: 'OWNER', rank: 4, mustChange: false, csrf: 'demo' }),
    '/api/panels': () => ({ panels: [{ id: 'analytics', title: 'Analytics' }, { id: 'arena', title: 'Arena' }, { id: 'guard', title: 'Economy Guard' },
      { id: 'perms', title: 'Permissions' }, { id: 'prison', title: 'Prison' }] }),
    '/api/reports': () => ({ open: 2, reports: [] }),
    '/api/bugs': () => ({ open: 1, bugs: [] }),
    '/api/players': () => ({ players: players.filter((p) => p.online).map((p) => ({ name: p.name, uuid: p.uuid, world: p.world })) }),
    '/api/directory': () => ({ total: players.length, players: players.map((p) => ({ name: p.name, uuid: p.uuid, online: p.online, world: p.world,
      first: p.first, last: p.last })) }),
    '/api/status': () => ({ amp: false, tps: 30, targetTps: 30, memoryMb: 3184, memoryMaxMb: 8192, online: 18, uptimeMs: 6 * DAY + 4 * HOUR }),
    '/api/log': (q) => (q.get('after') === '-1' ? { lines: logLines, latest: logLines.length - 1 } : { lines: [], latest: logLines.length - 1 }),
    '/api/profile': (q) => profile(players.find((p) => p.uuid === q.get('player')) || hero),
    '/api/profile-sections': sections,
    '/api/ping': pingData,
    '/api/geo': () => ({ ready: true, server: SERVER, players: players.map(geoOf) }),
    '/api/geo/player': (q) => ({ ready: true, server: SERVER, player: geoOf(players.find((p) => p.uuid === q.get('player')) || hero) }),
    '/api/graph': (q) => graph(q.get('player')),
    '/api/panel': (q) => (q.get('id') === 'analytics' ? analytics() : { title: q.get('id'), blocks: [] }),
    '/api/bans': () => ({ bans: [] }),
  };

  const real = window.fetch.bind(window);
  window.fetch = (input, opt) => {
    const url = new URL(typeof input === 'string' ? input : input.url, location.href);
    const route = ROUTES[url.pathname];
    if (!url.pathname.startsWith('/api/')) return real(input, opt);
    const body = route ? JSON.stringify(route(url.searchParams)) : '{}';
    // Answer a moment later, like a real server: app.js boots before the deferred globe.js has run.
    return new Promise((done) => setTimeout(() => done(new Response(body, { status: 200, headers: { 'Content-Type': 'application/json' } })), 40));
  };

  // Headless Edge draws a scrolled page as a blank strip, so shots never scroll: ?shot=<selector>
  // hides everything above that element instead, and any scroll (the console focuses its input) is undone.
  const shot = new URLSearchParams(location.search).get('shot');
  setTimeout(() => {
    const target = shot && document.querySelector(shot);
    // Walk up to #view, hiding whatever comes before the target at each level.
    for (let part = target; part && part.id !== 'view'; part = part.parentElement) {
      for (let prev = part.previousElementSibling; prev; prev = prev.previousElementSibling) prev.style.display = 'none';
    }
    document.activeElement?.blur();
    scrollTo(0, 0);
  }, 3500);
})();
