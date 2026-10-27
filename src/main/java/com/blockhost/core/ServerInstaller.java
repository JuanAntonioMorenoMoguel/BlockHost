package com.blockhost.core;

import com.blockhost.core.providers.MojangMeta;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Orquesta la instalación completa que el asistente lanza al final. */
public final class ServerInstaller {

    private final JavaRuntimeManager runtimes = new JavaRuntimeManager();

    /**
     * @param eulaAccepted el usuario marcó la casilla de la EULA de Minecraft en el asistente.
     */
    public ServerInstance install(ServerConfig cfg, boolean eulaAccepted, ProgressListener p) throws Exception {
        if (!eulaAccepted) throw new IllegalStateException("Hay que aceptar la EULA de Minecraft");

        Path dir = AppPaths.servers().resolve(ServerRegistry.folderName(cfg.name()));
        if (Files.exists(dir.resolve(ServerInstance.FILE))) {
            throw new IllegalStateException("Ya existe un servidor con ese nombre");
        }
        Files.createDirectories(dir);

        try {
            p.onProgress("Comprobando qué Java necesita Minecraft " + cfg.mcVersion() + "...", -1);
            int javaMajor = MojangMeta.requiredJava(cfg.mcVersion());
            Path java = runtimes.ensureJava(javaMajor, p);

            String jar = cfg.type().provider().install(cfg.mcVersion(), dir, p);

            p.onProgress("Configurando el servidor...", -1);
            Files.writeString(dir.resolve("eula.txt"),
                    "# Aceptada por el usuario en el asistente de BlockHost (https://aka.ms/MinecraftEULA)\neula=true\n",
                    StandardCharsets.UTF_8);
            writeProperties(dir, cfg);

            ServerInstance inst = new ServerInstance(dir, cfg, java, jar);
            inst.save();
            p.onProgress("¡Servidor listo!", 1);
            return inst;
        } catch (Exception e) {
            // Instalación a medias: se borra para poder reintentar con el mismo nombre.
            JavaRuntimeManager.deleteRecursively(dir);
            throw e;
        }
    }

    /** Escribe/actualiza server.properties conservando las claves que ya hubiera. */
    public static void writeProperties(Path dir, ServerConfig cfg) throws IOException {
        Path file = dir.resolve("server.properties");
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(r);
            }
        }
        props.setProperty("server-port", String.valueOf(cfg.port()));
        props.setProperty("motd", cfg.motd());
        props.setProperty("max-players", String.valueOf(cfg.maxPlayers()));
        props.setProperty("difficulty", cfg.difficulty());
        props.setProperty("gamemode", cfg.gamemode());
        props.setProperty("online-mode", String.valueOf(cfg.onlineMode()));
        props.setProperty("pvp", String.valueOf(cfg.pvp()));
        props.setProperty("enable-rcon", "false");
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(w, "Generado por BlockHost");
        }
    }
}
