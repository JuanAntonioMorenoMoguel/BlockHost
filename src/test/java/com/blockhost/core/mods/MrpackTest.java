package com.blockhost.core.mods;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MrpackTest {

    static final String INDEX = """
            {
              "formatVersion": 1, "game": "minecraft", "versionId": "1.2.0", "name": "Pack de prueba",
              "files": [
                {"path": "mods/lithium.jar", "hashes": {"sha1": "aa", "sha512": "bb"},
                 "env": {"client": "required", "server": "required"},
                 "downloads": ["https://cdn.modrinth.com/data/x/lithium.jar"], "fileSize": 10},
                {"path": "mods/minimap.jar", "hashes": {"sha1": "cc", "sha512": "dd"},
                 "env": {"client": "required", "server": "unsupported"},
                 "downloads": ["https://cdn.modrinth.com/data/y/minimap.jar"], "fileSize": 10}
              ],
              "dependencies": {"minecraft": "1.21.4", "fabric-loader": "0.16.9"}
            }""";

    @TempDir Path dir;

    @Test
    void parsesIndex() throws IOException {
        Mrpack.Pack p = Mrpack.parse(INDEX);
        assertEquals("1.21.4", p.mcVersion());
        assertEquals("fabric", p.loader());
        assertEquals("0.16.9", p.loaderVersion());
        assertEquals(2, p.files().size());
        assertEquals(1, p.serverFiles().size()); // el minimapa es solo de cliente
        assertEquals("Pack de prueba 1.2.0", p.title());
    }

    @Test
    void readsFromZipAndDetectsOtherLoaders() throws IOException {
        Path f = TestJars.jar(dir.resolve("p.mrpack"), Map.of("modrinth.index.json",
                INDEX.replace("\"fabric-loader\"", "\"neoforge\"")));
        assertEquals("neoforge", Mrpack.read(f).loader());
        Path notPack = TestJars.jar(dir.resolve("x.mrpack"), Map.of("a.txt", "a"));
        assertThrows(IOException.class, () -> Mrpack.read(notPack));
    }

    @Test
    void rejectsPathsOutsideTheServer() throws IOException {
        assertThrows(IOException.class, () -> Mrpack.safeResolve(dir, "../evil.jar"));
        assertThrows(IOException.class, () -> Mrpack.safeResolve(dir, "mods/../../evil.jar"));
        assertThrows(IOException.class, () -> Mrpack.safeResolve(dir, "/etc/passwd"));
        assertThrows(IOException.class, () -> Mrpack.safeResolve(dir, "C:\\\\Windows\\\\x"));
        assertTrue(Mrpack.safeResolve(dir, "mods/ok.jar").startsWith(dir.toAbsolutePath()));
    }

    @Test
    void onlyWhitelistedHosts() {
        assertTrue(Mrpack.allowedUrl("https://cdn.modrinth.com/data/a/b.jar"));
        assertTrue(Mrpack.allowedUrl("https://github.com/a/b/releases/download/c.jar"));
        assertTrue(Mrpack.allowedUrl("https://edge.forgecdn.net/files/1/2/c.jar"));
        assertFalse(Mrpack.allowedUrl("http://cdn.modrinth.com/data/a/b.jar"));
        assertFalse(Mrpack.allowedUrl("https://evil.example/b.jar"));
        assertFalse(Mrpack.allowedUrl("no es una url"));
    }

    @Test
    void overridesWithServerPriority() throws IOException {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("modrinth.index.json", INDEX);
        e.put("overrides/config/a.txt", "general");
        e.put("server-overrides/config/a.txt", "servidor");
        e.put("overrides/../../escape.txt", "x");
        Path f = TestJars.jar(dir.resolve("p.mrpack"), e);
        Path dest = Files.createDirectories(dir.resolve("srv"));
        Mrpack.Pack pack = Mrpack.parse(INDEX);
        assertThrows(IOException.class, () -> Mrpack.extractOverrides(pack, f, dest));

        e.remove("overrides/../../escape.txt");
        TestJars.jar(f, e);
        Mrpack.extractOverrides(pack, f, dest);
        assertEquals("servidor", Files.readString(dest.resolve("config/a.txt")));
    }
}
