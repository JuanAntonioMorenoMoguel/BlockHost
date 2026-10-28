package com.blockhost.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Localiza los servidores instalados. */
public final class ServerRegistry {

    private ServerRegistry() { }

    public static List<ServerInstance> list() {
        List<ServerInstance> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(AppPaths.servers())) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                if (!Files.exists(d.resolve(ServerInstance.FILE))) continue;
                try {
                    out.add(ServerInstance.load(d));
                } catch (Exception e) {
                    System.err.println("No se pudo cargar " + d + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("No se pudo listar servidores: " + e.getMessage());
        }
        out.sort(Comparator.comparing(s -> s.config().name().toLowerCase()));
        return out;
    }

    /** Nombre de carpeta seguro a partir del nombre visible. */
    public static String folderName(String displayName) {
        String f = displayName.trim()
                .replaceAll("[^A-Za-z0-9 _-]", "")
                .replaceAll("\\s+", "-");
        return f.isEmpty() ? "servidor" : f;
    }

    /** Borra el servidor entero: mundo, mods, copias de seguridad... No se puede deshacer. */
    public static void delete(ServerInstance inst) throws IOException {
        Path dir = inst.dir().toAbsolutePath().normalize();
        if (!dir.startsWith(AppPaths.servers().toAbsolutePath().normalize()) || !Files.exists(dir.resolve(ServerInstance.FILE))) {
            throw new IOException("No parece la carpeta de un servidor de BlockHost: " + dir);
        }
        // primero el fichero de BlockHost: si algo queda bloqueado a medias, ya no aparece como servidor roto
        Files.delete(dir.resolve(ServerInstance.FILE));
        JavaRuntimeManager.deleteRecursively(dir);
    }

    /** ¿Hay otro servidor (distinto de except) con ese nombre visible? */
    public static boolean nameTaken(String displayName, ServerInstance except) {
        for (ServerInstance s : list()) {
            if (except != null && s.dir().equals(except.dir())) continue;
            if (s.config().name().equalsIgnoreCase(displayName.trim())) return true;
        }
        return exists(displayName) && (except == null || !AppPaths.servers().resolve(folderName(displayName)).equals(except.dir()));
    }

    public static boolean exists(String displayName) {
        return Files.exists(AppPaths.servers().resolve(folderName(displayName)));
    }
}
