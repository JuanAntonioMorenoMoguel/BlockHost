package com.blockhost.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ajustes de la app (no de un servidor) en &lt;datos&gt;/settings.json. */
public final class Settings {

    private static final String FILE = "settings.json";

    private Settings() { }

    public static synchronized String get(String key) {
        return Json.str(load().get(key));
    }

    public static synchronized void set(String key, String value) throws IOException {
        Map<String, Object> m = load();
        if (value == null || value.isBlank()) m.remove(key);
        else m.put(key, value.trim());
        Files.writeString(file(), Json.write(m), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> load() {
        try {
            Path f = file();
            if (Files.exists(f)) return Json.obj(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("No se pudieron leer los ajustes: " + e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    private static Path file() { return AppPaths.base().resolve(FILE); }
}
