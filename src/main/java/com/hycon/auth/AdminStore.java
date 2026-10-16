package com.hycon.auth;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Panel accounts, kept in {@code admins.json} (only hashes, never passwords). Hycon writes this
 * file itself; edit accounts in the panel's Accounts tab instead of by hand.
 *
 * <p>Passwords Hycon makes up (first start, console reset) go to {@code FIRST-LOGIN.txt} rather than
 * the log, because the log is visible in the panel and may be shared. That file is deleted once the
 * account has picked its own password.
 */
public final class AdminStore {

    public record Admin(String name, Level level, String hash, boolean mustChange) {
        /** Stable id used as "banned by" in Hytale's ban list, so bans can be traced back to an account. */
        public UUID uuid() {
            return uuidFor(name);
        }
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{3,32}");

    private final Path file;
    private final Path loginFile;
    private final Map<String, Admin> admins = new ConcurrentHashMap<>();

    public AdminStore(Path dataDir) {
        this.file = dataDir.resolve("admins.json");
        this.loginFile = dataDir.resolve("FIRST-LOGIN.txt");
    }

    public static UUID uuidFor(String name) {
        return UUID.nameUUIDFromBytes(("hycon:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Loads the file; with no accounts at all, creates "owner" and returns its password file. */
    public synchronized Path load() throws IOException {
        admins.clear();
        if (Files.exists(file)) {
            Object list = JsonReader.object(Files.readString(file, StandardCharsets.UTF_8)).get("admins");
            if (list instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m && m.get("name") instanceof String n && m.get("hash") instanceof String h) {
                        Level level = Level.parse(m.get("level") instanceof String s ? s : null);
                        admins.put(key(n), new Admin(n, level == null ? Level.VIEWER : level, h, Boolean.TRUE.equals(m.get("mustChange"))));
                    }
                }
            }
        }
        if (admins.isEmpty()) {
            String pw = Passwords.generate();
            admins.put("owner", new Admin("owner", Level.OWNER, Passwords.hash(pw), true));
            save();
            writeLoginFile("owner", pw);
            return loginFile;
        }
        return null;
    }

    public Admin get(String name) {
        return name == null ? null : admins.get(key(name));
    }

    public List<Admin> all() {
        List<Admin> list = new ArrayList<>(admins.values());
        list.sort(Comparator.comparing(a -> a.name().toLowerCase(Locale.ROOT)));
        return list;
    }

    /** Creates an account with a random temporary password, which is returned (shown once). */
    public synchronized String create(String name, Level level) throws IOException {
        if (!validName(name)) {
            throw new IllegalArgumentException("Names are 3-32 letters, digits, _ . or -");
        }
        if (admins.containsKey(key(name))) {
            throw new IllegalArgumentException("That account already exists");
        }
        String pw = Passwords.generate();
        admins.put(key(name), new Admin(name, level, Passwords.hash(pw), true));
        save();
        return pw;
    }

    public synchronized void setPassword(String name, String password) throws IOException {
        Admin a = require(name);
        admins.put(key(name), new Admin(a.name(), a.level(), Passwords.hash(password), false));
        save();
        // The made-up password is no longer valid, so its file only causes confusion.
        if (Files.exists(loginFile) && Files.readString(loginFile).contains("Account:  " + a.name() + "\n")) {
            Files.deleteIfExists(loginFile);
        }
    }

    /** New random password that must be changed at next login; returns it. */
    public synchronized String resetPassword(String name) throws IOException {
        Admin a = require(name);
        String pw = Passwords.generate();
        admins.put(key(name), new Admin(a.name(), a.level(), Passwords.hash(pw), true));
        save();
        return pw;
    }

    /** Console reset: like {@link #resetPassword} but the password goes into FIRST-LOGIN.txt. */
    public synchronized Path resetToFile(String name) throws IOException {
        Admin a = get(name);
        if (a == null) {
            // Lets the console recover from a deleted or locked-out owner account.
            if (!validName(name)) {
                throw new IllegalArgumentException("Names are 3-32 letters, digits, _ . or -");
            }
            create(name, Level.OWNER);
        }
        writeLoginFile(get(name).name(), resetPassword(name));
        return loginFile;
    }

    public synchronized void setLevel(String name, Level level) throws IOException {
        Admin a = require(name);
        if (a.level() == Level.OWNER && level != Level.OWNER && owners() == 1) {
            throw new IllegalArgumentException("The last OWNER can't be demoted");
        }
        admins.put(key(name), new Admin(a.name(), level, a.hash(), a.mustChange()));
        save();
    }

    public synchronized void delete(String name) throws IOException {
        Admin a = require(name);
        if (a.level() == Level.OWNER && owners() == 1) {
            throw new IllegalArgumentException("The last OWNER can't be removed");
        }
        admins.remove(key(name));
        save();
    }

    private long owners() {
        return admins.values().stream().filter(a -> a.level() == Level.OWNER).count();
    }

    private Admin require(String name) {
        Admin a = get(name);
        if (a == null) {
            throw new IllegalArgumentException("No such account");
        }
        return a;
    }

    private void writeLoginFile(String name, String pw) throws IOException {
        String text = "Hycon panel login (delete this file after logging in; Hycon does it for you\n"
                + "once you choose a new password).\n\n"
                + "Account:  " + name + "\n"
                + "Password: " + pw + "\n";
        Files.writeString(loginFile, text, StandardCharsets.UTF_8);
    }

    /** Writes to a temp file and moves it over, so a crash mid-write never leaves a broken file. */
    private void save() throws IOException {
        Json j = new Json().beginObject().name("admins").beginArray();
        for (Admin a : all()) {
            j.beginObject().field("name", a.name()).field("level", a.level().name()).field("hash", a.hash())
                    .field("mustChange", a.mustChange()).endObject();
        }
        j.endArray().endObject();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("admins.json.tmp");
        Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
