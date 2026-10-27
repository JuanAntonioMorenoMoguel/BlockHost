package com.blockhost.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Carpetas de datos de la app según el sistema operativo. */
public final class AppPaths {

    private AppPaths() { }

    public static Path base() {
        String os = System.getProperty("os.name").toLowerCase();
        String home = System.getProperty("user.home");
        Path p;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            p = Path.of(appData != null ? appData : home, "BlockHost");
        } else if (os.contains("mac")) {
            p = Path.of(home, "Library", "Application Support", "BlockHost");
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            p = xdg != null && !xdg.isBlank() ? Path.of(xdg, "blockhost") : Path.of(home, ".local", "share", "blockhost");
        }
        return ensure(p);
    }

    public static Path servers() { return ensure(base().resolve("servers")); }

    public static Path runtimes() { return ensure(base().resolve("runtimes")); }

    public static Path cache() { return ensure(base().resolve("cache")); }

    private static Path ensure(Path p) {
        try {
            return Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear " + p, e);
        }
    }
}
