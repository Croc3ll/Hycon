package com.hycon.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small recursive JSON parser into Map / List / String / Double / Boolean / null. Used for request
 * bodies (already size-capped by the web server) and Hycon's own files. {@code //} and
 * {@code /* *}{@code /} comments are skipped, so config files can explain themselves.
 */
public final class JsonReader {

    /** Deeper input is rejected, so a crafted body can't overflow the stack. */
    private static final int MAX_DEPTH = 32;

    private final String s;
    private int i;

    private JsonReader(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        JsonReader r = new JsonReader(text);
        Object v = r.read(0);
        r.ws();
        if (r.i != r.s.length()) {
            throw r.error("Unexpected text after the value");
        }
        return v;
    }

    /** Parses and requires an object at the top level. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        if (parse(text) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("Expected a JSON object");
    }

    private Object read(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("Too deeply nested");
        }
        ws();
        if (i >= s.length()) {
            throw error("Unexpected end");
        }
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        i++; // {
        ws();
        if (peek() == '}') {
            i++;
            return map;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw error("Expected a \"name\"");
            }
            String key = readString();
            ws();
            expect(':');
            map.put(key, read(depth + 1));
            ws();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("Expected , or }");
            }
        }
    }

    private List<Object> readArray(int depth) {
        List<Object> list = new ArrayList<>();
        i++; // [
        ws();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            list.add(read(depth + 1));
            ws();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("Expected , or ]");
            }
        }
    }

    private String readString() {
        i++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw error("Bad \\u escape");
                    }
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> b.append(e); // \" \\ \/
            }
        }
    }

    private Double readNumber() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw error("Unexpected character '" + s.charAt(i) + "'");
        }
        return Double.parseDouble(s.substring(start, i));
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, i)) {
            throw error("Unexpected word");
        }
        i += word.length();
        return value;
    }

    /** Skips whitespace and comments. */
    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
            } else {
                return;
            }
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() {
        if (i >= s.length()) {
            throw error("Unexpected end");
        }
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("Expected " + c);
        }
    }

    private IllegalArgumentException error(String msg) {
        int line = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) {
            if (s.charAt(k) == '\n') {
                line++;
            }
        }
        return new IllegalArgumentException(msg + " (line " + line + ")");
    }
}
