package com.hycon.game;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Rough player locations for the globe: each join's address is looked up in DB-IP's free
 * "IP to City Lite" database (CC BY 4.0, https://db-ip.com), kept in {@code geoip/} and refreshed
 * monthly. The lookup runs on this machine, so no player address is sent anywhere, and only the
 * city's coordinates and name are saved ({@code geo.json}); like {@link Ties}, the address itself
 * is never stored. A VPN or mobile network moves the dot, so it's a hint, not a fact.
 */
public final class GeoIp {

    public record Place(double lat, double lon, String city, String region, String country, String cc, long time) {
    }

    private static final long REFRESH_MS = TimeUnit.DAYS.toMillis(35);
    private static final byte[] META = "«ÍïMaxMind.com".getBytes(StandardCharsets.ISO_8859_1);

    private final Path file;
    private final Path dir;
    private volatile Path current;
    private final Map<UUID, Place> places = new ConcurrentHashMap<>();
    private volatile Reader reader;
    private volatile boolean dirty;

    public GeoIp(Path dataDir) {
        this.file = dataDir.resolve("geo.json");
        this.dir = dataDir.resolve("geoip");
    }

    public void load() throws IOException {
        if (Files.exists(file)) {
            JsonReader.object(Files.readString(file, StandardCharsets.UTF_8)).forEach((k, v) -> {
                if (v instanceof Map<?, ?> m && m.get("lat") instanceof Double lat && m.get("lon") instanceof Double lon) {
                    places.put(UUID.fromString(k), new Place(lat, lon, str(m, "city"), str(m, "region"), str(m, "country"), str(m, "cc"),
                            m.get("time") instanceof Double t ? t.longValue() : 0));
                }
            });
        }
        Path newest = newest();
        if (newest != null) {
            try {
                reader = new Reader(newest);
                current = newest;
            } catch (IOException | RuntimeException e) {
                reader = null; // damaged download: fetched again by refresh()
            }
        }
    }

    /** Downloads the database when missing or a month old. Slow (~60 MB); call off the game thread. */
    public void refresh() throws IOException, InterruptedException {
        if (reader != null && System.currentTimeMillis() - Files.getLastModifiedTime(current).toMillis() < REFRESH_MS) {
            return;
        }
        Files.createDirectories(dir);
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        Path gz = dir.resolve("download.mmdb.gz");
        IOException last = null;
        // This month's file appears on the 1st; early in the month it may not be up yet.
        for (YearMonth m : List.of(YearMonth.now(), YearMonth.now().minusMonths(1))) {
            Path target = dir.resolve("dbip-city-lite-" + m + ".mmdb");
            if (target.equals(current)) {
                Files.setLastModifiedTime(current, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                return; // already the newest there is
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://download.db-ip.com/free/dbip-city-lite-" + m + ".mmdb.gz"))
                    .timeout(Duration.ofMinutes(10)).header("User-Agent", "Hycon").build();
            HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(gz));
            if (res.statusCode() != 200) {
                last = new IOException("db-ip.com answered " + res.statusCode() + " for " + m);
                continue;
            }
            // Each month gets its own file name: on Windows a memory-mapped file can't be replaced or
            // deleted while open, so the new one is opened beside it and older ones go on a later start.
            Path tmp = dir.resolve("download.mmdb");
            try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(gz);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            reader = new Reader(target);
            current = target;
            cleanOld();
            return;
        }
        throw last;
    }

    private Path newest() throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var files = Files.list(dir)) {
            Path best = files.filter(f -> f.getFileName().toString().matches("dbip-city-lite-\\d{4}-\\d{2}\\.mmdb"))
                    .max(java.util.Comparator.comparing(Path::toString)).orElse(null);
            cleanOld(best);
            return best;
        }
    }

    private void cleanOld() {
        cleanOld(current);
    }

    private void cleanOld(Path keep) {
        try (var files = Files.list(dir)) {
            files.filter(f -> !f.equals(keep) && f.getFileName().toString().endsWith(".mmdb")).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // still mapped by the old reader (Windows): removed on the next start
                }
            });
        } catch (IOException ignored) {
            // nothing to tidy
        }
    }

    public boolean ready() {
        return reader != null;
    }

    /** A player joined from {@code address} (null if unknown): remember roughly where that is. */
    public void joined(UUID player, InetAddress address) {
        Reader r = reader;
        if (r == null || address == null || address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return;
        }
        try {
            if (r.lookup(address) instanceof Map<?, ?> rec && rec.get("location") instanceof Map<?, ?> loc
                    && loc.get("latitude") instanceof Number lat && loc.get("longitude") instanceof Number lon) {
                String region = rec.get("subdivisions") instanceof List<?> subs && !subs.isEmpty() ? en(subs.get(0)) : "";
                Map<?, ?> country = rec.get("country") instanceof Map<?, ?> c ? c : Map.of();
                places.put(player, new Place(lat.doubleValue(), lon.doubleValue(), en(rec.get("city")), region, en(country),
                        country.get("iso_code") instanceof String s ? s : "", System.currentTimeMillis()));
                dirty = true;
            }
        } catch (RuntimeException ignored) {
            // an address the database doesn't cover
        }
    }

    public Place place(UUID player) {
        return places.get(player);
    }

    public Map<UUID, Place> all() {
        return Map.copyOf(places);
    }

    public synchronized void saveIfChanged() {
        if (!dirty) {
            return;
        }
        dirty = false;
        Json j = new Json().beginObject();
        places.forEach((u, p) -> j.name(u.toString()).beginObject().field("lat", p.lat()).field("lon", p.lon()).field("city", p.city())
                .field("region", p.region()).field("country", p.country()).field("cc", p.cc()).field("time", p.time()).endObject());
        try {
            Path tmp = file.resolveSibling("geo.json.tmp");
            Files.writeString(tmp, j.endObject().toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            dirty = true;
        }
    }

    private static String str(Map<?, ?> m, String k) {
        return m.get(k) instanceof String s ? s : "";
    }

    private static String en(Object o) {
        return o instanceof Map<?, ?> m && m.get("names") instanceof Map<?, ?> n && n.get("en") instanceof String s ? s : "";
    }

    /** A minimal reader for MaxMind's MMDB format (the one DB-IP ships), enough for one lookup per join. */
    private static final class Reader {
        private final RandomAccessFile raf;
        private final ByteBuffer buf;
        private final int nodeCount;
        private final int recordSize;
        private final int dataStart;
        private final int ipv4Start;
        private final int ipVersion;

        Reader(Path path) throws IOException {
            raf = new RandomAccessFile(path.toFile(), "r");
            buf = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).asReadOnlyBuffer();
            int meta = -1;
            outer:
            for (int i = buf.limit() - META.length; i >= Math.max(0, buf.limit() - 128 * 1024); i--) {
                for (int k = 0; k < META.length; k++) {
                    if (buf.get(i + k) != META[k]) {
                        continue outer;
                    }
                }
                meta = i + META.length;
                break;
            }
            if (meta < 0) {
                close();
                throw new IOException("not an MMDB file");
            }
            Map<?, ?> m = (Map<?, ?>) new Decoder(meta, meta).value();
            nodeCount = ((Number) m.get("node_count")).intValue();
            recordSize = ((Number) m.get("record_size")).intValue();
            ipVersion = ((Number) m.get("ip_version")).intValue();
            dataStart = nodeCount * recordSize / 4 + 16;
            int node = 0;
            if (ipVersion == 6) {
                for (int i = 0; i < 96 && node < nodeCount; i++) {
                    node = record(node, 0);
                }
            }
            ipv4Start = node;
        }

        Object lookup(InetAddress address) {
            byte[] ip = address.getAddress();
            if (ip.length == 16 && ipVersion == 4) {
                return null;
            }
            int node = ip.length == 4 || address instanceof Inet4Address ? ipv4Start : 0;
            for (int i = 0; i < ip.length * 8 && node < nodeCount; i++) {
                node = record(node, (ip[i >> 3] >> (7 - (i & 7))) & 1);
            }
            if (node <= nodeCount) {
                return null; // not in the database
            }
            int off = dataStart + (node - nodeCount - 16);
            return new Decoder(dataStart, off).value();
        }

        private int record(int node, int bit) {
            int base = node * recordSize / 4;
            return switch (recordSize) {
                case 24 -> u(base + bit * 3, 3);
                case 28 -> bit == 0
                        ? ((buf.get(base + 3) & 0xF0) << 20) | u(base, 3)
                        : ((buf.get(base + 3) & 0x0F) << 24) | u(base + 4, 3);
                case 32 -> u(base + bit * 4, 4);
                default -> throw new IllegalStateException("record size " + recordSize);
            };
        }

        private int u(int at, int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 8) | (buf.get(at + i) & 0xFF);
            }
            return v;
        }

        void close() throws IOException {
            raf.close();
        }

        private final class Decoder {
            private final int base;
            private int pos;

            Decoder(int base, int pos) {
                this.base = base;
                this.pos = pos;
            }

            Object value() {
                int ctrl = buf.get(pos++) & 0xFF;
                int type = ctrl >>> 5;
                if (type == 1) { // pointer into the data section: read the value there, carry on here
                    int ss = (ctrl >>> 3) & 3, vvv = ctrl & 7;
                    int p = switch (ss) {
                        case 0 -> (vvv << 8) | u(pos, 1);
                        case 1 -> ((vvv << 16) | u(pos, 2)) + 2048;
                        case 2 -> ((vvv << 24) | u(pos, 3)) + 526336;
                        default -> u(pos, 4);
                    };
                    pos += ss + 1;
                    return new Decoder(base, base + p).value();
                }
                if (type == 0) {
                    type = 7 + (buf.get(pos++) & 0xFF);
                }
                int size = ctrl & 0x1F;
                if (size == 29) {
                    size = 29 + u(pos, 1);
                    pos += 1;
                } else if (size == 30) {
                    size = 285 + u(pos, 2);
                    pos += 2;
                } else if (size == 31) {
                    size = 65821 + u(pos, 3);
                    pos += 3;
                }
                switch (type) {
                    case 2 -> {
                        byte[] b = new byte[size];
                        buf.get(pos, b);
                        pos += size;
                        return new String(b, StandardCharsets.UTF_8);
                    }
                    case 3 -> {
                        double d = buf.getDouble(pos);
                        pos += 8;
                        return d;
                    }
                    case 15 -> {
                        float f = buf.getFloat(pos);
                        pos += 4;
                        return (double) f;
                    }
                    case 5, 6, 8, 9, 10 -> {
                        long v = 0;
                        for (int i = 0; i < size; i++) {
                            v = (v << 8) | (buf.get(pos + i) & 0xFF);
                        }
                        pos += size;
                        return v;
                    }
                    case 7 -> {
                        Map<String, Object> m = new HashMap<>();
                        for (int i = 0; i < size; i++) {
                            String k = (String) value();
                            m.put(k, value());
                        }
                        return m;
                    }
                    case 11 -> {
                        List<Object> l = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            l.add(value());
                        }
                        return l;
                    }
                    case 14 -> {
                        return size != 0;
                    }
                    default -> { // bytes and anything unknown: skip
                        pos += size;
                        return null;
                    }
                }
            }
        }
    }
}
