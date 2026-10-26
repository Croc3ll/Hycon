package com.hycon.api;

import com.hycon.web.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * What a {@link HyconPanel} fills in. Blocks are shown in the order added:
 * <ul>
 *   <li>{@link #stat} - a number tile ("Online: 12"),</li>
 *   <li>{@link #series} - a line or filled area chart over time, with optional outage bands;
 *       the page can switch it to a table,</li>
 *   <li>{@link #bars} - labelled horizontal bars ("time per map"),</li>
 *   <li>{@link #columns} - a small column chart ("players by hour"),</li>
 *   <li>{@link #table} - rows and columns, optionally with a button per row,</li>
 *   <li>{@link #form} - text boxes and a submit button, handled by {@link HyconPanel#action},</li>
 *   <li>{@link #nav} - a side bar of sections and items; clicking one renders the tab again with
 *       {@link #selected()} set to its id (an editor: pick a thing on the left, edit it on the right).</li>
 * </ul>
 * Consecutive {@code bars}, {@code columns} and {@code pie} blocks sit side by side.
 * All text is shown as plain text (never as HTML), so values can come straight from players.
 */
public final class PanelData {

    /** Caps per tab, so one plugin can't send the browser megabytes. */
    private static final int MAX_BLOCKS = 100, MAX_POINTS = 5000, MAX_ROWS = 1000;

    private final long rangeMillis;
    private final List<Object> blocks = new ArrayList<>();
    private int points;

    private final boolean allTime;
    private final int viewerLevel;
    private final String selected;

    public PanelData(long rangeMillis) {
        this(rangeMillis, false);
    }

    public PanelData(long rangeMillis, boolean allTime) {
        this(rangeMillis, allTime, HyconApi.VIEWER);
    }

    public PanelData(long rangeMillis, boolean allTime, int viewerLevel) {
        this(rangeMillis, allTime, viewerLevel, null);
    }

    public PanelData(long rangeMillis, boolean allTime, int viewerLevel, String selected) {
        this.rangeMillis = rangeMillis;
        this.allTime = allTime;
        this.viewerLevel = viewerLevel;
        this.selected = selected == null || selected.isBlank() ? null : selected;
    }

    /** The id of the {@link #nav} item the user picked, or null. Since API version 4. */
    public String selected() {
        return selected;
    }

    /**
     * A side bar (one per tab): sections of clickable items, shown left of the other blocks.
     * Forms and row buttons also get the picked item's id as the value {@code "_selected"}. Since API version 4.
     */
    public Nav nav(String search) {
        Nav n = new Nav(search);
        add(n);
        return n;
    }

    public static final class Nav {
        final String search;
        final List<NavSection> sections = new ArrayList<>();

        Nav(String search) {
            this.search = search;
        }

        public NavSection section(String title) {
            NavSection s = new NavSection(title);
            if (sections.size() < 10) {
                sections.add(s);
            }
            return s;
        }
    }

    public static final class NavSection {
        final String title;
        final List<String[]> items = new ArrayList<>();
        String addForm, addPrompt, removeForm, removeConfirm;

        NavSection(String title) {
            this.title = title;
        }

        /** An item; {@code sub} is small text under the label, {@code badge} is shown on the right. */
        public NavSection item(String id, String label, String sub, String badge) {
            if (items.size() < MAX_ROWS) {
                items.add(new String[] {id, label, sub == null ? "" : sub, badge == null ? "" : badge});
            }
            return this;
        }

        /** A + button: asks {@code prompt}, then calls the action {@code form} with the answer as {@code "name"}. */
        public NavSection add(String form, String prompt) {
            this.addForm = form;
            this.addPrompt = prompt;
            return this;
        }

        /** An x on each item: calls {@code form} with the item's id as {@code "id"}, after {@code confirm}. */
        public NavSection remove(String form, String confirm) {
            this.removeForm = form;
            this.removeConfirm = confirm;
            return this;
        }
    }

    /** Level of the account looking at the tab, {@link HyconApi#VIEWER} etc.; e.g. leave out forms it may not use. Since API version 4. */
    public int viewerLevel() {
        return viewerLevel;
    }

    /**
     * A form: text boxes (or drop-downs) and a submit button. Submitting calls
     * {@link HyconPanel#action} with this id and the typed values. Since API version 4.
     */
    public Form form(String id, String title, String submit) {
        Form f = new Form(id, title, submit);
        add(f);
        return f;
    }

    public static final class Form {
        final String id, title, submit;
        final List<String[]> fields = new ArrayList<>();

        Form(String id, String title, String submit) {
            this.id = id;
            this.title = title;
            this.submit = submit;
        }

        /** A text box; {@code hint} is the greyed-out example text. */
        public Form field(String name, String label, String hint) {
            if (fields.size() < 20) {
                fields.add(new String[] {name, label, hint == null ? "" : hint});
            }
            return this;
        }

        /** A drop-down of fixed choices (the first is picked to start with). */
        public Form choice(String name, String label, String... options) {
            if (fields.size() < 20) {
                String[] f = new String[3 + options.length];
                f[0] = name;
                f[1] = label;
                f[2] = "";
                System.arraycopy(options, 0, f, 3, options.length);
                fields.add(f);
            }
            return this;
        }
    }

    /** How far back the browser asked for chart data (the tab's 1h / 6h / 24h / 7d / 30d picker). */
    public long rangeMillis() {
        return rangeMillis;
    }

    /**
     * True when the browser picked "All time". {@link #rangeMillis()} then reaches back to 1970, so
     * {@code now - rangeMillis()} includes everything; use this to label things "all time".
     */
    public boolean allTime() {
        return allTime;
    }

    /** A number tile. {@code hint} is optional small text under the value. */
    public PanelData stat(String label, String value, String hint) {
        add(new Stat(label, value, hint));
        return this;
    }

    public PanelData stat(String label, String value) {
        return stat(label, value, null);
    }

    /** Starts a line chart; add points to the returned series (time in epoch milliseconds). */
    public Series series(String title, String unit) {
        Series s = new Series(title, unit);
        add(s);
        return s;
    }

    /** Starts a table with these column headings; add rows to the returned table. */
    public Table table(String title, String... columns) {
        Table t = new Table(title, columns);
        add(t);
        return t;
    }

    /**
     * A top-down heatmap of a world: each cell is a {@code cellSize}x{@code cellSize}-block square,
     * coloured by its value (e.g. minutes spent there). Cells are given in cell coordinates
     * (block coordinate / cellSize, rounded down). Since API 3.
     */
    public Heatmap heatmap(String title, int cellSize, String unit) {
        Heatmap h = new Heatmap(title, cellSize, unit);
        add(h);
        return h;
    }

    /** A pie (donut) chart, e.g. where money came from. Slices are drawn largest first. Since API 3. */
    public Pie pie(String title) {
        Pie p = new Pie(title);
        add(p);
        return p;
    }

    /** Horizontal bars, each with a label, a small grey note, a length (value) and the text shown. */
    public Bars bars(String title) {
        Bars b = new Bars(title);
        add(b);
        return b;
    }

    /** A column chart; columns are drawn in the order added, labels under every few. */
    public Columns columns(String title) {
        Columns c = new Columns(title);
        add(c);
        return c;
    }

    /** A table cell holding a time; the page shows it as a date and time in the viewer's time zone. */
    public static String time(long epochMillis) {
        return "@" + epochMillis;
    }

    /** A line of text, e.g. "Economy plugin: VaultUnlocked". */
    public PanelData note(String text) {
        add(new Note(text));
        return this;
    }

    private void add(Object block) {
        if (blocks.size() < MAX_BLOCKS) {
            blocks.add(block);
        }
    }

    public final class Series {
        final String title, unit;
        final List<double[]> pts = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        final List<long[]> gaps = new ArrayList<>();
        boolean area;
        String subtitle;

        /** Fill the area under the first line (good for counts like players online). */
        public Series area() {
            area = true;
            return this;
        }

        /** Small grey text next to the title, e.g. "average per 5 min". */
        public Series subtitle(String text) {
            subtitle = text;
            return this;
        }

        /** A red band from..to, e.g. while the server was offline. */
        public Series gap(long fromMillis, long toMillis) {
            if (gaps.size() < 200) {
                gaps.add(new long[]{fromMillis, toMillis});
            }
            return this;
        }

        Series(String title, String unit) {
            this.title = title;
            this.unit = unit;
        }

        /** Names a line within this chart; points after this call belong to it. */
        public Series line(String name) {
            lines.add(name);
            return this;
        }

        public Series point(long timeMillis, double value) {
            if (points < MAX_POINTS) {
                points++;
                pts.add(new double[]{Math.max(0, lines.size() - 1), timeMillis, value});
            }
            return this;
        }
    }

    public static final class Table {
        final String title;
        final String[] columns;
        final List<String[]> rows = new ArrayList<>();
        String buttonLabel, buttonForm, buttonConfirm;
        final List<Integer> badges = new ArrayList<>();

        Table(String title, String[] columns) {
            this.title = title;
            this.columns = columns;
        }

        /**
         * A button on every row that calls {@link HyconPanel#action} with {@code form} and the row's
         * cells keyed by column title. {@code confirm} (optional) is asked first. Since API version 4.
         */
        /** Shows a column as coloured tags ("true" green, "false" red, anything else grey). Since API version 4. */
        public Table badge(int column) {
            badges.add(column);
            return this;
        }

        public Table button(String label, String form, String confirm) {
            this.buttonLabel = label;
            this.buttonForm = form;
            this.buttonConfirm = confirm;
            return this;
        }

        public Table row(Object... cells) {
            if (rows.size() < MAX_ROWS) {
                String[] r = new String[cells.length];
                for (int i = 0; i < cells.length; i++) {
                    r[i] = cells[i] == null ? "" : String.valueOf(cells[i]);
                }
                rows.add(r);
            }
            return this;
        }
    }

    public static final class Bars {
        final String title;
        final List<String[]> items = new ArrayList<>();
        final List<Double> values = new ArrayList<>();

        Bars(String title) {
            this.title = title;
        }

        /** {@code value} sets the bar length (relative to the largest); {@code text} is shown at the end. */
        public Bars bar(String label, String note, double value, String text) {
            if (items.size() < 50) {
                items.add(new String[]{label, note == null ? "" : note, text == null ? "" : text});
                values.add(value);
            }
            return this;
        }
    }

    public static final class Heatmap {
        final String title, unit;
        final int cellSize;
        final List<double[]> cells = new ArrayList<>();
        final List<String[]> pins = new ArrayList<>();
        /** Path segments: each a list of block positions x, z, x, z, ... */
        final List<List<Integer>> path = new ArrayList<>();
        final List<String[]> marks = new ArrayList<>();
        int pathPoints;
        String world;

        /** The world's name: Hycon then draws the real world map under the heat. */
        public Heatmap world(String name) {
            this.world = name;
            return this;
        }

        Heatmap(String title, int cellSize, String unit) {
            this.title = title;
            this.cellSize = Math.max(1, cellSize);
            this.unit = unit;
        }

        public Heatmap cell(int cellX, int cellZ, double value) {
            if (value > 0 && cells.size() < 20_000) {
                cells.add(new double[]{cellX, cellZ, value});
            }
            return this;
        }

        /** Starts a new stretch of path (e.g. a new session); following {@link #step}s join it up. */
        public Heatmap segment() {
            path.add(new ArrayList<>());
            return this;
        }

        /** The next point of the current path stretch, in block coordinates. */
        public Heatmap step(int x, int z) {
            if (path.isEmpty()) {
                segment();
            }
            if (pathPoints < 6000) {
                pathPoints++;
                List<Integer> seg = path.get(path.size() - 1);
                seg.add(x);
                seg.add(z);
            }
            return this;
        }

        /**
         * A marker for something done at a place, e.g. kind "broken" with a count and a description.
         * Kinds the page knows: broken, placed, used (anything else gets a plain marker).
         */
        public Heatmap mark(String kind, int x, int z, long count, String label) {
            if (marks.size() < 3000) {
                marks.add(new String[]{kind, String.valueOf(x), String.valueOf(z), String.valueOf(count), label});
            }
            return this;
        }

        /** A labelled marker at a block position, e.g. a mine or the spawn. */
        public Heatmap pin(String label, int x, int z) {
            if (pins.size() < 100) {
                pins.add(new String[]{label, String.valueOf(x), String.valueOf(z)});
            }
            return this;
        }
    }

    public static final class Pie {
        final String title;
        final List<String> labels = new ArrayList<>();
        final List<Double> values = new ArrayList<>();
        String unit;

        Pie(String title) {
            this.title = title;
        }

        /** Shown after values in the legend, e.g. "money" or "blocks". */
        public Pie unit(String unit) {
            this.unit = unit;
            return this;
        }

        /** One slice; zero or negative values are left out. */
        public Pie slice(String label, double value) {
            if (value > 0 && labels.size() < 50) {
                labels.add(label);
                values.add(value);
            }
            return this;
        }
    }

    public static final class Columns {
        final String title;
        final List<String> labels = new ArrayList<>();
        final List<Double> values = new ArrayList<>();

        Columns(String title) {
            this.title = title;
        }

        public Columns column(String label, double value) {
            if (labels.size() < 200) {
                labels.add(label);
                values.add(value);
            }
            return this;
        }
    }

    private record Stat(String label, String value, String hint) {
    }

    private record Note(String text) {
    }

    /** Hycon's own use: the JSON the panel page draws from. */
    public void write(Json j) {
        j.beginArray();
        for (Object b : blocks) {
            j.beginObject();
            switch (b) {
                case Stat s -> j.field("type", "stat").field("label", s.label).field("value", s.value).field("hint", s.hint);
                case Note n -> j.field("type", "note").field("text", n.text);
                case Series s -> {
                    j.field("type", "series").field("title", s.title).field("unit", s.unit).field("area", s.area)
                            .field("subtitle", s.subtitle).name("gaps").beginArray();
                    for (long[] g : s.gaps) {
                        j.beginArray().value(g[0]).value(g[1]).endArray();
                    }
                    j.endArray().name("lines").beginArray();
                    for (String l : s.lines) {
                        j.value(l);
                    }
                    j.endArray().name("points").beginArray();
                    for (double[] p : s.pts) {
                        j.beginArray().value(p[0]).value(p[1]).value(p[2]).endArray();
                    }
                    j.endArray();
                }
                case Bars bs -> {
                    j.field("type", "bars").field("title", bs.title).name("items").beginArray();
                    for (int i = 0; i < bs.items.size(); i++) {
                        String[] it = bs.items.get(i);
                        j.beginArray().value(it[0]).value(it[1]).value(bs.values.get(i)).value(it[2]).endArray();
                    }
                    j.endArray();
                }
                case Heatmap h -> {
                    j.field("type", "heatmap").field("title", h.title).field("unit", h.unit).field("cell", h.cellSize)
                            .field("world", h.world).name("cells").beginArray();
                    for (double[] c : h.cells) {
                        j.beginArray().value(c[0]).value(c[1]).value(Math.round(c[2] * 100) / 100.0).endArray();
                    }
                    j.endArray().name("pins").beginArray();
                    for (String[] p : h.pins) {
                        j.beginArray().value(p[0]).value(Double.parseDouble(p[1])).value(Double.parseDouble(p[2])).endArray();
                    }
                    j.endArray().name("path").beginArray();
                    for (List<Integer> seg : h.path) {
                        if (seg.size() >= 4) {
                            j.beginArray();
                            for (int v : seg) {
                                j.value(v);
                            }
                            j.endArray();
                        }
                    }
                    j.endArray().name("marks").beginArray();
                    for (String[] m : h.marks) {
                        j.beginArray().value(m[0]).value(Double.parseDouble(m[1])).value(Double.parseDouble(m[2]))
                                .value(Double.parseDouble(m[3])).value(m[4]).endArray();
                    }
                    j.endArray();
                }
                case Pie p -> {
                    j.field("type", "pie").field("title", p.title).field("unit", p.unit).name("items").beginArray();
                    for (int i = 0; i < p.labels.size(); i++) {
                        j.beginArray().value(p.labels.get(i)).value(p.values.get(i)).endArray();
                    }
                    j.endArray();
                }
                case Columns c -> {
                    j.field("type", "columns").field("title", c.title).name("items").beginArray();
                    for (int i = 0; i < c.labels.size(); i++) {
                        j.beginArray().value(c.labels.get(i)).value(c.values.get(i)).endArray();
                    }
                    j.endArray();
                }
                case Table t -> {
                    j.field("type", "table").field("title", t.title).name("columns").beginArray();
                    for (String c : t.columns) {
                        j.value(c);
                    }
                    j.endArray().name("rows").beginArray();
                    for (String[] r : t.rows) {
                        j.beginArray();
                        for (String c : r) {
                            j.value(c);
                        }
                        j.endArray();
                    }
                    j.endArray();
                    if (t.buttonForm != null) {
                        j.field("button", t.buttonLabel).field("form", t.buttonForm).field("confirm", t.buttonConfirm);
                    }
                    if (!t.badges.isEmpty()) {
                        j.name("badges").beginArray();
                        for (int c : t.badges) {
                            j.value(c);
                        }
                        j.endArray();
                    }
                }
                case Nav n -> {
                    j.field("type", "nav").field("search", n.search).name("sections").beginArray();
                    for (NavSection s : n.sections) {
                        j.beginObject().field("title", s.title).field("add", s.addForm).field("addPrompt", s.addPrompt)
                                .field("remove", s.removeForm).field("removeConfirm", s.removeConfirm).name("items").beginArray();
                        for (String[] it : s.items) {
                            j.beginArray();
                            for (String c : it) {
                                j.value(c);
                            }
                            j.endArray();
                        }
                        j.endArray().endObject();
                    }
                    j.endArray();
                }
                case Form f -> {
                    j.field("type", "form").field("id", f.id).field("title", f.title).field("submit", f.submit).name("fields").beginArray();
                    for (String[] fl : f.fields) {
                        j.beginArray();
                        for (String c : fl) {
                            j.value(c);
                        }
                        j.endArray();
                    }
                    j.endArray();
                }
                default -> { }
            }
            j.endObject();
        }
        j.endArray();
    }
}
