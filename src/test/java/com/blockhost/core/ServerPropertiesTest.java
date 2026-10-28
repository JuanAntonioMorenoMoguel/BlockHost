package com.blockhost.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPropertiesTest {

    @TempDir Path dir;

    @Test
    void keepsUnknownKeysAndSavesSorted() throws Exception {
        Files.writeString(dir.resolve("server.properties"), "zeta=1\nmotd=Hola\\u00F1\nmi-mod.opcion=x\n");
        ServerProperties p = ServerProperties.load(dir);
        assertEquals("Holañ", p.get("motd"));
        p.set("view-distance", "12");
        p.save();
        ServerProperties again = ServerProperties.load(dir);
        assertEquals("x", again.get("mi-mod.opcion"));
        assertEquals(12, again.getInt("view-distance", 10));
        String text = Files.readString(dir.resolve("server.properties"));
        assertTrue(text.indexOf("mi-mod.opcion") < text.indexOf("view-distance"));
        assertTrue(text.indexOf("view-distance") < text.indexOf("zeta"));
    }

    @Test
    void setIfAbsentDoesNotOverwrite() throws Exception {
        ServerProperties p = ServerProperties.load(dir);
        p.set("white-list", "true");
        p.setIfAbsent("white-list", "false");
        p.setIfAbsent("spawn-protection", "16");
        assertTrue(p.getBool("white-list", false));
        assertEquals("16", p.get("spawn-protection"));
    }

    @Test
    void validatesKnownKeys() throws Exception {
        assertNull(ServerProperties.validate("view-distance", "10"));
        assertNotNull(ServerProperties.validate("view-distance", "64"));
        assertNotNull(ServerProperties.validate("view-distance", "mucho"));
        assertNotNull(ServerProperties.validate("pvp", "si"));
        assertNull(ServerProperties.validate("pvp", "false"));
        assertNull(ServerProperties.validate("clave-de-un-mod", "lo que sea"));
        assertTrue(ServerProperties.load(dir).allKeys().contains("spawn-protection"));
    }

    @Test
    void newServersHaveNoWhitelist() throws Exception {
        ServerConfig cfg = new ServerConfig("Prueba", ServerType.VANILLA, "1.21.4", 2048, 25565, "motd", 10,
                "normal", "survival", true, true);
        ServerInstaller.writeProperties(dir, cfg);
        ServerProperties p = ServerProperties.load(dir);
        assertFalse(p.getBool("white-list", true));
        assertFalse(p.getBool("enforce-whitelist", true));
    }
}
