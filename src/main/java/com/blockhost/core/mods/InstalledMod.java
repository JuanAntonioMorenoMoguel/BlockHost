package com.blockhost.core.mods;

import java.nio.file.Path;

/**
 * Un .jar de la carpeta mods. Los desactivados se guardan como "x.jar.disabled" (el loader los ignora).
 *
 * @param meta   null si no se pudo leer (ver error)
 * @param source de dónde salió, si lo instaló BlockHost desde Modrinth o un modpack (null si se añadió a mano)
 */
public record InstalledMod(Path file, boolean enabled, ModMetadata meta, String error, ModSource source) {

    public static final String DISABLED_SUFFIX = ".disabled";

    public String fileName() { return file.getFileName().toString(); }

    /** Nombre del .jar sin el sufijo de desactivado: es la clave con la que se recuerda su origen. */
    public String jarName() {
        String n = fileName();
        return n.endsWith(DISABLED_SUFFIX) ? n.substring(0, n.length() - DISABLED_SUFFIX.length()) : n;
    }

    public String displayName() {
        if (meta != null && meta.id() != null) return meta.displayName();
        if (source != null && source.title() != null) return source.title();
        return jarName();
    }

    public String version() { return meta != null && meta.version() != null ? meta.version() : "?"; }
}
