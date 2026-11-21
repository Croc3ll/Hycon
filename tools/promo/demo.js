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

  // A few players stand out on the Players table (the rest get low scores): Grimsby is the Economy Guard case.
  const RISK = { 0: 12, 6: 34, 10: 62, 11: 24, 16: 88 };
  const players = NAMES.map((name, i) => {
    const place = PLACES[i % PLACES.length];
    return { name, uuid: uuid(i + 1), online: i < 18, world: WORLDS[i % WORLDS.length], first: NOW - (40 - i) * DAY - i * 3 * HOUR,
      last: i < 18 ? NOW : NOW - (i - 17) * 5 * HOUR, place, ping: Math.round(place[6] * (0.85 + rnd() * 0.3)),
      since: NOW - (6 + (i * 37) % 170) * MIN, playtimeMs: Math.round((140 - i * 4.3) * HOUR + (i * 17 % 60) * MIN), risk: RISK[i] ?? (i * 7) % 18 };
  });
  const PRESETS = [['Spam', ['warn', 'mute 30m', 'mute 1d', 'ban 1d']], ['Toxicity', ['warn', 'mute 1h', 'mute 1d', 'ban 3d', 'ban 30d']],
    ['Advertising', ['mute 1d', 'ban 7d', 'ban permanent']], ['Griefing', ['warn', 'ban 1d', 'ban 7d', 'ban permanent']],
    ['Hacking', ['ban 30d', 'ban permanent']]];
  const riskLevel = (r) => (r >= 80 ? 'Very high' : r >= 50 ? 'High' : r >= 20 ? 'Medium' : 'Low');
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

  // The Performance tab of a healthy, busy prison server (made-up numbers in the shape Hycon sends).
  const performance = () => {
    const bar = (name, pct) => [name, Math.round(pct / 0.31) + '% of the busy time', pct, pct.toFixed(1) + '%'];
    const health = series(DAY, 10 * MIN, (t) => Math.round(Math.min(100, 91 + 4 * Math.sin(t / (5 * HOUR)) - (Math.abs(t - (NOW - 7 * HOUR)) < 25 * MIN ? 24 : 0))));
    const week = series(7 * DAY, HOUR, (t) => Math.round(Math.min(100, 89 + 5 * Math.sin(t / (11 * HOUR)) + 2 * Math.sin(t / (3 * HOUR)))));
    return { title: 'Performance', blocks: [
      { type: 'note', text: 'Healthy: ticks take 4.2 ms on average (budget 33 ms), 3 lag spikes. PrisonCore uses the most world-thread time (9%).' },
      stat('Server health', '92 / 100', 'Excellent: in good standing at 75 and up. Points lost for: worst tick 182 ms (-4), 0.1 lag spikes a minute (-1).'),
      stat('Tick time', '4.2 ms', 'Good: under 20 ms is good; over 33 ms the game slows down.'),
      stat('Worst tick', '182 ms', 'Watch: under 100 ms nobody notices.'),
      stat('Lag spikes', '3', 'Good: a few a day is normal.'),
      stat('World threads busy', '31%', 'Good: under 50% leaves room for more players.'),
      stat('Memory', '3,184 MB', 'Good: 39% of 8192 MB. Longest GC pause 41 ms.'),
      { type: 'series', title: 'Server health', subtitle: '75 and up is good standing; per 10 min', unit: '/ 100', area: true, lines: ['Health'], gaps: [], points: health },
      { type: 'bars', title: 'Who uses the world threads', items: [bar('PrisonCore', 9.4), bar('Hytale', 8.1), bar('Hycon - AntiCheat', 1.2),
        bar('HyWorlds', 0.6), bar('Hycon', 0.3), bar('Tebex', 0.1)] },
      { type: 'table', title: 'Lag spikes (newest first)', columns: ['When', 'Where', 'Length', 'Cause', 'System or task', 'Also', 'Players there'],
        rows: [['@' + (NOW - 42 * MIN), 'skyworld', '182 ms', 'PrisonCore', 'MineResetSystem', 'chunk load', 'Kiwi_Rush, Ember, +6 more'],
          ['@' + (NOW - 3 * HOUR), 'hideouts', '131 ms', 'Hytale', 'ChunkSavingSystem', 'save', 'Nova_Blade, Oakheart'],
          ['@' + (NOW - 7 * HOUR), 'Scheduler', '118 ms', 'garbage collection', 'Autosave.run', '', 'everyone (21)']] },
      { type: 'series', title: 'Server health, last 7 days', subtitle: 'hourly average', unit: '/ 100', area: false, lines: ['Health'], gaps: [], points: week },
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
    '/api/panels': () => ({ panels: [{ id: 'analytics', title: 'Analytics' }, { id: 'arena', title: 'Arena', icon: 'swords' },
      { id: 'guard', title: 'Economy Guard', icon: 'coins' }, { id: 'performance', title: 'Performance' },
      { id: 'perms', title: 'Permissions', icon: 'key' }, { id: 'prison', title: 'Prison', icon: 'pickaxe' }] }),
    '/api/reports': () => ({ open: 2, reports: [] }),
    '/api/schedule': () => ({ amp: true, blocked: false, zone: 'America/Toronto', next: NOW + 5 * HOUR + 12 * MIN, oneOff: 0,
      restartTimes: ['04:00', '16:00'], warnMinutes: [15, 5, 1],
      announcements: [{ text: 'Vote for us with /vote and get a crate key!', everyMinutes: 30 }, { text: 'Join our Discord: discord.gg/example', everyMinutes: 45 }] }),
    '/api/security': () => ({ checks: [
      { level: 'warn', title: 'Admins without two-factor: Juniper', detail: 'These accounts can run console commands. Each person turns two-factor on under My account (click your name).' },
      { level: 'info', title: 'No console commands are blocked', detail: 'ADMIN accounts can run any command. List ones they never need in BlockedCommands in hycon.json, e.g. ["stop", "op"].' },
      { level: 'ok', title: 'Reached over HTTPS', detail: 'Logins only travel encrypted (SecureCookie is on).' }] }),
    '/api/mutes': () => ({ mutes: [{ player: players[16].uuid, name: players[16].name, by: 'admin', reason: '[Toxicity] insults in chat',
      since: NOW - 2 * HOUR, until: NOW + 22 * HOUR }] }),
    '/api/presets': () => ({ text: PRESETS.map(([n, steps]) => n + ': ' + steps.join(', ')).join('\n'),
      presets: PRESETS.map(([name, steps], i) => ({ name, steps: steps.map((st) => { const [action, duration] = st.split(' '); return { action, duration }; }),
        done: i % 3, next: Math.min(i % 3, steps.length - 1) })) }),
    '/api/bugs': () => ({ open: 1, bugs: [] }),
    '/api/players': () => ({ players: players.filter((p) => p.online).map((p) => ({ name: p.name, uuid: p.uuid, world: p.world,
      since: p.since, playtimeMs: p.playtimeMs, ping: p.ping, risk: p.risk, riskLevel: riskLevel(p.risk), muted: p === players[16] || undefined })) }),
    '/api/directory': () => ({ total: players.length, players: players.map((p) => ({ name: p.name, uuid: p.uuid, online: p.online, world: p.world,
      first: p.first, last: p.last, playtimeMs: p.playtimeMs, banned: p.name === 'Marrow' || undefined })) }),
    '/api/status': () => ({ amp: false, tps: 30, targetTps: 30, memoryMb: 3184, memoryMaxMb: 8192, online: 18, uptimeMs: 6 * DAY + 4 * HOUR }),
    '/api/log': (q) => (q.get('after') === '-1' ? { lines: logLines, latest: logLines.length - 1 } : { lines: [], latest: logLines.length - 1 }),
    '/api/profile': (q) => profile(players.find((p) => p.uuid === q.get('player')) || hero),
    '/api/profile-sections': sections,
    '/api/ping': pingData,
    '/api/geo': () => ({ ready: true, server: SERVER, players: players.map(geoOf) }),
    '/api/geo/player': (q) => ({ ready: true, server: SERVER, player: geoOf(players.find((p) => p.uuid === q.get('player')) || hero) }),
    '/api/graph': (q) => graph(q.get('player')),
    '/api/panel': (q) => (q.get('id') === 'analytics' ? analytics() : q.get('id') === 'performance' ? performance() : { title: q.get('id'), blocks: [] }),
    '/api/bans': () => ({ bans: [] }),
  };

  // POSTs that must answer with data for the page to go on; every other POST changes nothing.
  const POST_ROUTES = {
    '/api/2fa/start': () => ({ secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP', uri: 'otpauth://totp/Hycon:admin?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Hycon',
      // The real server draws this (QrCode.svgDataUri); here it's the QR of the uri above, drawn the same way.
      qr: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyMjUiIGhlaWdodD0iMjI1IiB2aWV3Qm94PSIwIDAgNDUgNDUiIHNoYXBlLXJlbmRlcmluZz0iY3Jpc3BFZGdlcyI+PHJlY3Qgd2lkdGg9IjQ1IiBoZWlnaHQ9IjQ1IiBmaWxsPSIjZmZmIi8+PHBhdGggZmlsbD0iIzAwMCIgZD0iTTQsNGg3djFoLTd6TTE1LDRoMXYxaC0xek0xOSw0aDF2MWgtMXpNMjEsNGgzdjFoLTN6TTI1LDRoM3YxaC0zek0yOSw0aDJ2MWgtMnpNMzQsNGg3djFoLTd6TTQsNWgxdjFoLTF6TTEwLDVoMXYxaC0xek0xMiw1aDJ2MWgtMnpNMTksNWg3djFoLTd6TTI3LDVoMXYxaC0xek0yOSw1aDF2MWgtMXpNMzEsNWgxdjFoLTF6TTM0LDVoMXYxaC0xek00MCw1aDF2MWgtMXpNNCw2aDF2MWgtMXpNNiw2aDN2MWgtM3pNMTAsNmgxdjFoLTF6TTE0LDZoMXYxaC0xek0xNiw2aDF2MWgtMXpNMjQsNmgxdjFoLTF6TTI3LDZoMXYxaC0xek0zMCw2aDF2MWgtMXpNMzIsNmgxdjFoLTF6TTM0LDZoMXYxaC0xek0zNiw2aDN2MWgtM3pNNDAsNmgxdjFoLTF6TTQsN2gxdjFoLTF6TTYsN2gzdjFoLTN6TTEwLDdoMXYxaC0xek0xNCw3aDR2MWgtNHpNMTksN2gxdjFoLTF6TTIxLDdoMnYxaC0yek0yNCw3aDF2MWgtMXpNMjYsN2gydjFoLTJ6TTMwLDdoMnYxaC0yek0zNCw3aDF2MWgtMXpNMzYsN2gzdjFoLTN6TTQwLDdoMXYxaC0xek00LDhoMXYxaC0xek02LDhoM3YxaC0zek0xMCw4aDF2MWgtMXpNMTIsOGgxdjFoLTF6TTE0LDhoM3YxaC0zek0xOCw4aDF2MWgtMXpNMjIsOGg2djFoLTZ6TTM0LDhoMXYxaC0xek0zNiw4aDN2MWgtM3pNNDAsOGgxdjFoLTF6TTQsOWgxdjFoLTF6TTEwLDloMXYxaC0xek0xNCw5aDF2MWgtMXpNMTcsOWgxdjFoLTF6TTIzLDloMXYxaC0xek0yNSw5aDF2MWgtMXpNMjcsOWg2djFoLTZ6TTM0LDloMXYxaC0xek00MCw5aDF2MWgtMXpNNCwxMGg3djFoLTd6TTEyLDEwaDF2MWgtMXpNMTQsMTBoMXYxaC0xek0xNiwxMGgxdjFoLTF6TTE4LDEwaDF2MWgtMXpNMjAsMTBoMXYxaC0xek0yMiwxMGgxdjFoLTF6TTI0LDEwaDF2MWgtMXpNMjYsMTBoMXYxaC0xek0yOCwxMGgxdjFoLTF6TTMwLDEwaDF2MWgtMXpNMzIsMTBoMXYxaC0xek0zNCwxMGg3djFoLTd6TTEzLDExaDN2MWgtM3pNMTcsMTFoMnYxaC0yek0yMCwxMWgxMXYxaC0xMXpNMzIsMTFoMXYxaC0xek00LDEyaDF2MWgtMXpNNiwxMmgxdjFoLTF6TTgsMTJoMXYxaC0xek0xMCwxMmgxdjFoLTF6TTE0LDEyaDF2MWgtMXpNMTcsMTJoMXYxaC0xek0xOSwxMmgzdjFoLTN6TTI1LDEyaDF2MWgtMXpNMjcsMTJoMnYxaC0yek0zMSwxMmgxdjFoLTF6TTM2LDEyaDF2MWgtMXpNMzksMTJoMXYxaC0xek00LDEzaDF2MWgtMXpNNiwxM2gxdjFoLTF6TTExLDEzaDF2MWgtMXpNMTQsMTNoMnYxaC0yek0xOSwxM2gydjFoLTJ6TTI0LDEzaDF2MWgtMXpNMjYsMTNoMXYxaC0xek0zMSwxM2gzdjFoLTN6TTM3LDEzaDF2MWgtMXpNNDAsMTNoMXYxaC0xek00LDE0aDJ2MWgtMnpNNywxNGgydjFoLTJ6TTEwLDE0aDN2MWgtM3pNMTUsMTRoMXYxaC0xek0xOCwxNGg1djFoLTV6TTI0LDE0aDJ2MWgtMnpNMjgsMTRoMXYxaC0xek0zMCwxNGgxdjFoLTF6TTMyLDE0aDJ2MWgtMnpNMzgsMTRoM3YxaC0zek00LDE1aDN2MWgtM3pNOCwxNWgydjFoLTJ6TTExLDE1aDN2MWgtM3pNMTUsMTVoNHYxaC00ek0yMCwxNWgydjFoLTJ6TTIzLDE1aDF2MWgtMXpNMjUsMTVoMXYxaC0xek0yNywxNWgxdjFoLTF6TTI5LDE1aDR2MWgtNHpNMzQsMTVoM3YxaC0zek0zOSwxNWgxdjFoLTF6TTgsMTZoMXYxaC0xek0xMCwxNmgxdjFoLTF6TTEyLDE2aDF2MWgtMXpNMTQsMTZoNHYxaC00ek0xOSwxNmgxdjFoLTF6TTIzLDE2aDF2MWgtMXpNMjUsMTZoMnYxaC0yek0yOCwxNmgydjFoLTJ6TTMyLDE2aDF2MWgtMXpNMzQsMTZoMXYxaC0xek0zOCwxNmgzdjFoLTN6TTQsMTdoMXYxaC0xek02LDE3aDN2MWgtM3pNMTEsMTdoMXYxaC0xek0xNCwxN2gxdjFoLTF6TTE4LDE3aDN2MWgtM3pNMjQsMTdoM3YxaC0zek0yOCwxN2gxdjFoLTF6TTMzLDE3aDN2MWgtM3pNMzcsMTdoMXYxaC0xek00LDE4aDF2MWgtMXpNNiwxOGgxdjFoLTF6TTgsMThoMXYxaC0xek0xMCwxOGgzdjFoLTN6TTE1LDE4aDF2MWgtMXpNMjAsMThoMnYxaC0yek0yNSwxOGgydjFoLTJ6TTI4LDE4aDF2MWgtMXpNMzAsMThoMXYxaC0xek0zMiwxOGgxdjFoLTF6TTM0LDE4aDF2MWgtMXpNMzgsMThoM3YxaC0zek00LDE5aDJ2MWgtMnpNMTIsMTloMXYxaC0xek0xNSwxOWgxNXYxaC0xNXpNMzEsMTloMXYxaC0xek0zMywxOWg0djFoLTR6TTM5LDE5aDF2MWgtMXpNNSwyMGgxdjFoLTF6TTcsMjBoNXYxaC01ek0xNCwyMGgxdjFoLTF6TTE3LDIwaDF2MWgtMXpNMjEsMjBoMXYxaC0xek0yNCwyMGgydjFoLTJ6TTMyLDIwaDN2MWgtM3pNMzYsMjBoMXYxaC0xek00LDIxaDF2MWgtMXpNNiwyMWgxdjFoLTF6TTksMjFoMXYxaC0xek0xMSwyMWgxdjFoLTF6TTE3LDIxaDF2MWgtMXpNMTksMjFoMXYxaC0xek0yMSwyMWgxdjFoLTF6TTIzLDIxaDJ2MWgtMnpNMjYsMjFoN3YxaC03ek0zNywyMWgydjFoLTJ6TTQwLDIxaDF2MWgtMXpNNCwyMmgxdjFoLTF6TTYsMjJoMnYxaC0yek05LDIyaDJ2MWgtMnpNMTIsMjJoMXYxaC0xek0xNCwyMmgxdjFoLTF6TTE2LDIyaDN2MWgtM3pNMjEsMjJoMnYxaC0yek0yNSwyMmgxdjFoLTF6TTI4LDIyaDN2MWgtM3pNMzMsMjJoMXYxaC0xek0zNiwyMmgydjFoLTJ6TTM5LDIyaDJ2MWgtMnpNNCwyM2gxdjFoLTF6TTcsMjNoMXYxaC0xek05LDIzaDF2MWgtMXpNMTIsMjNoNHYxaC00ek0xNywyM2gzdjFoLTN6TTIxLDIzaDF2MWgtMXpNMjMsMjNoNXYxaC01ek0yOSwyM2gydjFoLTJ6TTM0LDIzaDJ2MWgtMnpNMzcsMjNoMXYxaC0xek00MCwyM2gxdjFoLTF6TTQsMjRoMXYxaC0xek02LDI0aDF2MWgtMXpNOCwyNGgxdjFoLTF6TTEwLDI0aDF2MWgtMXpNMTMsMjRoMXYxaC0xek0xNSwyNGg0djFoLTR6TTIwLDI0aDF2MWgtMXpNMjMsMjRoM3YxaC0zek0yNywyNGgzdjFoLTN6TTMzLDI0aDN2MWgtM3pNMzcsMjRoMXYxaC0xek0zOSwyNGgxdjFoLTF6TTUsMjVoMnYxaC0yek05LDI1aDF2MWgtMXpNMTIsMjVoMXYxaC0xek0xNCwyNWgydjFoLTJ6TTE3LDI1aDR2MWgtNHpNMjIsMjVoMXYxaC0xek0yNCwyNWgzdjFoLTN6TTI4LDI1aDJ2MWgtMnpNMzIsMjVoMXYxaC0xek0zNCwyNWgydjFoLTJ6TTM3LDI1aDF2MWgtMXpNMzksMjVoMXYxaC0xek01LDI2aDJ2MWgtMnpNOSwyNmgydjFoLTJ6TTE0LDI2aDJ2MWgtMnpNMTcsMjZoMnYxaC0yek0yMSwyNmgxdjFoLTF6TTI0LDI2aDN2MWgtM3pNMzAsMjZoMXYxaC0xek0zMiwyNmgxdjFoLTF6TTM0LDI2aDF2MWgtMXpNMzYsMjZoNXYxaC01ek01LDI3aDF2MWgtMXpNOCwyN2gydjFoLTJ6TTExLDI3aDR2MWgtNHpNMTYsMjdoMXYxaC0xek0yMSwyN2gxdjFoLTF6TTI1LDI3aDN2MWgtM3pNMjksMjdoMXYxaC0xek0zMSwyN2gydjFoLTJ6TTM0LDI3aDN2MWgtM3pNNCwyOGgydjFoLTJ6TTgsMjhoMXYxaC0xek0xMCwyOGgxdjFoLTF6TTEyLDI4aDF2MWgtMXpNMTUsMjhoMXYxaC0xek0xOCwyOGgxdjFoLTF6TTIwLDI4aDF2MWgtMXpNMjIsMjhoMXYxaC0xek0yNSwyOGgxdjFoLTF6TTMzLDI4aDV2MWgtNXpNMzksMjhoMnYxaC0yek01LDI5aDJ2MWgtMnpNOSwyOWgxdjFoLTF6TTEzLDI5aDF2MWgtMXpNMTUsMjloMXYxaC0xek0xOSwyOWgzdjFoLTN6TTI1LDI5aDR2MWgtNHpNMzcsMjloMXYxaC0xek0zOSwyOWgydjFoLTJ6TTQsMzBoMXYxaC0xek05LDMwaDN2MWgtM3pNMTQsMzBoMXYxaC0xek0xNywzMGg2djFoLTZ6TTI0LDMwaDN2MWgtM3pNMjgsMzBoMnYxaC0yek0zMywzMGgydjFoLTJ6TTM2LDMwaDJ2MWgtMnpNMzksMzBoMnYxaC0yek01LDMxaDJ2MWgtMnpNMTIsMzFoM3YxaC0zek0xNiwzMWgxdjFoLTF6TTIwLDMxaDN2MWgtM3pNMjUsMzFoM3YxaC0zek0yOSwzMWgzdjFoLTN6TTMzLDMxaDF2MWgtMXpNMzUsMzFoMnYxaC0yek00MCwzMWgxdjFoLTF6TTQsMzJoMXYxaC0xek03LDMyaDJ2MWgtMnpNMTAsMzJoMnYxaC0yek0xNCwzMmgxdjFoLTF6TTE2LDMyaDF2MWgtMXpNMTgsMzJoMnYxaC0yek0yNSwzMmgxdjFoLTF6TTI4LDMyaDJ2MWgtMnpNMzEsMzJoOXYxaC05ek0xMiwzM2gxdjFoLTF6TTE4LDMzaDF2MWgtMXpNMjIsMzNoMXYxaC0xek0yNCwzM2gydjFoLTJ6TTI4LDMzaDF2MWgtMXpNMzIsMzNoMXYxaC0xek0zNiwzM2gxdjFoLTF6TTM5LDMzaDF2MWgtMXpNNCwzNGg3djFoLTd6TTEzLDM0aDR2MWgtNHpNMTgsMzRoMXYxaC0xek0yMSwzNGgxdjFoLTF6TTI4LDM0aDV2MWgtNXpNMzQsMzRoMXYxaC0xek0zNiwzNGg1djFoLTV6TTQsMzVoMXYxaC0xek0xMCwzNWgxdjFoLTF6TTEzLDM1aDF2MWgtMXpNMTUsMzVoMnYxaC0yek0xOCwzNWgydjFoLTJ6TTI1LDM1aDJ2MWgtMnpNMjksMzVoMnYxaC0yek0zMiwzNWgxdjFoLTF6TTM2LDM1aDF2MWgtMXpNMzksMzVoMXYxaC0xek00LDM2aDF2MWgtMXpNNiwzNmgzdjFoLTN6TTEwLDM2aDF2MWgtMXpNMTIsMzZoMnYxaC0yek0xOCwzNmgydjFoLTJ6TTI1LDM2aDF2MWgtMXpNMjcsMzZoMXYxaC0xek0yOSwzNmgydjFoLTJ6TTMyLDM2aDZ2MWgtNnpNNDAsMzZoMXYxaC0xek00LDM3aDF2MWgtMXpNNiwzN2gzdjFoLTN6TTEwLDM3aDF2MWgtMXpNMTMsMzdoMXYxaC0xek0xNSwzN2gydjFoLTJ6TTE4LDM3aDJ2MWgtMnpNMjEsMzdoMXYxaC0xek0yNCwzN2gydjFoLTJ6TTI3LDM3aDJ2MWgtMnpNMzAsMzdoMXYxaC0xek0zMiwzN2gxdjFoLTF6TTM1LDM3aDN2MWgtM3pNMzksMzdoMXYxaC0xek00LDM4aDF2MWgtMXpNNiwzOGgzdjFoLTN6TTEwLDM4aDF2MWgtMXpNMTIsMzhoMnYxaC0yek0yMSwzOGgxdjFoLTF6TTI5LDM4aDF2MWgtMXpNMzEsMzhoMXYxaC0xek0zNSwzOGgydjFoLTJ6TTM4LDM4aDN2MWgtM3pNNCwzOWgxdjFoLTF6TTEwLDM5aDF2MWgtMXpNMTQsMzloMXYxaC0xek0xOCwzOWgydjFoLTJ6TTIxLDM5aDZ2MWgtNnpNMjksMzloMXYxaC0xek0zNSwzOWgxdjFoLTF6TTM5LDM5aDF2MWgtMXpNNCw0MGg3djFoLTd6TTEyLDQwaDF2MWgtMXpNMTQsNDBoMnYxaC0yek0xNyw0MGgxdjFoLTF6TTI0LDQwaDJ2MWgtMnpNMjksNDBoNXYxaC01ek0zNSw0MGg2djFoLTZ6Ii8+PC9zdmc+' }),
  };
  const real = window.fetch.bind(window);
  window.fetch = (input, opt) => {
    const url = new URL(typeof input === 'string' ? input : input.url, location.href);
    const route = ROUTES[url.pathname];
    if (!url.pathname.startsWith('/api/')) return real(input, opt);
    const post = (opt && opt.method === 'POST') || (input && input.method === 'POST');
    const body = post && POST_ROUTES[url.pathname] ? JSON.stringify(POST_ROUTES[url.pathname]())
      : post ? JSON.stringify({ ok: true, message: 'Demo panel: nothing was changed.' })
      : route ? JSON.stringify(route(url.searchParams)) : '{}';
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
