package com.hycon.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;

/**
 * Time-based one-time codes (RFC 6238), the six digits authenticator apps show: HMAC-SHA1 over the
 * 30-second step, the same settings every common app uses by default. A code from the step before
 * or after is accepted too, so a phone clock a little off still works.
 */
public final class Totp {

    private static final int DIGITS = 6;
    private static final int STEP_SECONDS = 30;
    /** Steps accepted either side of now. */
    private static final int WINDOW = 1;
    private static final int SECRET_BYTES = 20;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    /** The name authenticator apps list the account under. */
    private static final String ISSUER = "Hycon";

    private Totp() {
    }

    /** A new random secret in base32, what the app is given. */
    public static String newSecret() {
        byte[] b = new byte[SECRET_BYTES];
        Passwords.RANDOM.nextBytes(b);
        return encode(b);
    }

    /**
     * The link an authenticator app opens or scans: {@code otpauth://totp/Hycon:name?secret=...&issuer=Hycon},
     * Google Authenticator's key URI format. Digits, period and SHA1 are left out: they are the defaults every
     * app assumes, and a shorter link makes a smaller QR code that scans more easily.
     */
    public static String uri(String account, String secret) {
        String name = URLEncoder.encode(account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + ISSUER + ":" + name + "?secret=" + secret + "&issuer=" + ISSUER;
    }

    /**
     * The time step the code belongs to, or -1 when it's wrong. A step at or before {@code usedStep} is
     * refused, so a code someone saw over a shoulder can't be used a second time.
     */
    public static long verify(String secret, String code, long usedStep) {
        if (secret == null || code == null) {
            return -1;
        }
        String digits = code.replaceAll("\\s", "");
        if (!digits.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        byte[] key = decode(secret);
        long now = System.currentTimeMillis() / 1000 / STEP_SECONDS;
        for (long step = now - WINDOW; step <= now + WINDOW; step++) {
            if (step > usedStep && java.security.MessageDigest.isEqual(format(codeAt(key, step)).getBytes(StandardCharsets.UTF_8),
                    digits.getBytes(StandardCharsets.UTF_8))) {
                return step;
            }
        }
        return -1;
    }

    static String format(int code) {
        return String.format(Locale.ROOT, "%0" + DIGITS + "d", code);
    }

    static int codeAt(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int off = h[h.length - 1] & 0x0F;
            int bin = ((h[off] & 0x7F) << 24) | ((h[off + 1] & 0xFF) << 16) | ((h[off + 2] & 0xFF) << 8) | (h[off + 3] & 0xFF);
            return bin % 1_000_000;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 missing from this Java runtime", e);
        }
    }

    static String encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] decode(String text) {
        String s = text.replaceAll("[\\s=]", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Not base32");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }
}
