package com.blockhost.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser/serializador JSON mínimo sin dependencias.
 * Objetos -> Map<String,Object>, arrays -> List<Object>, números -> Long o Double.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("Contenido extra al final");
        return v;
    }

    // ---------- helpers de acceso ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("Se esperaba un objeto JSON");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        if (o instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException("Se esperaba un array JSON");
    }

    public static String str(Object o) { return o == null ? null : o.toString(); }

    public static long num(Object o, long def) { return o instanceof Number n ? n.longValue() : def; }

    public static boolean bool(Object o, boolean def) { return o instanceof Boolean b ? b : def; }

    /** Navega por una ruta de claves de objetos: path(json, "downloads", "server", "url"). */
    public static Object path(Object root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(k);
        }
        return cur;
    }

    // ---------- parser ----------

    private Object value() {
        if (i >= s.length()) throw err("Fin inesperado");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw err("Se esperaba una clave");
            String k = string();
            ws(); expect(':'); ws();
            m.put(k, value());
            ws();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw err("Se esperaba ',' o '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = next();
            if (c == ']') return l;
            if (c != ',') throw err("Se esperaba ',' o ']'");
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw err("Escape unicode incompleto");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("Escape inválido");
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        boolean dbl = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '.' || c == 'e' || c == 'E') dbl = true;
            else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) break;
            i++;
        }
        String t = s.substring(start, i);
        if (t.isEmpty()) throw err("Valor inesperado");
        try {
            return dbl ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t);
        } catch (NumberFormatException ex) {
            return Double.parseDouble(t);
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw err("Literal inválido");
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private char next() {
        if (i >= s.length()) throw err("Fin inesperado");
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) throw err("Se esperaba '" + c + "'");
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON inválido en posición " + i + ": " + msg);
    }

    // ---------- serializador ----------

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b, 0);
        return b.toString();
    }

    private static void write(Object v, StringBuilder b, int indent) {
        if (v == null) b.append("null");
        else if (v instanceof String str) quote(str, b);
        else if (v instanceof Number || v instanceof Boolean) b.append(v);
        else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(b, indent + 2);
                quote(String.valueOf(e.getKey()), b);
                b.append(": ");
                write(e.getValue(), b, indent + 2);
                if (++n < m.size()) b.append(',');
                b.append('\n');
            }
            pad(b, indent);
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(", ");
                write(l.get(k), b, indent);
            }
            b.append(']');
        } else quote(v.toString(), b);
    }

    private static void pad(StringBuilder b, int n) { b.append(" ".repeat(n)); }

    private static void quote(String str, StringBuilder b) {
        b.append('"');
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
