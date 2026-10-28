package com.blockhost.core.mods;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lectores mínimos de los formatos en que los mods y plugins declaran sus datos: el TOML de Forge/NeoForge
 * (mods.toml) y el YAML de Bukkit/Paper (plugin.yml). Solo lo que esos ficheros usan en la práctica; lo que no
 * se entiende se ignora en vez de fallar.
 */
final class MetaFormats {

    private MetaFormats() { }

    /** Una tabla de TOML: "" para las claves de arriba, "mods" para [[mods]], "dependencies.x" para [[dependencies.x]]... */
    record TomlTable(String name, Map<String, Object> values) { }

    /** Tablas en el orden del fichero. Los valores son String, Boolean, Long o List de ellos. */
    static List<TomlTable> toml(String text) {
        List<TomlTable> out = new ArrayList<>();
        TomlTable current = new TomlTable("", new LinkedHashMap<>());
        out.add(current);
        String[] lines = text.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i]).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) {
                String name = line.replaceAll("^\\[+|]+$", "").trim();
                current = new TomlTable(name, new LinkedHashMap<>());
                out.add(current);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = unquote(line.substring(0, eq).trim());
            String value = line.substring(eq + 1).trim();
            for (String delim : List.of("'''", "\"\"\"")) {
                if (value.startsWith(delim) && (value.length() < 6 || !value.endsWith(delim))) {
                    // texto de varias líneas (la descripción): se salta hasta que se cierra
                    StringBuilder b = new StringBuilder(value.substring(3));
                    while (++i < lines.length && !lines[i].contains(delim)) b.append('\n').append(lines[i]);
                    if (i < lines.length) b.append('\n').append(lines[i], 0, lines[i].indexOf(delim));
                    value = null;
                    current.values().put(key, b.toString());
                    break;
                }
            }
            if (value != null) current.values().put(key, tomlValue(value));
        }
        return out;
    }

    private static Object tomlValue(String v) {
        if (v.startsWith("'''") || v.startsWith("\"\"\"")) return v.substring(3, Math.max(3, v.length() - 3));
        if (v.startsWith("\"") || v.startsWith("'")) return unquote(v);
        if (v.equals("true") || v.equals("false")) return Boolean.parseBoolean(v);
        if (v.startsWith("[") && v.endsWith("]")) {
            List<Object> list = new ArrayList<>();
            for (String part : splitTopLevel(v.substring(1, v.length() - 1))) {
                if (!part.isBlank()) list.add(tomlValue(part.trim()));
            }
            return list;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    /** Quita un comentario "# ..." que no esté dentro de comillas. */
    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'') && t.charAt(t.length() - 1) == t.charAt(0)) {
            t = t.substring(1, t.length() - 1);
            if (s.trim().charAt(0) == '"') t = t.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return t;
    }

    /**
     * YAML por sangría: mapas, listas con "- " y listas en línea "[a, b]". Devuelve mapas anidados con
     * valores String o List. Suficiente para plugin.yml y paper-plugin.yml.
     */
    static Map<String, Object> yaml(String text) {
        Map<String, Object> root = new LinkedHashMap<>();
        // pila de (sangría, contenedor); el contenedor es un Map o una List
        List<Object[]> stack = new ArrayList<>();
        stack.add(new Object[]{-1, root});
        String pendingKey = null; // clave sin valor: lo que venga con más sangría es suyo
        Map<String, Object> pendingOwner = null;
        int pendingIndent = -1;

        for (String raw : text.replace("\r", "").replace("\t", "  ").split("\n")) {
            String noComment = stripYamlComment(raw);
            if (noComment.isBlank() || noComment.trim().equals("---")) continue;
            int indent = noComment.length() - noComment.stripLeading().length();
            String line = noComment.trim();

            if (pendingKey != null) {
                if (indent > pendingIndent || (indent == pendingIndent && line.startsWith("- "))) {
                    Object child = line.startsWith("- ") || line.equals("-") ? new ArrayList<>() : new LinkedHashMap<String, Object>();
                    pendingOwner.put(pendingKey, child);
                    stack.add(new Object[]{indent, child});
                } else {
                    pendingOwner.put(pendingKey, "");
                }
                pendingKey = null;
            }
            while (stack.size() > 1 && (int) stack.get(stack.size() - 1)[0] > indent) stack.remove(stack.size() - 1);
            Object top = stack.get(stack.size() - 1)[1];

            if (line.startsWith("- ") || line.equals("-")) {
                if (top instanceof List<?>) {
                    @SuppressWarnings("unchecked") List<Object> list = (List<Object>) top;
                    list.add(yamlScalar(line.length() > 1 ? line.substring(2) : ""));
                }
                continue;
            }
            int colon = keyColon(line);
            if (colon < 0 || !(top instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) top;
            String key = unquote(line.substring(0, colon));
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                pendingKey = key;
                pendingOwner = map;
                pendingIndent = indent;
            } else if (value.startsWith("[") && value.endsWith("]")) {
                List<Object> list = new ArrayList<>();
                for (String part : splitTopLevel(value.substring(1, value.length() - 1))) {
                    if (!part.isBlank()) list.add(yamlScalar(part));
                }
                map.put(key, list);
            } else if (value.equals("|") || value.equals(">") || value.startsWith("|-") || value.startsWith(">-")) {
                map.put(key, ""); // texto de bloque (descripciones): no se usa
                pendingKey = null;
            } else {
                map.put(key, yamlScalar(value));
            }
        }
        if (pendingKey != null) pendingOwner.put(pendingKey, "");
        return root;
    }

    private static String yamlScalar(String s) { return unquote(s.trim()); }

    /** Posición de los dos puntos que separan clave y valor ("key: value" o "key:"), fuera de comillas. */
    private static int keyColon(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == ':' && (i == line.length() - 1 || line.charAt(i + 1) == ' ')) return i;
        }
        return -1;
    }

    private static String stripYamlComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) return line.substring(0, i);
        }
        return line;
    }
}
