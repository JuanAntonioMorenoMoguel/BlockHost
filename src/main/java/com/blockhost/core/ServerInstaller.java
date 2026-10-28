package com.blockhost.core;

import com.blockhost.core.mods.ModManager;
import com.blockhost.core.mods.Mrpack;
import com.blockhost.core.providers.FabricProvider;
import com.blockhost.core.providers.ForgeProvider;
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
        return install(cfg, eulaAccepted, null, p);
    }

    /**
     * @param loaderVersion versión de Fabric Loader, Forge o NeoForge a instalar (la que pide un modpack);
     *                      null = la recomendada.
     */
    public ServerInstance install(ServerConfig cfg, boolean eulaAccepted, String loaderVersion, ProgressListener p)
            throws Exception {
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

            String jar;
            String loader = null;
            if (cfg.type() == ServerType.FABRIC) {
                p.log("Buscando el loader de Fabric para " + cfg.mcVersion() + "...");
                loader = loaderVersion != null ? loaderVersion : FabricProvider.latestLoader(cfg.mcVersion());
                jar = FabricProvider.installLoader(cfg.mcVersion(), loader, dir, p);
            } else if (cfg.type() == ServerType.FORGE || cfg.type() == ServerType.NEOFORGE) {
                ForgeProvider.Flavor flavor = cfg.type() == ServerType.FORGE ? ForgeProvider.Flavor.FORGE : ForgeProvider.Flavor.NEOFORGE;
                p.log("Buscando " + flavor.displayName + " para " + cfg.mcVersion() + "...");
                loader = loaderVersion != null ? loaderVersion : new ForgeProvider(flavor).latestLoader(cfg.mcVersion());
                jar = ForgeProvider.install(flavor, cfg.mcVersion(), loader, dir, java, p);
            } else {
                jar = cfg.type().provider().install(cfg.mcVersion(), dir, p);
            }

            p.onProgress("Configurando el servidor...", -1);
            Files.writeString(dir.resolve("eula.txt"),
                    "# Aceptada por el usuario en el asistente de BlockHost (https://aka.ms/MinecraftEULA)\neula=true\n",
                    StandardCharsets.UTF_8);
            writeProperties(dir, cfg);
            if (cfg.type().supportsPlugins()) Files.createDirectories(dir.resolve("plugins"));

            ServerInstance inst = new ServerInstance(dir, cfg, java, jar, loader);
            inst.save();
            p.onProgress("¡Servidor listo!", 1);
            return inst;
        } catch (Exception e) {
            // Instalación a medias: se borra para poder reintentar con el mismo nombre.
            JavaRuntimeManager.deleteRecursively(dir);
            throw e;
        }
    }

    /**
     * Crea un servidor Fabric, Forge o NeoForge con la versión de Minecraft y del loader que fija el modpack y
     * lo instala. Del cfg se usan nombre, RAM, puerto y opciones de juego; tipo y versión los decide el pack.
     */
    public ServerInstance installFromModpack(ServerConfig cfg, Path mrpack, boolean eulaAccepted, ProgressListener p)
            throws Exception {
        Mrpack.Pack pack = Mrpack.read(mrpack);
        ServerType type = ServerType.forModpackLoader(pack.loader());
        if (type == null) {
            throw new IllegalStateException("El modpack usa " + (pack.loader() == null ? "Minecraft sin mods" : pack.loader())
                    + " y BlockHost instala modpacks de Fabric, Forge y NeoForge.");
        }
        ServerConfig withPack = new ServerConfig(cfg.name(), type, pack.mcVersion(), cfg.ramMb(), cfg.port(),
                cfg.motd(), cfg.maxPlayers(), cfg.difficulty(), cfg.gamemode(), cfg.onlineMode(), cfg.pvp());
        ServerInstance inst = install(withPack, eulaAccepted, pack.loaderVersion(), p);
        try {
            new ModManager(inst).installModpack(pack, mrpack, p);
        } catch (Exception e) {
            JavaRuntimeManager.deleteRecursively(inst.dir());
            throw e;
        }
        return inst;
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
