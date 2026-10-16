package com.hycon.api;

import com.hycon.web.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * What a {@link HyconPanel} fills in. Three kinds of blocks, shown in the order added:
 * <ul>
 *   <li>{@link #stat} - a number tile ("Online: 12"),</li>
 *   <li>{@link #series} - a line chart over time,</li>
 *   <li>{@link #table} - rows and columns.</li>
 * </ul>
 * All text is shown as plain text (never as HTML), so values can come straight from players.
 */
public final class PanelData {

    /** Caps per tab, so one plugin can't send the browser megabytes. */
    private static final int MAX_BLOCKS = 100, MAX_POINTS = 5000, MAX_ROWS = 1000;

    private final long rangeMillis;
    private final List<Object> blocks = new ArrayList<>();
    private int points;

    public PanelData(long rangeMillis) {
        this.rangeMillis = rangeMillis;
    }

    /** How far back the browser asked for chart data (the tab's 1h / 6h / 24h / 7d / 30d picker). */
    public long rangeMillis() {
        return rangeMillis;
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

        Table(String title, String[] columns) {
            this.title = title;
            this.columns = columns;
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
                    j.field("type", "series").field("title", s.title).field("unit", s.unit).name("lines").beginArray();
                    for (String l : s.lines) {
                        j.value(l);
                    }
                    j.endArray().name("points").beginArray();
                    for (double[] p : s.pts) {
                        j.beginArray().value(p[0]).value(p[1]).value(p[2]).endArray();
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
                }
                default -> { }
            }
            j.endObject();
        }
        j.endArray();
    }
}
