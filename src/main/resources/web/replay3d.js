// Hycon replay, 3D view: blocky Hytale-shaped players walking over the real world map (laid flat as the floor),
// facing where they look, with name tags; hits as arrows with their distance, red when flagged. The camera
// follows the watched player from behind and above; drag to orbit, scroll to zoom. three.js (MIT, bundled in
// /vendor) is loaded the first time a 3D replay is shown, so other pages don't pay for it.
'use strict';

let THREE_READY = null;

function loadThree() {
  if (window.THREE && THREE.OrbitControls) return Promise.resolve();
  if (THREE_READY) return THREE_READY;
  const add = (src) => new Promise((ok, fail) => {
    const s = document.createElement('script');
    s.src = src; s.onload = ok; s.onerror = () => fail(new Error('Could not load ' + src));
    document.head.append(s);
  });
  THREE_READY = add('/vendor/three.min.js').then(() => add('/vendor/OrbitControls.js'));
  return THREE_READY;
}

// Player proportions in blocks (about Hytale's: 1.8 tall).
const P3 = { legH: 0.75, legW: 0.22, bodyH: 0.7, bodyW: 0.5, bodyD: 0.28, armW: 0.18, head: 0.42 };
const SKIN = 0xe0b48f, TROUSERS = 0x2c3140;

/**
 * Builds the 3D view into {@code box}. {@code r} is the replay; {@code colourOf} maps names to colours;
 * {@code at(actor, t)} gives a blended sample; {@code main} is the watched actor. Returns { draw(now), resize() }
 * once three.js has loaded.
 */
function replay3d(box, r, actors, main, colourOf, at, events, flagColour, eventMs, trailMs) {
  return loadThree().then(() => {
    const T = window.THREE;
    const renderer = new T.WebGLRenderer({ antialias: true });
    renderer.setPixelRatio(devicePixelRatio);
    box.append(renderer.domElement);
    renderer.domElement.className = 'replay replay3d';
    const scene = new T.Scene();
    scene.background = new T.Color(0x0b0c0f);
    scene.add(new T.HemisphereLight(0xffffff, 0x404050, 0.9));
    const sun = new T.DirectionalLight(0xffffff, 0.7);
    sun.position.set(30, 60, 20);
    scene.add(sun);
    const camera = new T.PerspectiveCamera(50, 2, 0.1, 2000);
    const controls = new T.OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.maxPolarAngle = Math.PI * 0.49; // never under the floor
    controls.minDistance = 3;
    controls.maxDistance = 60;

    // Floor: the world map image laid flat (north, -Z, at the top of the image), or a plain grid without one.
    let groundY = Infinity;
    for (const a of actors) for (const s of a.samples) groundY = Math.min(groundY, s[2]);
    let x0 = Infinity, x1 = -Infinity, z0 = Infinity, z1 = -Infinity;
    for (const a of actors) for (const [, x, , z] of a.samples) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z); }
    if (r.world) {
      const ch = (b) => Math.floor(b / 32);
      let cx0 = ch(x0 - 48), cx1 = ch(x1 + 48), cz0 = ch(z0 - 48), cz1 = ch(z1 + 48);
      const clip = (a, b) => { if (b - a < 24) return [a, b]; const m = Math.floor((a + b) / 2); return [m - 11, m + 12]; };
      [cx0, cx1] = clip(cx0, cx1); [cz0, cz1] = clip(cz0, cz1);
      const w = (cx1 - cx0 + 1) * 32, d = (cz1 - cz0 + 1) * 32;
      const box = [cx0 * 32, cz0 * 32, (cx1 + 1) * 32, (cz1 + 1) * 32];
      const tex = new T.TextureLoader().load('/api/worldmap?world=' + encodeURIComponent(r.world) + '&cx0=' + cx0 + '&cz0=' + cz0 + '&cx1=' + cx1 + '&cz1=' + cz1,
        (t) => { colourTerrain(t.image, box); renderer.render(scene, camera); });
      tex.magFilter = T.NearestFilter;
      tex.minFilter = T.LinearMipmapLinearFilter;
      const floor = new T.Mesh(new T.PlaneGeometry(w, d), new T.MeshLambertMaterial({ map: tex }));
      floor.rotation.x = -Math.PI / 2;
      // Under real terrain the flat map sits a little lower, filling in past the terrain's edge.
      floor.position.set(cx0 * 32 + w / 2, groundY - (r.terrain || r.blocks || r.blockSource ? 1.5 : 0.02), cz0 * 32 + d / 2);
      scene.add(floor);
    }
    // The real ground, as blocks: each column is a stack up to its top block, deep enough to close cliffs.
    let terrain = null;
    // The see-through hole's rings: up to this far from the camera-player line (blocks), drawn at this opacity.
    const HOLE_RINGS = [[1.4, 0.06], [2.3, 0.28], [3.1, 0.6]];
    // Blocks come in tiles: all at once in the page (r.blocks, one tile), or a tile at a time from the plugin around
    // the watched player (r.blockSource), in a height band that follows them, so a small area is ever loaded.
    const TILE = 16, TILE_REACH = 2, BAND_STEP = 8, BAND_DOWN = 8, BAND_UP = 24, MAX_LOADING = 4, BAND_SLACK = 2;
    const boxGeometry = new T.BoxGeometry(1, 1, 1);
    const tiles = new Map(), loading = new Set();
    let ghost = null;
    const streaming = !!(r.blockSource && r.blockSource.src);
    if (streaming || (r.blocks && r.blocks.list && r.blocks.list.length)) {
      // See-through copies of the blocks between the camera and the watched player, in rings that fade: nearly
      // gone in the middle, half there around it, a little faded at the edge, so the hole has soft edges.
      ghost = HOLE_RINGS.map(([, opacity], i) => {
        const g = new T.InstancedMesh(boxGeometry, new T.MeshLambertMaterial({ color: 0xffffff, transparent: true,
          opacity, depthWrite: false }), 6000);
        g.count = 0;
        g.renderOrder = 5 + i;
        scene.add(g);
        return g;
      });
      if (!streaming) addTile('all', r.blocks);
    } else if (r.terrain && r.terrain.top) {
      terrain = buildTerrain(r.terrain);
      scene.add(terrain.mesh);
    }

    // The real blocks, each in its kind's colour, a little lighter or darker block by block so surfaces read.
    function addTile(key, b) {
      const list = b.list, count = list.length / 4;
      if (!count) { tiles.set(key, { mesh: null, list, hidden: new Map() }); return; }
      const mesh = new T.InstancedMesh(boxGeometry, new T.MeshLambertMaterial({ color: 0xffffff }), count);
      const m = new T.Matrix4(), base = b.colours.map((c) => new T.Color(c)), c = new T.Color();
      for (let i = 0; i < count; i++) {
        const x = list[i * 4], y = list[i * 4 + 1], z = list[i * 4 + 2], k = list[i * 4 + 3];
        m.makeTranslation(x + 0.5, y + 0.5, z + 0.5);
        mesh.setMatrixAt(i, m);
        const jitter = 0.92 + (((x * 73856093) ^ (y * 19349663) ^ (z * 83492791)) >>> 0) % 17 / 100;
        c.copy(base[k] || base[0]).multiplyScalar(jitter);
        mesh.setColorAt(i, c);
      }
      mesh.instanceMatrix.needsUpdate = true;
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
      scene.add(mesh);
      tiles.set(key, { mesh, list, hidden: new Map() });
    }

    function dropTile(key) {
      const t = tiles.get(key);
      if (t && t.mesh) { scene.remove(t.mesh); t.mesh.material.dispose(); t.mesh.dispose(); }
      tiles.delete(key);
    }

    // Loads the tiles around the watched player in their height band and lets go of the rest.
    function stream(px, py, pz) {
      if (!streaming) return;
      lastAt = [px, py, pz];
      // The band only moves once the player is clearly past its edge, so bobbing on the edge doesn't reload it all.
      if (band === null || py < band - BAND_SLACK || py >= band + BAND_STEP + BAND_SLACK) band = Math.floor(py / BAND_STEP) * BAND_STEP;
      const tx0 = Math.floor(px / TILE), tz0 = Math.floor(pz / TILE);
      const want = new Set();
      for (let dz = -TILE_REACH; dz <= TILE_REACH; dz++) for (let dx = -TILE_REACH; dx <= TILE_REACH; dx++) {
        want.add((tx0 + dx) + ',' + (tz0 + dz) + ',' + band);
      }
      for (const key of [...tiles.keys()]) if (!want.has(key)) dropTile(key);
      // Nearest first, a few at a time.
      const missing = [...want].filter((k) => !tiles.has(k) && !loading.has(k)).sort((a, b) => {
        const [ax, az] = a.split(',').map(Number), [bx, bz] = b.split(',').map(Number);
        return Math.hypot(ax - tx0, az - tz0) - Math.hypot(bx - tx0, bz - tz0);
      });
      for (const key of missing) {
        if (loading.size >= MAX_LOADING) break;
        const [tx, tz, by] = key.split(',').map(Number);
        loading.add(key);
        api('GET', '/api/blocks?src=' + encodeURIComponent(r.blockSource.src) + '&key=' + encodeURIComponent(r.blockSource.key)
          + '&x=' + tx * TILE + '&z=' + tz * TILE + '&s=' + TILE + '&y0=' + (by - BAND_DOWN) + '&y1=' + (by + BAND_UP))
          .then((b) => {
            loading.delete(key);
            if (!latestWant.has(key)) return;
            addTile(key, b);
            stream(...lastAt); // the next tiles, also while paused
            if (followed) clearView(followed);
            renderer.render(scene, camera);
          })
          .catch(() => loading.delete(key));
      }
      latestWant = want;
    }
    let latestWant = new Set(), lastAt = [0, 0, 0], band = null;

    function buildTerrain(tr) {
      const top = (x, z) => (x < 0 || z < 0 || x >= tr.w || z >= tr.d ? null : tr.top[z * tr.w + x]);
      const cubes = [];
      let lo = Infinity, hi = -Infinity;
      for (let z = 0; z < tr.d; z++) for (let x = 0; x < tr.w; x++) {
        const y = top(x, z);
        if (y === null || y === undefined) continue;
        let low = y;
        for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
          const n = top(x + dx, z + dz);
          if (n !== null && n !== undefined) low = Math.min(low, n + 1);
        }
        low = Math.max(low, y - 8); // cliffs are filled at most 8 blocks down
        for (let b = y; b >= low; b--) cubes.push([x, b, z, b === y]);
        lo = Math.min(lo, y); hi = Math.max(hi, y);
      }
      const mesh = new T.InstancedMesh(new T.BoxGeometry(1, 1, 1), new T.MeshLambertMaterial({ color: 0xffffff }), cubes.length);
      const m = new T.Matrix4(), c = new T.Color();
      // Until the map's colours are in: grass on top, earth below, a little darker for lower ground.
      const shade = (y, isTop) => {
        const f = 0.75 + 0.25 * ((y - lo) / ((hi - lo) || 1));
        return isTop ? c.setRGB(0.32 * f, 0.55 * f, 0.26 * f) : c.setRGB(0.42 * f, 0.31 * f, 0.2 * f);
      };
      cubes.forEach(([x, y, z, isTop], i) => {
        m.makeTranslation(tr.x0 + x + 0.5, y + 0.5, tr.z0 + z + 0.5);
        mesh.setMatrixAt(i, m);
        mesh.setColorAt(i, shade(y, isTop));
      });
      mesh.instanceMatrix.needsUpdate = true;
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
      return { mesh, cubes, tr, shade };
    }

    // Once the map image is in, the top blocks take its colour at their spot (sides stay a darker shade of it).
    function colourTerrain(img, box) {
      if (!terrain) return;
      const cv = document.createElement('canvas');
      cv.width = img.naturalWidth; cv.height = img.naturalHeight;
      const g = cv.getContext('2d');
      g.drawImage(img, 0, 0);
      let data;
      try { data = g.getImageData(0, 0, cv.width, cv.height).data; } catch (e) { return; }
      const pxPerBlock = cv.width / (box[2] - box[0]);
      const c = new T.Color();
      terrain.cubes.forEach(([x, y, z, isTop], i) => {
        const px = Math.floor((terrain.tr.x0 + x - box[0]) * pxPerBlock), pz = Math.floor((terrain.tr.z0 + z - box[1]) * pxPerBlock);
        if (px < 0 || pz < 0 || px >= cv.width || pz >= cv.height) return;
        const k = (pz * cv.width + px) * 4;
        if (data[k + 3] < 10) return;
        const f = isTop ? 1 : 0.6;
        terrain.mesh.setColorAt(i, c.setRGB(data[k] / 255 * f, data[k + 1] / 255 * f, data[k + 2] / 255 * f));
      });
      terrain.mesh.instanceColor.needsUpdate = true;
    }

    const grid = new T.GridHelper(Math.max(64, Math.ceil(Math.max(x1 - x0, z1 - z0) / 8) * 8 + 64),
      Math.max(16, Math.ceil(Math.max(x1 - x0, z1 - z0) / 8) + 16), 0x3a3d46, 0x24262d);
    grid.position.set((x0 + x1) / 2, groundY, (z0 + z1) / 2);
    grid.material.transparent = true;
    grid.material.opacity = r.world ? 0.25 : 0.8;
    if (!r.terrain && !r.blocks && !r.blockSource) scene.add(grid);

    function textSprite(text, colour) {
      const c = document.createElement('canvas'), g = c.getContext('2d'), size = 48;
      g.font = '600 ' + size + 'px system-ui';
      const w = Math.ceil(g.measureText(text).width) + 24;
      c.width = w; c.height = size + 20;
      g.font = '600 ' + size + 'px system-ui';
      g.fillStyle = 'rgba(10,11,14,0.75)'; g.fillRect(0, 0, c.width, c.height);
      g.fillStyle = colour; g.textBaseline = 'middle'; g.fillText(text, 12, c.height / 2 + 2);
      const tex = new T.CanvasTexture(c);
      const sprite = new T.Sprite(new T.SpriteMaterial({ map: tex, depthTest: false, transparent: true }));
      sprite.scale.set(c.width / c.height * 0.45, 0.45, 1);
      sprite.renderOrder = 10;
      return sprite;
    }

    // Every part's material, per figure, so a hit can make the whole body glow.
    let parts = null;
    function box3(w, h, d, colour) {
      const m = new T.Mesh(new T.BoxGeometry(w, h, d), new T.MeshLambertMaterial({ color: colour }));
      if (parts) parts.push(m.material);
      return m;
    }

    // One blocky player, facing -Z when not turned (Hytale's yaw 0). Limbs hang from pivots so they can swing.
    function figure(colour, isMain) {
      parts = [];
      const g = new T.Group(), shirt = new T.Color(colour);
      const limb = (w, h, colourOf, x, y) => {
        const pivot = new T.Group();
        pivot.position.set(x, y, 0);
        const m = box3(w, h, w, colourOf);
        m.position.y = -h / 2;
        pivot.add(m);
        g.add(pivot);
        return pivot;
      };
      const legL = limb(P3.legW, P3.legH, TROUSERS, -P3.legW * 0.55, P3.legH);
      const legR = limb(P3.legW, P3.legH, TROUSERS, P3.legW * 0.55, P3.legH);
      const body = box3(P3.bodyW, P3.bodyH, P3.bodyD, shirt);
      body.position.y = P3.legH + P3.bodyH / 2;
      g.add(body);
      const armY = P3.legH + P3.bodyH - 0.05;
      const armL = limb(P3.armW, P3.bodyH, shirt, -(P3.bodyW / 2 + P3.armW / 2), armY);
      const armR = limb(P3.armW, P3.bodyH, shirt, P3.bodyW / 2 + P3.armW / 2, armY);
      const head = box3(P3.head, P3.head, P3.head, SKIN);
      head.position.y = P3.legH + P3.bodyH + P3.head / 2 + 0.02;
      g.add(head);
      // A face on the front (-Z) so you can see which way they look.
      const face = box3(P3.head * 0.7, P3.head * 0.18, 0.02, 0x222222);
      face.position.set(0, head.position.y + 0.04, -P3.head / 2 - 0.01);
      g.add(face);
      if (isMain) {
        const ring = new T.Mesh(new T.RingGeometry(0.55, 0.7, 32), new T.MeshBasicMaterial({ color: colour, side: T.DoubleSide, transparent: true, opacity: 0.8 }));
        ring.rotation.x = -Math.PI / 2;
        ring.position.y = 0.03;
        g.add(ring);
      }
      const materials = parts;
      parts = null;
      return { g, legL, legR, armL, armR, materials };
    }

    const people = actors.map((a) => {
      const colour = colourOf.get(a.name), f = figure(colour, a === main);
      const tag = textSprite(a.name, '#ffffff');
      tag.position.y = 2.25;
      f.g.add(tag);
      scene.add(f.g);
      const trail = new T.Line(new T.BufferGeometry(), new T.LineBasicMaterial({ color: colour, transparent: true, opacity: 0.6 }));
      scene.add(trail);
      return { a, f, trail };
    });

    // Hit arrows are made on demand and reused.
    const arrows = [];
    function arrow(i) {
      if (!arrows[i]) {
        const line = new T.Line(new T.BufferGeometry().setFromPoints([new T.Vector3(), new T.Vector3()]),
          new T.LineDashedMaterial({ color: 0xffffff, transparent: true, dashSize: 1000, gapSize: 0 }));
        const cone = new T.Mesh(new T.ConeGeometry(0.15, 0.4, 12), new T.MeshBasicMaterial({ color: 0xffffff, transparent: true }));
        scene.add(line); scene.add(cone);
        arrows[i] = { line, cone, label: null, text: '' };
      }
      return arrows[i];
    }

    const CHEST = P3.legH + P3.bodyH * 0.6;
    let followed = null;

    // Blocks hidden because they stand between the camera and the watched player (each tile keeps its own, id ->
    // real matrix and ring). They are drawn faintly by the ghost meshes instead, so you see inside a house.
    const ZERO = new T.Matrix4().makeScale(0, 0, 0), tmpC = new T.Color();
    function clearView(target) {
      if (!ghost) return;
      const outer = HOLE_RINGS[HOLE_RINGS.length - 1][0];
      const cx = camera.position.x, cy = camera.position.y, cz = camera.position.z;
      // The line runs to the player's chest; the last bit by the player stays solid so the floor under them shows.
      const dx = target.x - cx, dy = target.y - cy, dz = target.z - cz, len2 = dx * dx + dy * dy + dz * dz;
      const stop = Math.max(0, 1 - 1.2 / Math.sqrt(len2 || 1));
      const n = ghost.map(() => 0);
      for (const t of tiles.values()) {
        if (!t.mesh) continue;
        const list = t.list, count = list.length / 4, want = new Map();
        for (let i = 0; i < count; i++) {
          const bx = list[i * 4] + 0.5 - cx, by = list[i * 4 + 1] + 0.5 - cy, bz = list[i * 4 + 2] + 0.5 - cz;
          const k = (bx * dx + by * dy + bz * dz) / (len2 || 1);
          if (k <= 0 || k >= stop) continue;
          const ex = bx - dx * k, ey = by - dy * k, ez = bz - dz * k, d = Math.sqrt(ex * ex + ey * ey + ez * ez);
          if (d < outer) want.set(i, HOLE_RINGS.findIndex(([edge]) => d < edge));
        }
        for (const [id, h] of t.hidden) if (!want.has(id)) { t.mesh.setMatrixAt(id, h.m); t.hidden.delete(id); }
        for (const [id, ring] of want) {
          const h = t.hidden.get(id);
          if (h) { h.ring = ring; continue; }
          const m = new T.Matrix4();
          t.mesh.getMatrixAt(id, m);
          t.hidden.set(id, { m, ring });
          t.mesh.setMatrixAt(id, ZERO);
        }
        t.mesh.instanceMatrix.needsUpdate = true;
        for (const [id, h] of t.hidden) {
          const g = ghost[h.ring];
          if (n[h.ring] >= g.instanceMatrix.count) continue;
          g.setMatrixAt(n[h.ring], h.m);
          t.mesh.getColorAt(id, tmpC);
          g.setColorAt(n[h.ring], tmpC);
          n[h.ring]++;
        }
      }
      ghost.forEach((g, i) => {
        g.count = n[i];
        g.instanceMatrix.needsUpdate = true;
        if (g.instanceColor) g.instanceColor.needsUpdate = true;
      });
    }

    function draw(now) {
      const where = new Map();
      for (const p of people) {
        const s = at(p.a, now), prev = at(p.a, now - 100);
        p.f.g.position.set(s[1], s[2], s[3]);
        p.f.g.rotation.y = s[4]; // Hytale's yaw turns the same way as three.js around +Y
        const speed = Math.hypot(s[1] - prev[1], s[3] - prev[3]) * 10;
        const swing = Math.sin(now / 1000 * 9) * Math.min(0.9, speed * 0.12);
        p.f.legL.rotation.x = swing; p.f.legR.rotation.x = -swing;
        p.f.armL.rotation.x = -swing * 0.8; p.f.armR.rotation.x = swing * 0.8;
        where.set(p.a.name, s);
        const pts = [];
        for (const q of p.a.samples) {
          if (q[0] > now) break;
          if (q[0] >= now - trailMs) pts.push(new T.Vector3(q[1], q[2] + 0.05, q[3]));
        }
        p.trail.geometry.setFromPoints(pts.length > 1 ? pts : [new T.Vector3(), new T.Vector3()]);
        p.trail.visible = pts.length > 1;
      }
      // Whoever was just hit glows red, fading out: bright for a flagged hit, softer for a normal one.
      const glow = new Map();
      for (const [t, , to, bad] of events) {
        if (!to || t > now || t < now - eventMs) continue;
        const g = (1 - (now - t) / eventMs) * (bad ? 1 : 0.5);
        glow.set(to, Math.max(glow.get(to) || 0, g));
      }
      for (const p of people) {
        const g = glow.get(p.a.name) || 0;
        for (const m of p.f.materials) m.emissive.setRGB(g, 0, 0);
      }
      let used = 0;
      for (const [t, from, to, bad, , kind] of events) {
        if (t > now || t < now - eventMs || !to) continue;
        const a = where.get(from), b = where.get(to);
        if (!a || !b) continue;
        const fade = 1 - (now - t) / eventMs, colour = bad ? flagColour : '#ffffff';
        const A = new T.Vector3(a[1], a[2] + CHEST, a[3]), B = new T.Vector3(b[1], b[2] + CHEST, b[3]);
        const dir = B.clone().sub(A), dist = dir.length();
        const end = A.clone().add(dir.clone().setLength(Math.max(0, dist - 0.35)));
        const ar = arrow(used++);
        ar.line.geometry.setFromPoints([A, end]);
        ar.line.computeLineDistances();
        // Shots are dashed, melee hits solid (a dash longer than any line).
        ar.line.material.dashSize = kind === 'shot' ? 0.5 : 1000;
        ar.line.material.gapSize = kind === 'shot' ? 0.35 : 0;
        ar.line.material.color.set(colour); ar.line.material.opacity = 0.4 + 0.6 * fade; ar.line.visible = true;
        ar.cone.position.copy(end);
        ar.cone.quaternion.setFromUnitVectors(new T.Vector3(0, 1, 0), dir.clone().normalize());
        ar.cone.material.color.set(colour); ar.cone.material.opacity = 0.4 + 0.6 * fade; ar.cone.visible = true;
        const text = Math.hypot(b[1] - a[1], b[2] - a[2], b[3] - a[3]).toFixed(1) + ' blocks';
        if (ar.text !== text + colour) {
          if (ar.label) scene.remove(ar.label);
          ar.label = textSprite(text, colour);
          ar.text = text + colour;
          scene.add(ar.label);
        }
        ar.label.position.copy(A.clone().add(B).multiplyScalar(0.5)).add(new T.Vector3(0, 0.5, 0));
        ar.label.material.opacity = 0.5 + 0.5 * fade;
        ar.label.visible = true;
      }
      for (let i = used; i < arrows.length; i++) {
        arrows[i].line.visible = false; arrows[i].cone.visible = false;
        if (arrows[i].label) arrows[i].label.visible = false;
      }
      // Follow the watched player: move the orbit target with them, keeping the camera's offset.
      const m = where.get(main.name), target = new T.Vector3(m[1], m[2] + 1, m[3]);
      if (!followed) {
        camera.position.set(m[1] + 6 * Math.sin(m[4]), m[2] + 6, m[3] + 6 * Math.cos(m[4]));
        controls.target.copy(target);
      } else {
        const moved = target.clone().sub(followed);
        camera.position.add(moved);
        controls.target.add(moved);
      }
      followed = target;
      stream(m[1], m[2], m[3]);
      controls.update();
      clearView(target);
      renderer.render(scene, camera);
    }

    function resize() {
      const w = box.clientWidth || 700, h = Math.round(Math.min(560, w * 0.5));
      renderer.setSize(w, h);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
    }
    controls.addEventListener('change', () => { if (followed) clearView(followed); renderer.render(scene, camera); });
    resize();
    // Back to the starting view: close behind the watched player.
    function reset() {
      followed = null;
    }
    return { draw, resize, reset, canvas: renderer.domElement };
  });
}
