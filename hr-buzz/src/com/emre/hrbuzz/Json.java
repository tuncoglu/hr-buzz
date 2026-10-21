package com.emre.hrbuzz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal JSON reader and writer, so the coach's request building and response checking run
 * on a plain JVM (Android's org.json is stubbed out there).
 *
 * Values are {@code Map<String,Object>} (insertion ordered), {@code List<Object>},
 * {@code String}, {@code Double}, {@code Boolean} or {@code null}.
 */
public final class Json {

    private Json() {
    }

    public static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ reading

    public static Object parse(String text) throws ParseException {
        Json.Reader r = new Json.Reader(text);
        r.skipSpace();
        Object v = r.value();
        r.skipSpace();
        if (r.pos != text.length()) {
            throw new ParseException("trailing text at " + r.pos);
        }
        return v;
    }

    /** Parses and insists on an object at the top. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws ParseException {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new ParseException("not a JSON object");
        }
        return (Map<String, Object>) v;
    }

    private static final class Reader {
        final String s;
        int pos;

        Reader(String s) {
            this.s = s;
        }

        void skipSpace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object value() throws ParseException {
            if (pos >= s.length()) {
                throw new ParseException("unexpected end");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    return word("true", Boolean.TRUE);
                case 'f':
                    return word("false", Boolean.FALSE);
                case 'n':
                    return word("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw new ParseException("unexpected '" + c + "' at " + pos);
            }
        }

        private Object word(String w, Object v) throws ParseException {
            if (!s.startsWith(w, pos)) {
                throw new ParseException("bad literal at " + pos);
            }
            pos += w.length();
            return v;
        }

        private Map<String, Object> object() throws ParseException {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipSpace();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipSpace();
                if (peek() != '"') {
                    throw new ParseException("expected key at " + pos);
                }
                String key = string();
                skipSpace();
                expect(':');
                skipSpace();
                m.put(key, value());
                skipSpace();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new ParseException("expected , or } at " + (pos - 1));
                }
            }
        }

        private List<Object> array() throws ParseException {
            List<Object> list = new ArrayList<>();
            pos++;
            skipSpace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipSpace();
                list.add(value());
                skipSpace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new ParseException("expected , or ] at " + (pos - 1));
                }
            }
        }

        private String string() throws ParseException {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new ParseException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw new ParseException("bad escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(e);
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw new ParseException("bad unicode escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new ParseException("bad unicode escape");
                        }
                        pos += 4;
                        break;
                    default:
                        throw new ParseException("bad escape \\" + e);
                }
            }
        }

        private Double number() throws ParseException {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+'
                        || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new ParseException("bad number at " + start);
            }
        }

        private char peek() throws ParseException {
            if (pos >= s.length()) {
                throw new ParseException("unexpected end");
            }
            return s.charAt(pos);
        }

        private char next() throws ParseException {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char c) throws ParseException {
            if (next() != c) {
                throw new ParseException("expected '" + c + "' at " + (pos - 1));
            }
        }
    }

    // ---------------------------------------------------- typed access helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return fallback;
    }

    /** Numbers, or numeric strings (models sometimes quote them). */
    public static double num(Map<String, Object> m, String key, double fallback) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Integer || v instanceof Long) {
            sb.append(v.toString());
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(String.format(Locale.ROOT, "%.4g", d).replaceAll("\\.?0+$", ""));
            }
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v instanceof int[]) {
            sb.append('[');
            int[] a = (int[]) v;
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(a[i]);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** Small helper for building objects inline: {@code Json.map("a", 1, "b", "x")}. */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
