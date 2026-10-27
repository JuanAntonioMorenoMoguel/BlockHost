package com.blockhost.core;

import com.blockhost.core.providers.FabricProvider;
import com.blockhost.core.providers.PaperProvider;
import com.blockhost.core.providers.ServerProvider;
import com.blockhost.core.providers.VanillaProvider;

public enum ServerType {

    VANILLA("Vanilla", "Servidor oficial de Mojang, sin mods ni plugins."),
    PAPER("Paper", "Optimizado y con soporte de plugins. Recomendado sin mods."),
    FABRIC("Fabric", "Para jugar con mods. Los jugadores necesitan Fabric y los mismos mods.");

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
        };
    }

    public boolean supportsMods() { return this == FABRIC; }

    public boolean supportsPlugins() { return this == PAPER; }
}
