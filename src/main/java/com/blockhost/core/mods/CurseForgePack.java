package com.blockhost.core.mods;

import com.blockhost.core.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Modpacks de CurseForge (.zip con manifest.json). El manifiesto solo lista ids de proyecto y de fichero,
 * así que se resuelven con la API para obtener nombre, enlace de descarga y huella, y se convierten al
 * mismo formato que un .mrpack.
 */
public final class CurseForgePack {

    private static final long CLASS_MODS = 6;

    private CurseForgePack() { }

    /** @param fileRefs pares {projectID, fileID} de los ficheros obligatorios */
    record Manifest(String name, String version, String mcVersion, Map<String, String> loaders,
                    List<long[]> fileRefs, String overrides) { }

    public static Mrpack.Pack read(Path zipFile) throws Exception {
        Manifest m;
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry e = zip.getEntry("manifest.json");
            if (e == null) throw new IOException("No es un modpack de CurseForge: falta manifest.json");
            m = parseManifest(new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
        }
        if (!CurseForge.INSTANCE.available()) {
            throw new IOException("Para instalar modpacks de CurseForge hace falta su clave de API (búscala en Ajustes de CurseForge).");
        }

        List<Long> fileIds = new ArrayList<>();
        List<Long> modIds = new ArrayList<>();
        for (long[] r : m.fileRefs()) { modIds.add(r[0]); fileIds.add(r[1]); }
        Map<String, Long> classes = CurseForge.INSTANCE.classIds(modIds);

        List<Mrpack.PackFile> files = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (ModRepository.Version v : CurseForge.INSTANCE.files(fileIds)) {
            // El servidor solo necesita mods: paquetes de recursos y shaders son cosa de cada jugador.
            if (classes.getOrDefault(v.projectId(), CLASS_MODS) != CLASS_MODS || v.file() == null) continue;
            if (v.file().url() == null) {
                unavailable.add(v.versionNumber() != null ? v.versionNumber() : v.file().fileName());
                continue;
            }
            files.add(new Mrpack.PackFile("mods/" + v.file().fileName(), v.file().sha1(), null,
                    List.of(v.file().url()), v.file().size(), "required"));
        }
        return toPack(m, files, unavailable);
    }

    static Mrpack.Pack toPack(Manifest m, List<Mrpack.PackFile> files, List<String> unavailable) {
        return new Mrpack.Pack(m.name(), m.version(), null, m.mcVersion(), m.loaders(), files,
                List.of(m.overrides()), unavailable);
    }

    static Manifest parseManifest(String json) throws IOException {
        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(json));
        } catch (IllegalArgumentException e) {
            throw new IOException("manifest.json ilegible: " + e.getMessage());
        }
        String mc = Json.str(Json.path(m, "minecraft", "version"));
        if (mc == null) throw new IOException("El modpack no indica su versión de Minecraft");

        // "fabric-0.16.10" -> fabric-loader = 0.16.10 (mismas claves que un .mrpack)
        Map<String, String> loaders = new LinkedHashMap<>();
        if (Json.path(m, "minecraft", "modLoaders") instanceof List<?> l) {
            for (Object o : l) {
                String id = Json.str(Json.path(o, "id"));
                if (id == null || !id.contains("-")) continue;
                String kind = id.substring(0, id.indexOf('-'));
                String version = id.substring(id.indexOf('-') + 1);
                String key = switch (kind) {
                    case "fabric" -> "fabric-loader";
                    case "quilt" -> "quilt-loader";
                    default -> kind; // forge, neoforge
                };
                if (Json.bool(Json.path(o, "primary"), false)) loaders.clear();
                loaders.putIfAbsent(key, version);
            }
        }

        List<long[]> refs = new ArrayList<>();
        if (m.get("files") instanceof List<?> l) {
            for (Object o : l) {
                if (!Json.bool(Json.path(o, "required"), true)) continue;
                refs.add(new long[]{Json.num(Json.path(o, "projectID"), 0), Json.num(Json.path(o, "fileID"), 0)});
            }
        }
        String overrides = Json.str(m.get("overrides"));
        return new Manifest(Json.str(m.get("name")), Json.str(m.get("version")), mc, loaders, refs,
                overrides == null || overrides.isBlank() ? "overrides" : overrides);
    }
}
