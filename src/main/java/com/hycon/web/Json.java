package com.hycon.web;

/**
 * Streaming JSON writer straight into one StringBuilder. A full JSON library would be a large jar
 * for the handful of small objects the panel sends.
 */
public final class Json {

    private final StringBuilder out = new StringBuilder(1024);
    /** Whether the next value in the current object/array needs a comma first. */
    private boolean needComma;

    public Json beginObject() { sep(); out.append('{'); needComma = false; return this; }
    public Json endObject() { out.append('}'); needComma = true; return this; }
    public Json beginArray() { sep(); out.append('['); needComma = false; return this; }
    public Json endArray() { out.append(']'); needComma = true; return this; }

    public Json name(String name) {
        sep();
        string(name);
        out.append(':');
        needComma = false;
        return this;
    }

    public Json value(String s) {
        sep();
        if (s == null) {
            out.append("null");
        } else {
            string(s);
        }
        needComma = true;
        return this;
    }

    public Json value(double d) {
        sep();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            out.append("null");
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            out.append((long) d); // whole numbers without ".0" keep payloads small
        } else {
            out.append(d);
        }
        needComma = true;
        return this;
    }

    public Json value(boolean b) { sep(); out.append(b); needComma = true; return this; }

    public Json field(String name, String v) { return name(name).value(v); }
    public Json field(String name, double v) { return name(name).value(v); }
    public Json field(String name, boolean v) { return name(name).value(v); }

    private void sep() {
        if (needComma) {
            out.append(',');
            needComma = false;
        }
    }

    private void string(String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                // "<" escaped so a value can never close a <script> if JSON is ever inlined in HTML.
                case '<' -> out.append("\\u003c");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
