package com.blockhost.core.mods;

import com.blockhost.core.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lo que declara un .jar de mod. Para Fabric se lee fabric.mod.json (dependencias, incompatibilidades,
 * entorno y los mods que lleva incrustados en META-INF/jars). De otros loaders solo se detecta cuál es.
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
        List<ModMetadata> nested            // jar-in-jar
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

    /** Lee el .jar. Si no es un zip válido lanza IOException; si no es de Fabric devuelve el loader detectado. */
    public static ModMetadata read(Path jar) throws IOException {
        try (InputStream in = Files.newInputStream(jar)) {
            return read(in, 0);
        }
    }

    static ModMetadata read(InputStream raw, int depth) throws IOException {
        String fabricJson = null;
        Loader other = Loader.UNKNOWN;
        Map<String, byte[]> jars = new HashMap<>();
        boolean anyEntry = false;
        try (ZipInputStream zin = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                anyEntry = true;
                String n = e.getName();
                switch (n) {
                    case "fabric.mod.json" -> fabricJson = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                    case "quilt.mod.json" -> other = prefer(other, Loader.QUILT);
                    case "META-INF/neoforge.mods.toml" -> other = prefer(other, Loader.NEOFORGE);
                    case "META-INF/mods.toml", "mcmod.info" -> other = prefer(other, Loader.FORGE);
                    case "plugin.yml", "paper-plugin.yml" -> other = prefer(other, Loader.BUKKIT);
                    default -> {
                        if (depth < MAX_NESTING && n.startsWith("META-INF/jars/") && n.endsWith(".jar")) {
                            jars.put(n, zin.readAllBytes());
                        }
                    }
                }
            }
        }
        if (!anyEntry) throw new IOException("No es un archivo .jar válido");
        if (fabricJson == null) return unknown(other);

        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(fabricJson.replace("﻿", "")));
        } catch (IllegalArgumentException ex) {
            throw new IOException("fabric.mod.json ilegible: " + ex.getMessage());
        }

        List<ModMetadata> nested = new ArrayList<>();
        if (m.get("jars") instanceof List<?> list) {
            for (Object o : list) {
                byte[] bytes = jars.get(Json.str(Json.path(o, "file")));
                if (bytes == null) continue;
                try {
                    ModMetadata n = read(new ByteArrayInputStream(bytes), depth + 1);
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
                nested);
    }

    private static Loader prefer(Loader current, Loader found) {
        // neoforge.mods.toml es más específico que mods.toml
        return current == Loader.UNKNOWN || (current == Loader.FORGE && found == Loader.NEOFORGE) ? found : current;
    }

    private static ModMetadata unknown(Loader loader) {
        return new ModMetadata(loader, null, null, null, "*", Map.of(), Map.of(), Map.of(), List.of(), List.of());
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
