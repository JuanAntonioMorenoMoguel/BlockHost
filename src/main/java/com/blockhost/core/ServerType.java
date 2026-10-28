package com.blockhost.core;

import com.blockhost.core.mods.ModMetadata;
import com.blockhost.core.providers.FabricProvider;
import com.blockhost.core.providers.ForgeProvider;
import com.blockhost.core.providers.PaperProvider;
import com.blockhost.core.providers.ServerProvider;
import com.blockhost.core.providers.VanillaProvider;

import java.util.List;

public enum ServerType {

    VANILLA("Vanilla", "Servidor oficial de Mojang, sin mods ni plugins."),
    PAPER("Paper", "Optimizado y con soporte de plugins. Recomendado sin mods."),
    FABRIC("Fabric", "Para mods. Ligero y el primero en actualizarse. Los jugadores necesitan Fabric y los mismos mods."),
    FORGE("Forge", "El loader de mods clásico, con muchos mods y modpacks grandes. Los jugadores necesitan Forge."),
    NEOFORGE("NeoForge", "Sucesor de Forge, para mods modernos (1.20.2 o más). Los jugadores necesitan NeoForge.");

    public final String displayName;
    public final String description;

    ServerType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public ServerProvider provider() {
        return switch (this) {
            case VANILLA -> new VanillaProvider();
            case PAPER -> new PaperProvider();
            case FABRIC -> new FabricProvider();
            case FORGE -> new ForgeProvider(ForgeProvider.Flavor.FORGE);
            case NEOFORGE -> new ForgeProvider(ForgeProvider.Flavor.NEOFORGE);
        };
    }

    public boolean supportsMods() { return this == FABRIC || this == FORGE || this == NEOFORGE; }

    public boolean supportsPlugins() { return this == PAPER; }

    /** Mods o plugins: lo que se gestiona en la pestaña de complementos. */
    public boolean supportsAddons() { return supportsMods() || supportsPlugins(); }

    /** Qué tipo de .jar carga este servidor (null en Vanilla). */
    public ModMetadata.Loader addonLoader() {
        return switch (this) {
            case FABRIC -> ModMetadata.Loader.FABRIC;
            case FORGE -> ModMetadata.Loader.FORGE;
            case NEOFORGE -> ModMetadata.Loader.NEOFORGE;
            case PAPER -> ModMetadata.Loader.BUKKIT;
            case VANILLA -> null;
        };
    }

    /** Loaders con los que se busca en Modrinth. Paper carga también plugins de Spigot y Bukkit. */
    public List<String> modrinthLoaders() {
        return switch (this) {
            case FABRIC -> List.of("fabric");
            case FORGE -> List.of("forge");
            case NEOFORGE -> List.of("neoforge");
            case PAPER -> List.of("paper", "spigot", "bukkit");
            case VANILLA -> List.of();
        };
    }

    /** Carpeta de los complementos: "mods" o "plugins". */
    public String addonFolder() { return supportsPlugins() ? "plugins" : "mods"; }

    /** Tipo de proyecto en Modrinth: "mod" o "plugin". */
    public String addonProjectType() { return supportsPlugins() ? "plugin" : "mod"; }

    /** "mod" o "plugin", para los textos. */
    public String addonNoun() { return supportsPlugins() ? "plugin" : "mod"; }

    /** Servidor que instala un modpack con ese loader ("fabric", "forge", "neoforge"), o null si no se soporta. */
    public static ServerType forModpackLoader(String loader) {
        if (loader == null) return null;
        return switch (loader) {
            case "fabric" -> FABRIC;
            case "forge" -> FORGE;
            case "neoforge" -> NEOFORGE;
            default -> null;
        };
    }

    /** Id del loader en los rangos de versión de los mods ("fabricloader", "forge", "neoforge"). */
    public String loaderModId() {
        return switch (this) {
            case FABRIC -> "fabricloader";
            case FORGE -> "forge";
            case NEOFORGE -> "neoforge";
            default -> null;
        };
    }
}
