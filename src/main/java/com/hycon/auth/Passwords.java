package com.hycon.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing with PBKDF2-HMAC-SHA256 from the JDK (no extra jar). Stored as
 * {@code pbkdf2-sha256$iterations$salt$hash}, so the iteration count can be raised later
 * without breaking existing accounts.
 */
public final class Passwords {

    /** OWASP's 2023 recommendation for PBKDF2-SHA256; about a quarter second per check. */
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    public static final int MIN_LENGTH = 10;

    static final SecureRandom RANDOM = new SecureRandom();
    /** Hash checked when the account name is unknown, so timing doesn't reveal which names exist. */
    private static final String DUMMY = hash("not-a-real-password-" + RANDOM.nextLong());

    private Passwords() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + b64(salt) + "$" + b64(derive(password, salt, ITERATIONS));
    }

    /** Checks a password; {@code stored == null} still costs a full hash (see {@link #DUMMY}). */
    public static boolean verify(String password, String stored) {
        String s = stored == null ? DUMMY : stored;
        String[] parts = s.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            boolean ok = MessageDigest.isEqual(expected, derive(password, salt, iterations));
            return ok && stored != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A random password that is easy to type: 16 characters without look-alikes (0/O, 1/l). */
    public static String generate() {
        String chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder b = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            b.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return b.toString();
    }

    /** A random URL-safe token (session ids, CSRF tokens). */
    public static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is missing from this Java runtime", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
}
