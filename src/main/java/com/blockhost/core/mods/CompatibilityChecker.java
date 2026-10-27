package com.blockhost.core.mods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comprueba, como haría Fabric Loader al arrancar, que los mods activos encajan entre sí y con
 * la versión de Minecraft, del loader y de Java del servidor. Así el fallo se ve antes de iniciar.
 */
public final class CompatibilityChecker {

    public enum Severity { ERROR, WARNING }

    /**
     * @param missingId id del mod que falta (para intentar instalarlo desde Modrinth), o null
     */
    public record Issue(Severity severity, String mod, String file, String message, String missingId) {
        @Override
        public String toString() {
            return (severity == Severity.ERROR ? "✖ " : "⚠ ") + mod + ": " + message;
        }
    }

    /** Entorno del servidor. loaderVersion y javaMajor pueden ser desconocidos (null / 0): se omite esa comprobación. */
    public record Environment(String mcVersion, String loaderVersion, int javaMajor) { }

    private CompatibilityChecker() { }

    public static List<Issue> check(List<InstalledMod> mods, Environment env) {
        List<Issue> issues = new ArrayList<>();

        // Qué hay disponible: id -> versiones (y nombre bonito), incluidos jar-in-jar y alias "provides".
        Map<String, List<String>> available = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, List<InstalledMod>> topLevel = new LinkedHashMap<>();
        put(available, names, "minecraft", env.mcVersion(), "Minecraft");
        if (env.loaderVersion() != null) put(available, names, "fabricloader", env.loaderVersion(), "Fabric Loader");
        if (env.javaMajor() > 0) put(available, names, "java", String.valueOf(env.javaMajor()), "Java");

        List<InstalledMod> loaded = new ArrayList<>();
        for (InstalledMod m : mods) {
            if (!m.enabled()) continue;
            ModMetadata meta = m.meta();
            if (m.error() != null || meta == null) {
                issues.add(issue(Severity.ERROR, m, "No se puede leer el archivo: " + m.error()));
                continue;
            }
            if (meta.loader() != ModMetadata.Loader.FABRIC) {
                issues.add(issue(Severity.ERROR, m, wrongLoader(meta.loader())));
                continue;
            }
            if (meta.clientOnly()) {
                issues.add(issue(Severity.WARNING, m, "Es solo para el juego de los jugadores (cliente): el servidor "
                        + "no lo carga. Puedes quitarlo de aquí; quien lo quiera, que lo instale en su Minecraft."));
                continue;
            }
            loaded.add(m);
            topLevel.computeIfAbsent(meta.id(), k -> new ArrayList<>()).add(m);
            addRecursively(available, names, meta);
        }

        for (List<InstalledMod> same : topLevel.values()) {
            if (same.size() < 2) continue;
            for (InstalledMod m : same) {
                List<String> others = same.stream().filter(o -> o != m).map(InstalledMod::fileName).toList();
                issues.add(issue(Severity.ERROR, m, "Está repetido (también en " + String.join(", ", others)
                        + "). Deja solo una versión."));
            }
        }

        for (InstalledMod m : loaded) {
            ModMetadata meta = m.meta();
            meta.depends().forEach((id, ranges) -> {
                if (id.equals("fabricloader") && env.loaderVersion() == null) return;
                if (id.equals("java") && env.javaMajor() <= 0) return;
                List<String> have = available.get(id);
                String need = describe(ranges);
                if (have == null) {
                    issues.add(new Issue(Severity.ERROR, meta.displayName(), m.fileName(),
                            "Necesita «" + names.getOrDefault(id, id) + "»" + need + " y no está instalado.", id));
                } else if (have.stream().noneMatch(v -> VersionPredicate.matchesAny(v, ranges))) {
                    issues.add(new Issue(Severity.ERROR, meta.displayName(), m.fileName(),
                            "Necesita " + names.getOrDefault(id, id) + need + ", pero hay "
                                    + String.join(" / ", have) + ".", builtin(id) ? null : id));
                }
            });
            meta.breaks().forEach((id, ranges) -> {
                List<String> have = available.get(id);
                if (have != null && have.stream().anyMatch(v -> VersionPredicate.matchesAny(v, ranges))) {
                    issues.add(issue(Severity.ERROR, m, "Es incompatible con " + names.getOrDefault(id, id)
                            + " " + String.join(" / ", have) + ": el servidor no arrancará con los dos."));
                }
            });
            meta.conflicts().forEach((id, ranges) -> {
                List<String> have = available.get(id);
                if (have != null && have.stream().anyMatch(v -> VersionPredicate.matchesAny(v, ranges))) {
                    issues.add(issue(Severity.WARNING, m, "Tiene problemas conocidos con "
                            + names.getOrDefault(id, id) + " " + String.join(" / ", have) + "."));
                }
            });
        }
        return issues;
    }

    public static boolean hasErrors(List<Issue> issues) {
        return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
    }

    /** Problemas de after que no estaban en before (para avisar solo de lo que rompe una instalación nueva). */
    public static List<Issue> introduced(List<Issue> before, List<Issue> after) {
        List<String> old = before.stream().map(i -> i.file() + "|" + i.message()).toList();
        return after.stream().filter(i -> !old.contains(i.file() + "|" + i.message())).toList();
    }

    private static boolean builtin(String id) {
        return id.equals("minecraft") || id.equals("fabricloader") || id.equals("java");
    }

    private static void addRecursively(Map<String, List<String>> available, Map<String, String> names, ModMetadata meta) {
        if (meta.id() == null) return;
        String v = meta.version() == null ? "0" : meta.version();
        put(available, names, meta.id(), v, meta.displayName());
        for (String alias : meta.provides()) put(available, names, alias, v, meta.displayName());
        for (ModMetadata n : meta.nested()) addRecursively(available, names, n);
    }

    private static void put(Map<String, List<String>> available, Map<String, String> names,
                            String id, String version, String name) {
        available.computeIfAbsent(id, k -> new ArrayList<>()).add(version);
        names.putIfAbsent(id, name);
    }

    private static String describe(List<String> ranges) {
        if (ranges.isEmpty() || ranges.stream().allMatch(r -> r.isBlank() || r.equals("*"))) return "";
        return " (" + String.join(" o ", ranges) + ")";
    }

    private static String wrongLoader(ModMetadata.Loader l) {
        return switch (l) {
            case QUILT -> "Es un mod de Quilt: Fabric no puede cargarlo.";
            case FORGE, NEOFORGE -> "Es un mod de " + l.displayName + " y este servidor usa Fabric.";
            case BUKKIT -> "Es un plugin de Paper/Bukkit, no un mod: necesita un servidor Paper.";
            default -> "No parece un mod de Fabric (no tiene fabric.mod.json).";
        };
    }

    private static Issue issue(Severity s, InstalledMod m, String msg) {
        return new Issue(s, m.displayName(), m.fileName(), msg, null);
    }
}
