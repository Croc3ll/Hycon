package com.hycon.auth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * A QR code (ISO 18004) for the two-factor setup link, so a phone can scan it instead of typing the key.
 * Drawn here, not by an online QR service: the key must never leave the panel. Byte mode, error
 * correction level M (15%), the smallest version that fits, and the mask the standard scores best.
 * Black on white with the full four-module border, the way phone scanners read most reliably.
 */
public final class QrCode {

    /** Error correction codewords in each block, by version, for level M. */
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24,
            28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28};
    /** Error correction blocks, by version, for level M. */
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17,
            17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49};
    /** Level M's two bits in the format information. */
    private static final int FORMAT_LEVEL_M = 0;
    private static final int MODE_BYTE = 0b0100;
    /** Light border all round, in modules: the standard's quiet zone. */
    private static final int QUIET_ZONE = 4;
    /** Screen pixels per module, so every module is a whole number of pixels on a normal screen. */
    private static final int PIXELS_PER_MODULE = 5;

    private final int version;
    private final int size;
    private final boolean[][] dark;
    private final boolean[][] fixed;

    private QrCode(int version) {
        this.version = version;
        size = version * 4 + 17;
        dark = new boolean[size][size];
        fixed = new boolean[size][size];
    }

    /** The text as an SVG image in a {@code data:} URL, ready for an {@code <img>} on the panel. */
    public static String svgDataUri(String text) {
        String svg = encode(text).svg();
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }

    private static QrCode encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (4 + countBits(version) + bytes.length * 8 > dataCodewords(version) * 8) {
            if (++version > 40) {
                throw new IllegalArgumentException("Text too long for a QR code");
            }
        }
        QrCode qr = new QrCode(version);
        qr.drawFixedPatterns();
        qr.drawCodewords(qr.withErrorCorrection(qr.dataBits(bytes)));
        qr.applyBestMask();
        return qr;
    }

    /** Each row's dark runs as one rectangle, which keeps the image small. */
    private String svg() {
        int side = size + QUIET_ZONE * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!dark[y][x]) {
                    continue;
                }
                int run = 1;
                while (x + run < size && dark[y][x + run]) {
                    run++;
                }
                path.append('M').append(x + QUIET_ZONE).append(',').append(y + QUIET_ZONE)
                        .append('h').append(run).append("v1h-").append(run).append('z');
                x += run - 1;
            }
        }
        int pixels = side * PIXELS_PER_MODULE;
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + pixels + "\" height=\"" + pixels
                + "\" viewBox=\"0 0 " + side + " " + side + "\" shape-rendering=\"crispEdges\">"
                + "<rect width=\"" + side + "\" height=\"" + side + "\" fill=\"#fff\"/>"
                + "<path fill=\"#000\" d=\"" + path + "\"/></svg>";
    }

    // ---------- Data and error correction ----------

    /** Bits in the length field of byte mode. */
    private static int countBits(int version) {
        return version <= 9 ? 8 : 16;
    }

    /** Modules left for data and error correction once the fixed patterns are drawn. */
    private static int rawDataModules(int version) {
        int result = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int align = version / 7 + 2;
            result -= (25 * align - 10) * align - 55;
            if (version >= 7) {
                result -= 36;
            }
        }
        return result;
    }

    private static int dataCodewords(int version) {
        return rawDataModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version];
    }

    /** Mode, length, the bytes, then the terminator and the standard's 0xEC 0x11 padding. */
    private byte[] dataBits(byte[] bytes) {
        BitWriter out = new BitWriter(dataCodewords(version));
        out.append(MODE_BYTE, 4);
        out.append(bytes.length, countBits(version));
        for (byte b : bytes) {
            out.append(b & 0xFF, 8);
        }
        int capacity = out.data.length * 8;
        out.append(0, Math.min(4, capacity - out.length));
        out.append(0, (8 - out.length % 8) % 8);
        for (int pad = 0xEC; out.length < capacity; pad ^= 0xEC ^ 0x11) {
            out.append(pad, 8);
        }
        return out.data;
    }

    /** Splits the data into blocks, adds each block's Reed-Solomon codewords and interleaves them. */
    private byte[] withErrorCorrection(byte[] data) {
        int blocks = BLOCKS[version];
        int eccLength = ECC_PER_BLOCK[version];
        int raw = rawDataModules(version) / 8;
        int shortBlocks = blocks - raw % blocks;
        int shortLength = raw / blocks;
        byte[] divisor = rsDivisor(eccLength);
        byte[][] parts = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int dataLength = shortLength - eccLength + (i < shortBlocks ? 0 : 1);
            byte[] chunk = Arrays.copyOfRange(data, k, k + dataLength);
            k += dataLength;
            byte[] block = Arrays.copyOf(chunk, shortLength + 1);
            System.arraycopy(rsRemainder(chunk, divisor), 0, block, block.length - eccLength, eccLength);
            parts[i] = block;
        }
        byte[] result = new byte[raw];
        for (int i = 0, k = 0; i < parts[0].length; i++) {
            for (int j = 0; j < blocks; j++) {
                // Short blocks have no codeword at this spot: their spare byte is skipped.
                if (i != shortLength - eccLength || j >= shortBlocks) {
                    result[k++] = parts[j][i];
                }
            }
        }
        return result;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMultiply(result[j] & 0xFF, root);
                if (j + 1 < degree) {
                    result[j] ^= result[j + 1];
                }
            }
            root = gfMultiply(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) gfMultiply(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    /** Multiplication in GF(2^8) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1. */
    private static int gfMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---------- Drawing ----------

    private void set(int x, int y, boolean isDark) {
        dark[y][x] = isDark;
        fixed[y][x] = true;
    }

    private void drawFixedPatterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] align = alignmentPositions();
        int last = align.length - 1;
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j <= last; j++) {
                boolean onFinder = i == 0 && j == 0 || i == 0 && j == last || i == last && j == 0;
                if (!onFinder) {
                    drawAlignment(align[i], align[j]);
                }
            }
        }
        drawFormat(0); // reserves the spots; the real mask's bits go in later
        drawVersion();
    }

    private void drawFinder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && y >= 0 && x < size && y < size) {
                    set(x, y, ring != 2 && ring != 4);
                }
            }
        }
    }

    private void drawAlignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private int[] alignmentPositions() {
        if (version == 1) {
            return new int[0];
        }
        int count = version / 7 + 2;
        int step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2;
        int[] result = new int[count];
        result[0] = 6;
        for (int i = count - 1, pos = size - 7; i >= 1; i--, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    /** The level and mask, with their BCH check bits, in both copies. */
    private void drawFormat(int mask) {
        int data = FORMAT_LEVEL_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) {
            set(8, i, bit(bits, i));
        }
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) {
            set(14 - i, 8, bit(bits, i));
        }
        for (int i = 0; i < 8; i++) {
            set(size - 1 - i, 8, bit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            set(8, size - 15 + i, bit(bits, i));
        }
        set(8, size - 8, true);
    }

    /** Versions 7 and up carry their number, with BCH check bits, in two 6x3 blocks. */
    private void drawVersion() {
        if (version < 7) {
            return;
        }
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            int a = size - 11 + i % 3;
            int b = i / 3;
            set(a, b, bit(bits, i));
            set(b, a, bit(bits, i));
        }
    }

    /** Fills the free modules in the standard's zigzag: two columns at a time, up then down, from the right. */
    private void drawCodewords(byte[] codewords) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5; // the vertical timing line is skipped
            }
            boolean upward = ((right + 1) & 2) == 0;
            for (int step = 0; step < size; step++) {
                int y = upward ? size - 1 - step : step;
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    if (!fixed[y][x] && i < codewords.length * 8) {
                        dark[y][x] = bit(codewords[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    // ---------- Masking ----------

    private void applyBestMask() {
        int best = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(mask);
            drawFormat(mask);
            int penalty = penalty();
            if (penalty < bestPenalty) {
                best = mask;
                bestPenalty = penalty;
            }
            applyMask(mask); // XOR again undoes it
        }
        applyMask(best);
        drawFormat(best);
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!fixed[y][x] && masked(mask, x, y)) {
                    dark[y][x] = !dark[y][x];
                }
            }
        }
    }

    private static boolean masked(int mask, int x, int y) {
        return switch (mask) {
            case 0 -> (x + y) % 2 == 0;
            case 1 -> y % 2 == 0;
            case 2 -> x % 3 == 0;
            case 3 -> (x + y) % 3 == 0;
            case 4 -> (x / 3 + y / 2) % 2 == 0;
            case 5 -> x * y % 2 + x * y % 3 == 0;
            case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
            default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
        };
    }

    /** The standard's four penalty rules: long runs, 2x2 blocks, finder look-alikes and dark/light balance. */
    private int penalty() {
        int result = 0;
        for (int i = 0; i < size; i++) {
            result += linePenalty(i, true) + linePenalty(i, false);
        }
        int darkCount = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                darkCount += dark[y][x] ? 1 : 0;
                boolean c = dark[y][x];
                if (x + 1 < size && y + 1 < size && c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) {
                    result += 3;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1;
        return result + k * 10;
    }

    /** Runs of five or more alike, and dark-light-dark-dark-dark-light-dark with four light on one side. */
    private int linePenalty(int index, boolean row) {
        int result = 0;
        int run = 0;
        for (int i = 0; i < size; i++) {
            boolean c = module(index, i, row);
            run = i > 0 && c == module(index, i - 1, row) ? run + 1 : 1;
            if (run == 5) {
                result += 3;
            } else if (run > 5) {
                result++;
            }
        }
        for (int i = 0; i + 7 <= size; i++) {
            boolean finder = module(index, i, row) && !module(index, i + 1, row) && module(index, i + 2, row)
                    && module(index, i + 3, row) && module(index, i + 4, row) && !module(index, i + 5, row)
                    && module(index, i + 6, row);
            if (finder && (lightRun(index, i - 4, row) || lightRun(index, i + 7, row))) {
                result += 40;
            }
        }
        return result;
    }

    /** Four light modules from {@code start}; past the edge counts as light (the quiet zone). */
    private boolean lightRun(int index, int start, boolean row) {
        for (int i = start; i < start + 4; i++) {
            if (module(index, i, row)) {
                return false;
            }
        }
        return true;
    }

    private boolean module(int index, int i, boolean row) {
        return row ? isDark(i, index) : isDark(index, i);
    }

    private boolean isDark(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size && dark[y][x];
    }

    private static boolean bit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    /** Appends bits most significant first into a fixed number of codewords. */
    private static final class BitWriter {
        final byte[] data;
        int length;

        BitWriter(int codewords) {
            data = new byte[codewords];
        }

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                if (bit(value, i)) {
                    data[length >>> 3] |= (byte) (1 << (7 - (length & 7)));
                }
                length++;
            }
        }
    }
}
