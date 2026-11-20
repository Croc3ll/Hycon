package com.hycon.game;

import com.hycon.web.Json;
import com.hycon.web.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Punishment presets, kept in {@code presets.json}: a named offence ("Spam") with steps that get
 * harsher for repeat offenders ("warn, mute 1h, mute 1d, ban 3d"). A player's next step is the number
 * of times they were already punished for that offence; past the last step, the last one repeats.
 * Punishments made through a preset start their reason with "[Spam]", which is how they're counted.
 */
public final class Presets {

    /** One step: {@code action} is warn, kick, mute or ban; {@code duration} is "1h", "7d" or "permanent" (null for warn and kick). */
    public record Step(String action, String duration) {
        @Override
        public String toString() {
            return duration == null ? action : action + " " + duration;
        }
    }

    public record Preset(String name, List<Step> steps) {
        /** The step for a player already punished {@code done} times for this offence. */
        public Step next(int done) {
            return steps.get(Math.min(done, steps.size() - 1));
        }

        /** The reason prefix that marks punishments from this preset. */
        public String tag() {
            return "[" + name + "]";
        }
    }

    private static final int MAX_PRESETS = 30;
    private static final int MAX_STEPS = 10;
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} _'&.-]{2,32}");
    private static final Pattern DURATION = Pattern.compile("permanent|\\d{1,4}[mhdw]");

    /** Starting presets on a new server; owners change them on the Presets tab. */
    private static final String DEFAULTS = "Spam: warn, mute 30m, mute 1d, ban 1d\n"
            + "Toxicity: warn, mute 1h, mute 1d, ban 3d, ban 30d\n"
            + "Advertising: mute 1d, ban 7d, ban permanent\n"
            + "Griefing: warn, ban 1d, ban 7d, ban permanent\n"
            + "Exploiting bugs: warn, ban 3d, ban 30d\n"
            + "Hacking: ban 30d, ban permanent";

    private final Path file;
    private volatile List<Preset> presets = List.of();

    public Presets(Path dataDir) {
        this.file = dataDir.resolve("presets.json");
    }

    public synchronized void load() throws IOException {
        if (!Files.exists(file)) {
            presets = parse(DEFAULTS);
            save();
            return;
        }
        List<Preset> list = new ArrayList<>();
        if (JsonReader.object(Files.readString(file, StandardCharsets.UTF_8)).get("presets") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && m.get("name") instanceof String n && m.get("steps") instanceof String s) {
                    try {
                        list.add(new Preset(n, steps(s)));
                    } catch (IllegalArgumentException ignored) {
                        // skip a damaged preset
                    }
                }
            }
        }
        presets = List.copyOf(list);
    }

    public List<Preset> all() {
        return presets;
    }

    public Preset get(String name) {
        return presets.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    /** Replaces every preset with the text from the panel, one per line: "Name: step, step, ...". */
    public synchronized void replace(String text) throws IOException {
        presets = parse(text);
        save();
    }

    /** The presets as panel text, one per line. */
    public String text() {
        StringBuilder b = new StringBuilder();
        for (Preset p : presets) {
            b.append(p.name()).append(": ").append(String.join(", ", p.steps().stream().map(Step::toString).toList())).append('\n');
        }
        return b.toString().strip();
    }

    static List<Preset> parse(String text) {
        List<Preset> list = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("Write each preset as \"Name: step, step\" (" + line + ")");
            }
            String name = line.substring(0, colon).strip();
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Preset names are 2-32 letters, digits and spaces (" + name + ")");
            }
            if (list.stream().anyMatch(p -> p.name().equalsIgnoreCase(name))) {
                throw new IllegalArgumentException("Two presets are called " + name);
            }
            list.add(new Preset(name, steps(line.substring(colon + 1))));
        }
        if (list.size() > MAX_PRESETS) {
            throw new IllegalArgumentException("At most " + MAX_PRESETS + " presets");
        }
        return List.copyOf(list);
    }

    private static List<Step> steps(String text) {
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split(",")) {
            String[] w = raw.strip().toLowerCase(Locale.ROOT).split("\\s+");
            String action = w[0];
            boolean timed = action.equals("mute") || action.equals("ban");
            if (!timed && !action.equals("warn") && !action.equals("kick")) {
                throw new IllegalArgumentException("Steps are warn, kick, mute <time> or ban <time> (" + raw.strip() + ")");
            }
            if (timed && (w.length != 2 || !DURATION.matcher(w[1]).matches())) {
                throw new IllegalArgumentException("Give " + action + " a time like 30m, 1h, 7d, 2w or permanent (" + raw.strip() + ")");
            }
            if (!timed && w.length != 1) {
                throw new IllegalArgumentException(action + " takes no time (" + raw.strip() + ")");
            }
            steps.add(new Step(action, timed ? w[1] : null));
        }
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("Each preset needs 1-" + MAX_STEPS + " steps");
        }
        return List.copyOf(steps);
    }

    private void save() throws IOException {
        Json j = new Json().beginObject().name("presets").beginArray();
        for (Preset p : presets) {
            j.beginObject().field("name", p.name()).field("steps", String.join(", ", p.steps().stream().map(Step::toString).toList())).endObject();
        }
        j.endArray().endObject();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("presets.json.tmp");
        Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
