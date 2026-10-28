package com.blockhost.core.mods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comprueba, como haría el loader al arrancar, que los mods (o plugins) activos encajan entre sí y con
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

    /**
     * Entorno del servidor. loaderVersion y javaMajor pueden ser desconocidos (null / 0): se omite esa comprobación.
     *
     * @param platform qué carga el servidor: FABRIC, FORGE, NEOFORGE o BUKKIT (plugins de Paper)
     */
    public record Environment(ModMetadata.Loader platform, String mcVersion, String loaderVersion, int javaMajor) {

        /** Servidor Fabric. */
        public Environment(String mcVersion, String loaderVersion, int javaMajor) {
            this(ModMetadata.Loader.FABRIC, mcVersion, loaderVersion, javaMajor);
        }

        /** Id con el que los mods piden el loader: "fabricloader", "forge" o "neoforge". */
        String loaderId() {
            return switch (platform) {
                case FABRIC -> "fabricloader";
                case FORGE -> "forge";
                case NEOFORGE -> "neoforge";
                default -> null;
            };
        }

        boolean plugins() { return platform == ModMetadata.Loader.BUKKIT; }
    }

    private CompatibilityChecker() { }

    public static List<Issue> check(List<InstalledMod> mods, Environment env) {
        List<Issue> issues = new ArrayList<>();

        // Qué hay disponible: id -> versiones (y nombre bonito), incluidos jar-in-jar y alias "provides".
        Map<String, List<String>> available = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, List<InstalledMod>> topLevel = new LinkedHashMap<>();
        put(available, names, "minecraft", env.mcVersion(), "Minecraft");
        String loaderId = env.loaderId();
        if (loaderId != null && env.loaderVersion() != null) {
            put(available, names, loaderId, env.loaderVersion(), env.platform() == ModMetadata.Loader.FABRIC
                    ? "Fabric Loader" : env.platform().displayName);
        }
        if (env.platform() == ModMetadata.Loader.FORGE || env.platform() == ModMetadata.Loader.NEOFORGE) {
            // el lenguaje de mods de Forge viene con el loader
            for (String lang : List.of("javafml", "lowcodefml", "mclanguage")) {
                put(available, names, lang, env.loaderVersion() == null ? "0" : env.loaderVersion(), env.platform().displayName);
            }
        }
        if (env.javaMajor() > 0) put(available, names, "java", String.valueOf(env.javaMajor()), "Java");

        List<InstalledMod> loaded = new ArrayList<>();
        for (InstalledMod m : mods) {
            if (!m.enabled()) continue;
            ModMetadata meta = m.meta();
            if (m.error() != null || meta == null) {
                issues.add(issue(Severity.ERROR, m, "No se puede leer el archivo: " + m.error()));
                continue;
            }
            if (!meta.supports(env.platform())) {
                issues.add(issue(Severity.ERROR, m, wrongLoader(meta.loaders(), env.platform())));
                continue;
            }
            if (meta.clientOnly()) {
                issues.add(issue(Severity.WARNING, m, "Es solo para el juego de los jugadores (cliente): el servidor "
                        + "no lo carga. Puedes quitarlo de aquí; quien lo quiera, que lo instale en su Minecraft."));
                continue;
            }
            loaded.add(m);
            topLevel.computeIfAbsent(key(meta.id(), env), k -> new ArrayList<>()).add(m);
            addRecursively(available, names, meta, env);
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
            meta.depends().forEach((rawId, ranges) -> {
                String id = key(rawId, env);
                if (id.equals(loaderId) && env.loaderVersion() == null) return;
                if (id.equals("java") && env.javaMajor() <= 0) return;
                List<String> have = available.get(id);
                String need = describe(ranges);
                if (have == null) {
                    issues.add(new Issue(Severity.ERROR, meta.displayName(), m.fileName(),
                            "Necesita «" + names.getOrDefault(id, rawId) + "»" + need + " y no está instalado.", rawId));
                } else if (have.stream().noneMatch(v -> VersionPredicate.matchesAny(v, ranges))) {
                    issues.add(new Issue(Severity.ERROR, meta.displayName(), m.fileName(),
                            "Necesita " + names.getOrDefault(id, rawId) + need + ", pero hay "
                                    + String.join(" / ", have) + ".", builtin(id) ? null : rawId));
                }
            });
            meta.breaks().forEach((rawId, ranges) -> {
                String id = key(rawId, env);
                List<String> have = available.get(id);
                if (have != null && have.stream().anyMatch(v -> VersionPredicate.matchesAny(v, ranges))) {
                    issues.add(issue(Severity.ERROR, m, "Es incompatible con " + names.getOrDefault(id, id)
                            + " " + String.join(" / ", have) + ": el servidor no arrancará con los dos."));
                }
            });
            meta.conflicts().forEach((rawId, ranges) -> {
                String id = key(rawId, env);
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
        return List.of("minecraft", "fabricloader", "forge", "neoforge", "java", "javafml").contains(id);
    }

    /** Los plugins se piden por nombre sin distinguir mayúsculas. */
    private static String key(String id, Environment env) {
        return env.plugins() && id != null ? id.toLowerCase() : id;
    }

    private static void addRecursively(Map<String, List<String>> available, Map<String, String> names, ModMetadata meta,
                                       Environment env) {
        if (meta.id() == null) return;
        String v = meta.version() == null ? "0" : meta.version();
        put(available, names, key(meta.id(), env), v, meta.displayName());
        for (String alias : meta.provides()) put(available, names, key(alias, env), v, meta.displayName());
        for (ModMetadata n : meta.nested()) addRecursively(available, names, n, env);
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

    private static String wrongLoader(java.util.Set<ModMetadata.Loader> loaders, ModMetadata.Loader server) {
        List<String> mods = loaders.stream().filter(l -> l != ModMetadata.Loader.BUKKIT && l != ModMetadata.Loader.UNKNOWN)
                .map(l -> l.displayName).toList();
        String modOf = "Es un mod de " + String.join(" y ", mods);
        if (server == ModMetadata.Loader.BUKKIT) {
            return mods.isEmpty() ? "No parece un plugin de Paper/Bukkit (no tiene plugin.yml)."
                    : modOf + ", no un plugin: Paper no carga mods.";
        }
        String serverName = server == ModMetadata.Loader.FABRIC ? "Fabric" : server.displayName;
        if (!mods.isEmpty()) return modOf + " y este servidor usa " + serverName + ".";
        if (loaders.contains(ModMetadata.Loader.BUKKIT)) return "Es un plugin de Paper/Bukkit, no un mod: necesita un servidor Paper.";
        return "No parece un mod de " + serverName + ".";
    }

    private static Issue issue(Severity s, InstalledMod m, String msg) {
        return new Issue(s, m.displayName(), m.fileName(), msg, null);
    }
}
