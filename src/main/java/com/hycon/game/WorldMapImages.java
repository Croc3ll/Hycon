package com.hycon.game;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The real world map behind the heatmaps: the same images Hytale draws for its in-game map, one per
 * 32x32-block chunk, stitched into one PNG for an area. Chunks the map hasn't drawn are generated
 * by Hytale on request (that's the expensive part), so areas are capped at {@link #MAX_CHUNKS} per
 * side and finished pictures are cached for {@link #CACHE_MS}.
 */
public final class WorldMapImages {

    public static final int CHUNK = 32;
    public static final int MAX_CHUNKS = 48;
    /** Pixels per chunk in the stitched picture (2 blocks per pixel). */
    private static final int PX = 16;
    private static final long CACHE_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int CACHE_SIZE = 16;

    private record Entry(long time, byte[] png) {
    }

    private final Map<String, Entry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> e) {
            return size() > CACHE_SIZE;
        }
    };

    /**
     * PNG of chunks cx0..cx1, cz0..cz1 (inclusive) of a world, or null if the world doesn't exist or
     * has no map. North (smaller Z) is up, like the heatmap.
     */
    public byte[] png(String worldName, int cx0, int cz0, int cx1, int cz1) throws IOException {
        if (cx1 < cx0 || cz1 < cz0 || cx1 - cx0 >= MAX_CHUNKS || cz1 - cz0 >= MAX_CHUNKS) {
            throw new IllegalArgumentException("Area too large (at most " + MAX_CHUNKS + " chunks per side)");
        }
        String key = worldName + ":" + cx0 + "," + cz0 + "," + cx1 + "," + cz1;
        synchronized (cache) {
            Entry e = cache.get(key);
            if (e != null && System.currentTimeMillis() - e.time() < CACHE_MS) {
                return e.png();
            }
        }
        World world = Universe.get().getWorld(worldName);
        if (world == null || world.getWorldMapManager() == null) {
            return null;
        }
        WorldMapManager maps = world.getWorldMapManager();
        int w = cx1 - cx0 + 1, h = cz1 - cz0 + 1;
        List<CompletableFuture<MapImage>> futures = new ArrayList<>(w * h);
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                futures.add(maps.getImageAsync(cx, cz));
            }
        }
        BufferedImage out = new BufferedImage(w * PX, h * PX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
        for (int i = 0; i < futures.size(); i++) {
            MapImage img;
            try {
                // Chunks still being drawn after the deadline stay blank; the next load fills them in.
                img = futures.get(i).get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                continue;
            }
            BufferedImage tile = decode(img);
            if (tile != null) {
                g.drawImage(tile, (i % w) * PX, (i / w) * PX, PX, PX, null);
            }
        }
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        byte[] png = bytes.toByteArray();
        synchronized (cache) {
            cache.put(key, new Entry(System.currentTimeMillis(), png));
        }
        return png;
    }

    /** Hytale's map image: a palette of RGBA colours and indices packed lowest bit first, row by row. */
    static BufferedImage decode(MapImage m) {
        if (m == null || m.palette == null || m.packedIndices == null || m.width <= 0 || m.height <= 0 || m.bitsPerIndex <= 0) {
            return null;
        }
        int bits = m.bitsPerIndex, mask = (1 << bits) - 1;
        BufferedImage img = new BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_ARGB);
        byte[] a = m.packedIndices;
        for (int i = 0; i < m.width * m.height; i++) {
            long bit = (long) i * bits;
            int idx = 0;
            for (int b = 0; b < bits; b++) {
                long pos = bit + b;
                int byteIndex = (int) (pos >>> 3);
                if (byteIndex < a.length && ((a[byteIndex] >>> (pos & 7)) & 1) != 0) {
                    idx |= 1 << b;
                }
            }
            idx &= mask;
            int rgba = idx < m.palette.length ? m.palette[idx] : 0;
            int argb = (rgba & 0xFF) << 24 | (rgba >>> 8);   // RGBA -> ARGB
            img.setRGB(i % m.width, i / m.width, argb);
        }
        return img;
    }
}
