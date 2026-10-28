package com.blockhost.core.mods;

import com.blockhost.core.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lo que declara un .jar de mod o plugin: fabric.mod.json (Fabric), META-INF/mods.toml (Forge),
 * META-INF/neoforge.mods.toml (NeoForge) o plugin.yml / paper-plugin.yml (Paper). Se leen dependencias,
 * incompatibilidades y los mods que lleva incrustados (META-INF/jars en Fabric, META-INF/jarjar en Forge).
 *
 * <p>Los rangos de versión se guardan con la sintaxis de cada loader: la de Fabric ("&gt;=1.2") o la de Maven
 * de Forge ("[1.2,)"). {@link VersionPredicate} entiende las dos.
 */
public record ModMetadata(
        Loader loader,
        String id,
        String name,
        String version,
        String environment,                 // "*", "client" o "server"
        Map<String, List<String>> depends,  // id -> rangos de versión (vale cualquiera)
        Map<String, List<String>> breaks,   // si está instalado en ese rango, el juego no arranca
        Map<String, List<String>> conflicts,// funciona, pero con problemas conocidos
        List<String> provides,              // alias que también cuenta como instalado
        List<ModMetadata> nested,           // jar-in-jar
        String icon,                        // ruta del icono dentro del .jar, o null
        Set<Loader> loaders                 // todos los loaders que declara (hay .jar para Forge y NeoForge a la vez)
) {

    public enum Loader {
        FABRIC("Fabric"), QUILT("Quilt"), FORGE("Forge"), NEOFORGE("NeoForge"), BUKKIT("Bukkit/Paper (plugin)"),
        UNKNOWN("desconocido");

        public final String displayName;

        Loader(String displayName) { this.displayName = displayName; }
    }

    private static final int MAX_NESTING = 3;

    public String displayName() {
        return name != null && !name.isBlank() ? name : id != null ? id : "?";
    }

    public boolean clientOnly() { return "client".equals(environment); }

    /** ¿Lo carga un servidor de ese tipo? */
    public boolean supports(Loader l) { return loaders.contains(l); }

    /** Lee el .jar como mod de Fabric (o lo que sea, si no lo es). */
    public static ModMetadata read(Path jar) throws IOException { return read(jar, Loader.FABRIC); }

    /**
     * Lee el .jar. Si declara varios loaders, se usan los datos del preferido (el del servidor).
     * Si no es un zip válido lanza IOException.
     */
    public static ModMetadata read(Path jar, Loader prefer) throws IOException {
        try (InputStream in = Files.newInputStream(jar)) {
            return read(in, prefer, 0);
        }
    }

    static ModMetadata read(InputStream raw, Loader prefer, int depth) throws IOException {
        Map<String, String> texts = new HashMap<>();
        Map<String, byte[]> jars = new LinkedHashMap<>();
        boolean anyEntry = false;
        try (ZipInputStream zin = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                anyEntry = true;
                String n = e.getName();
                switch (n) {
                    case "fabric.mod.json", "quilt.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml",
                         "mcmod.info", "plugin.yml", "paper-plugin.yml", "META-INF/MANIFEST.MF",
                         "META-INF/jarjar/metadata.json" ->
                            texts.put(n, new String(zin.readAllBytes(), StandardCharsets.UTF_8).replace("\uFEFF", ""));
                    default -> {
                        if (depth < MAX_NESTING && n.endsWith(".jar")
                                && (n.startsWith("META-INF/jars/") || n.startsWith("META-INF/jarjar/"))) {
                            jars.put(n, zin.readAllBytes());
                        }
                    }
                }
            }
        }
        if (!anyEntry) throw new IOException("No es un archivo .jar válido");

        Set<Loader> loaders = EnumSet.noneOf(Loader.class);
        if (texts.containsKey("fabric.mod.json")) loaders.add(Loader.FABRIC);
        if (texts.containsKey("quilt.mod.json")) loaders.add(Loader.QUILT);
        if (texts.containsKey("META-INF/neoforge.mods.toml")) loaders.add(Loader.NEOFORGE);
        if (texts.containsKey("META-INF/mods.toml")) {
            // NeoForge 20.2–20.4 aún leía mods.toml; se reconoce porque depende de "neoforge"
            loaders.add(texts.get("META-INF/mods.toml").contains("\"neoforge\"") ? Loader.NEOFORGE : Loader.FORGE);
        }
        if (texts.containsKey("mcmod.info")) loaders.add(Loader.FORGE);
        if (texts.containsKey("plugin.yml") || texts.containsKey("paper-plugin.yml")) loaders.add(Loader.BUKKIT);

        Loader use = loaders.contains(prefer) ? prefer
                : loaders.stream().filter(l -> l != Loader.QUILT).findFirst().orElse(loaders.isEmpty() ? Loader.UNKNOWN : Loader.QUILT);
        try {
            return switch (use) {
                case FABRIC -> fabric(texts.get("fabric.mod.json"), jars, depth, loaders);
                case FORGE, NEOFORGE -> forge(use, texts, jars, depth, loaders);
                case BUKKIT -> bukkit(texts, loaders);
                default -> unknown(use, loaders);
            };
        } catch (IllegalArgumentException ex) {
            throw new IOException("Datos del mod ilegibles: " + ex.getMessage());
        }
    }

    // ---------------- Fabric ----------------

    private static ModMetadata fabric(String fabricJson, Map<String, byte[]> jars, int depth, Set<Loader> loaders)
            throws IOException {
        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(fabricJson));
        } catch (IllegalArgumentException ex) {
            throw new IOException("fabric.mod.json ilegible: " + ex.getMessage());
        }

        List<ModMetadata> nested = new ArrayList<>();
        if (m.get("jars") instanceof List<?> list) {
            for (Object o : list) {
                byte[] bytes = jars.get(Json.str(Json.path(o, "file")));
                if (bytes == null) continue;
                try {
                    ModMetadata n = read(new ByteArrayInputStream(bytes), Loader.FABRIC, depth + 1);
                    if (n.loader() == Loader.FABRIC) nested.add(n);
                } catch (IOException ignored) {
                    // un jar incrustado roto no invalida el mod
                }
            }
        }

        String env = Json.str(m.get("environment"));
        return new ModMetadata(Loader.FABRIC,
                Json.str(m.get("id")),
                Json.str(m.get("name")),
                Json.str(m.get("version")),
                env == null ? "*" : env,
                ranges(m.get("depends")),
                ranges(m.get("breaks")),
                ranges(m.get("conflicts")),
                m.get("provides") instanceof List<?> p ? p.stream().map(String::valueOf).toList() : List.of(),
                nested,
                icon(m.get("icon")),
                loaders);
    }

    // ---------------- Forge / NeoForge ----------------

    /**
     * mods.toml: el primer [[mods]] es el mod; [[dependencies.&lt;id&gt;]] sus dependencias. Forge marca las
     * obligatorias con mandatory=true y NeoForge con type="required". Las de side="CLIENT" no cuentan en un servidor.
     */
    private static ModMetadata forge(Loader loader, Map<String, String> texts, Map<String, byte[]> jars, int depth,
                                     Set<Loader> loaders) {
        String toml = loader == Loader.NEOFORGE && texts.containsKey("META-INF/neoforge.mods.toml")
                ? texts.get("META-INF/neoforge.mods.toml") : texts.get("META-INF/mods.toml");
        if (toml == null) return unknown(loader, loaders); // solo mcmod.info: Forge muy antiguo
        List<MetaFormats.TomlTable> tables = MetaFormats.toml(toml);

        Map<String, Object> mod = tables.stream().filter(t -> t.name().equals("mods")).findFirst()
                .map(MetaFormats.TomlTable::values).orElse(Map.of());
        String id = Json.str(mod.get("modId"));
        String version = Json.str(mod.get("version"));
        if (version == null || version.contains("${")) version = manifestVersion(texts.get("META-INF/MANIFEST.MF"));
        Object logo = mod.get("logoFile");
        if (logo == null) logo = tables.get(0).values().get("logoFile");

        Map<String, List<String>> depends = new LinkedHashMap<>();
        Map<String, List<String>> breaks = new LinkedHashMap<>();
        Map<String, List<String>> conflicts = new LinkedHashMap<>();
        for (MetaFormats.TomlTable t : tables) {
            if (id == null || !t.name().equals("dependencies." + id)) continue;
            Map<String, Object> d = t.values();
            String dep = Json.str(d.get("modId"));
            if (dep == null || "CLIENT".equalsIgnoreCase(Json.str(d.get("side")))) continue;
            String range = mavenRange(Json.str(d.get("versionRange")));
            String type = Json.str(d.get("type"));
            if (type == null) type = Boolean.TRUE.equals(d.get("mandatory")) ? "required" : "optional";
            switch (type.toLowerCase()) {
                case "required" -> depends.put(dep, List.of(range));
                case "incompatible" -> breaks.put(dep, List.of(range));
                case "discouraged" -> conflicts.put(dep, List.of(range));
                default -> { } // optional
            }
        }

        // jar-in-jar de Forge: META-INF/jarjar/*.jar (las librerías incrustadas cuentan como instaladas)
        List<ModMetadata> nested = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : jars.entrySet()) {
            if (!e.getKey().startsWith("META-INF/jarjar/")) continue;
            try {
                ModMetadata n = read(new ByteArrayInputStream(e.getValue()), loader, depth + 1);
                if (n.id() != null) nested.add(n);
            } catch (IOException ignored) {
                // librería sin datos de mod
            }
        }

        // modId adicionales del mismo jar (algunos declaran varios [[mods]])
        List<String> provides = new ArrayList<>();
        tables.stream().filter(t -> t.name().equals("mods")).skip(1)
                .map(t -> Json.str(t.values().get("modId"))).filter(x -> x != null).forEach(provides::add);

        return new ModMetadata(loader, id, Json.str(mod.get("displayName")), version, "*", depends, breaks, conflicts,
                provides, nested, Json.str(logo), loaders);
    }

    /** "[47,)" se queda igual; una versión suelta en Maven es solo una preferencia ("1.2" = cualquiera). */
    private static String mavenRange(String r) {
        if (r == null || r.isBlank()) return "*";
        String t = r.trim();
        return t.startsWith("[") || t.startsWith("(") ? t : "*";
    }

    private static String manifestVersion(String manifest) {
        if (manifest == null) return null;
        for (String line : manifest.split("\r?\n")) {
            if (line.startsWith("Implementation-Version:")) return line.substring(line.indexOf(':') + 1).trim();
        }
        return null;
    }

    // ---------------- Paper / Bukkit ----------------

    /**
     * plugin.yml: name, version, depend (obligatorias) y api-version (versión mínima de Minecraft).
     * paper-plugin.yml: dependencies.server.&lt;nombre&gt; con required (por defecto true).
     */
    private static ModMetadata bukkit(Map<String, String> texts, Set<Loader> loaders) {
        boolean paper = texts.containsKey("paper-plugin.yml");
        Map<String, Object> y = MetaFormats.yaml(paper ? texts.get("paper-plugin.yml") : texts.get("plugin.yml"));
        String name = Json.str(y.get("name"));
        Map<String, List<String>> depends = new LinkedHashMap<>();
        String api = Json.str(y.get("api-version"));
        if (api != null && api.matches("\\d+(\\.\\d+)*")) depends.put("minecraft", List.of(">=" + api));
        if (paper) {
            if (Json.path(y, "dependencies", "server") instanceof Map<?, ?> server) {
                server.forEach((k, v) -> {
                    boolean required = !(v instanceof Map<?, ?> m) || !"false".equals(Json.str(m.get("required")));
                    if (required) depends.put(String.valueOf(k), List.of("*"));
                });
            }
        } else if (y.get("depend") instanceof List<?> l) {
            l.forEach(d -> depends.put(String.valueOf(d), List.of("*")));
        }
        return new ModMetadata(Loader.BUKKIT, name, name, Json.str(y.get("version")), "*", depends, Map.of(), Map.of(),
                List.of(), List.of(), null, loaders);
    }

    private static ModMetadata unknown(Loader loader, Set<Loader> loaders) {
        return new ModMetadata(loader, null, null, null, "*", Map.of(), Map.of(), Map.of(), List.of(), List.of(), null,
                loaders);
    }

    /** "icon" puede ser una ruta o {"16": "...", "128": "..."}: se elige la mayor que no pase de 128 px. */
    private static String icon(Object o) {
        if (o instanceof String s) return s.isBlank() ? null : s;
        if (!(o instanceof Map<?, ?> sizes)) return null;
        String best = null;
        int bestSize = -1;
        for (Map.Entry<?, ?> e : sizes.entrySet()) {
            int size;
            try {
                size = Integer.parseInt(String.valueOf(e.getKey()));
            } catch (NumberFormatException ex) {
                continue;
            }
            boolean better = bestSize < 0 || (size <= 128 ? size > bestSize || bestSize > 128 : size < bestSize && bestSize > 128);
            if (better) { best = String.valueOf(e.getValue()); bestSize = size; }
        }
        return best;
    }

    /** Lee un fichero de dentro del .jar (p. ej. el icono). null si no existe o pesa demasiado. */
    public static byte[] readEntry(Path jar, String name) {
        if (name == null) return null;
        String n = name.startsWith("/") ? name.substring(1) : name;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(n);
            if (e == null || e.getSize() > 1024 * 1024) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** {"minecraft": "~1.21", "fabric-api": ["&gt;=0.100", "0.90.x"]} -> id -> lista de rangos. */
    private static Map<String, List<String>> ranges(Object o) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!(o instanceof Map<?, ?> map)) return out;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            List<String> r = new ArrayList<>();
            if (e.getValue() instanceof List<?> l) l.forEach(x -> r.add(String.valueOf(x)));
            else if (e.getValue() != null) r.add(String.valueOf(e.getValue()));
            out.put(String.valueOf(e.getKey()), r);
        }
        return out;
    }
}
