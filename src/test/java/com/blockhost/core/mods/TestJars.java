package com.blockhost.core.mods;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Crea .jar de prueba con el contenido indicado (ruta -> texto o byte[]). */
final class TestJars {

    private TestJars() { }

    static Path jar(Path file, Map<String, Object> entries) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, bytes(entries));
        return file;
    }

    static byte[] bytes(Map<String, Object> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue() instanceof byte[] b ? b : e.getValue().toString().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** Mod de Fabric con id, versión y el resto del fabric.mod.json (sin llaves externas), p. ej. "\"depends\": {...}". */
    static Path fabricMod(Path dir, String id, String version, String extra) throws IOException {
        String json = "{\"schemaVersion\": 1, \"id\": \"" + id + "\", \"version\": \"" + version + "\", \"name\": \""
                + id.toUpperCase() + "\"" + (extra == null || extra.isBlank() ? "" : ", " + extra) + "}";
        return jar(dir.resolve(id + "-" + version + ".jar"), Map.of("fabric.mod.json", json));
    }
}
