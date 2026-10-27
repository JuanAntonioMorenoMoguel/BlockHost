package com.blockhost.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lo que el usuario elige en el asistente. */
public record ServerConfig(
        String name,
        ServerType type,
        String mcVersion,
        int ramMb,
        int port,
        String motd,
        int maxPlayers,
        String difficulty, // peaceful | easy | normal | hard
        String gamemode,   // survival | creative | adventure | spectator
        boolean onlineMode,
        boolean pvp
) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type.name());
        m.put("mcVersion", mcVersion);
        m.put("ramMb", ramMb);
        m.put("port", port);
        m.put("motd", motd);
        m.put("maxPlayers", maxPlayers);
        m.put("difficulty", difficulty);
        m.put("gamemode", gamemode);
        m.put("onlineMode", onlineMode);
        m.put("pvp", pvp);
        return m;
    }

    public static ServerConfig fromMap(Map<String, Object> m) {
        return new ServerConfig(
                Json.str(m.get("name")),
                ServerType.valueOf(Json.str(m.get("type"))),
                Json.str(m.get("mcVersion")),
                (int) Json.num(m.get("ramMb"), 2048),
                (int) Json.num(m.get("port"), 25565),
                Json.str(m.getOrDefault("motd", "Un servidor de Minecraft")),
                (int) Json.num(m.get("maxPlayers"), 10),
                Json.str(m.getOrDefault("difficulty", "normal")),
                Json.str(m.getOrDefault("gamemode", "survival")),
                Json.bool(m.get("onlineMode"), true),
                Json.bool(m.get("pvp"), true));
    }

    public ServerConfig withRamMb(int ram) {
        return new ServerConfig(name, type, mcVersion, ram, port, motd, maxPlayers, difficulty, gamemode, onlineMode, pvp);
    }
}
