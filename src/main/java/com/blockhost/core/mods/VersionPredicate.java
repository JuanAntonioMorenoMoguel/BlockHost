package com.blockhost.core.mods;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rangos de versión con la sintaxis de fabric.mod.json: "*", "1.21.4", "1.21.x", "&gt;=1.21 &lt;1.22", "~1.20.1", "^2.0".
 * Varias condiciones separadas por espacios se cumplen todas; una lista de rangos basta con que se cumpla uno.
 */
public final class VersionPredicate {

    private static final Pattern SEMVER = Pattern.compile("^(\\d+(?:\\.(?:\\d+|[xX*]))*)(?:-([^+]*))?(?:\\+.*)?$");

    private VersionPredicate() { }

    /** ¿version cumple alguno de los rangos? Una lista vacía no exige nada. */
    public static boolean matchesAny(String version, List<String> ranges) {
        if (ranges == null || ranges.isEmpty()) return true;
        for (String r : ranges) if (matches(version, r)) return true;
        return false;
    }

    public static boolean matches(String version, String range) {
        if (range == null) return true;
        String r = range.trim();
        if (r.isEmpty() || r.equals("*")) return true;
        if (version == null) return false;
        if (r.startsWith("[") || r.startsWith("(")) return matchesMaven(version, r);
        for (String part : r.split("\\s+")) {
            if (!matchesSingle(version, part)) return false;
        }
        return true;
    }

    private static final Pattern MAVEN_GROUP = Pattern.compile("([\\[(])([^\\])]*)([\\])])");

    /**
     * Rangos de Maven, los de Forge/NeoForge: "[1.20.1,1.21)", "[47,)", "[1.0]", "(,2.0]". Varios grupos
     * separados por comas se cumplen con que valga uno.
     */
    static boolean matchesMaven(String version, String range) {
        Matcher m = MAVEN_GROUP.matcher(range);
        boolean any = false;
        while (m.find()) {
            any = true;
            boolean lowInc = m.group(1).equals("["), highInc = m.group(3).equals("]");
            String body = m.group(2).trim();
            if (!body.contains(",")) {
                if (compare(version, body) == 0) return true;
                continue;
            }
            String low = body.substring(0, body.indexOf(',')).trim(), high = body.substring(body.indexOf(',') + 1).trim();
            boolean ok = true;
            if (!low.isEmpty()) {
                int c = compare(version, low);
                ok = lowInc ? c >= 0 : c > 0;
            }
            if (ok && !high.isEmpty()) {
                int c = compare(version, high);
                ok = highInc ? c <= 0 : c < 0;
            }
            if (ok) return true;
        }
        return !any; // si no se entiende el rango, no se bloquea nada
    }

    private static boolean matchesSingle(String version, String p) {
        if (p.equals("*")) return true;
        String op;
        if (p.startsWith(">=") || p.startsWith("<=")) op = p.substring(0, 2);
        else if (p.startsWith(">") || p.startsWith("<") || p.startsWith("=") || p.startsWith("~") || p.startsWith("^")) {
            op = p.substring(0, 1);
        } else op = "";
        String target = p.substring(op.length()).trim();

        Version v = Version.parse(version);
        Version t = Version.parse(target);
        if (v == null || t == null) return op.isEmpty() || op.equals("=") ? version.equals(target) : false;

        int c = v.compareTo(t);
        return switch (op) {
            case ">=" -> c >= 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case "<" -> c < 0;
            // ~1.20.1: misma minor (>=1.20.1 <1.21); ^1.2: misma major (>=1.2 <2)
            case "~" -> c >= 0 && v.compareTo(t.bump(Math.min(1, t.parts.size() - 1))) < 0;
            case "^" -> c >= 0 && v.compareTo(t.bump(0)) < 0;
            default -> t.wildcardAt >= 0
                    ? c >= 0 && v.compareTo(t.bump(t.wildcardAt - 1)) < 0
                    : c == 0;
        };
    }

    /** Compara dos versiones semánticas; las que no lo son se comparan como texto. */
    public static int compare(String a, String b) {
        Version x = Version.parse(a), y = Version.parse(b);
        if (x == null || y == null) return String.valueOf(a).compareTo(String.valueOf(b));
        return x.compareTo(y);
    }

    /** 1.21.4-rc.1 → partes [1,21,4], prerelease "rc.1". Un comodín (1.21.x) corta las partes. */
    static final class Version implements Comparable<Version> {
        final List<Integer> parts;
        final String pre; // null = versión final; "" = la más baja de las pre (">=1.21-")
        final int wildcardAt;

        private Version(List<Integer> parts, String pre, int wildcardAt) {
            this.parts = parts;
            this.pre = pre;
            this.wildcardAt = wildcardAt;
        }

        static Version parse(String s) {
            if (s == null) return null;
            Matcher m = SEMVER.matcher(s.trim());
            if (!m.matches()) return null;
            List<Integer> parts = new ArrayList<>();
            int wildcard = -1;
            for (String c : m.group(1).split("\\.")) {
                if (c.equalsIgnoreCase("x") || c.equals("*")) { wildcard = parts.size(); break; }
                try {
                    parts.add(Integer.parseInt(c));
                } catch (NumberFormatException e) {
                    return null; // número desmesurado
                }
            }
            if (parts.isEmpty()) return null;
            return new Version(parts, m.group(2), wildcard);
        }

        /** Siguiente versión subiendo la parte index: (1.20.1).bump(1) = 1.21.0-'' (antes de cualquier pre de 1.21). */
        Version bump(int index) {
            int i = Math.max(0, index);
            List<Integer> p = new ArrayList<>(parts.subList(0, Math.min(i + 1, parts.size())));
            while (p.size() <= i) p.add(0);
            p.set(i, p.get(i) + 1);
            return new Version(p, "", -1);
        }

        @Override
        public int compareTo(Version o) {
            for (int i = 0; i < Math.max(parts.size(), o.parts.size()); i++) {
                int a = i < parts.size() ? parts.get(i) : 0;
                int b = i < o.parts.size() ? o.parts.get(i) : 0;
                if (a != b) return Integer.compare(a, b);
            }
            if (pre == null) return o.pre == null ? 0 : 1;
            if (o.pre == null) return -1;
            return comparePre(pre, o.pre);
        }

        private static int comparePre(String a, String b) {
            if (a.isEmpty() || b.isEmpty()) return Boolean.compare(!a.isEmpty(), !b.isEmpty());
            String[] x = a.split("\\."), y = b.split("\\.");
            for (int i = 0; i < Math.min(x.length, y.length); i++) {
                boolean xn = x[i].matches("\\d+"), yn = y[i].matches("\\d+");
                int c;
                if (xn && yn) c = compareDigits(x[i], y[i]);
                else if (xn) c = -1;
                else if (yn) c = 1;
                else c = x[i].compareTo(y[i]);
                if (c != 0) return c;
            }
            return Integer.compare(x.length, y.length);
        }

        /** Compara números de cualquier longitud sin desbordar. */
        private static int compareDigits(String a, String b) {
            a = a.replaceFirst("^0+(?=.)", "");
            b = b.replaceFirst("^0+(?=.)", "");
            return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
        }
    }
}
