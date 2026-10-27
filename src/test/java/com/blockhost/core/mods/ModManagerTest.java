package com.blockhost.core.mods;

import com.blockhost.core.ServerConfig;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModManagerTest {

    @TempDir Path dir;
    private ModManager mods;
    private ServerInstance inst;

    @BeforeEach
    void setUp() throws IOException {
        Path server = Files.createDirectories(dir.resolve("server"));
        ServerConfig cfg = new ServerConfig("Prueba", ServerType.FABRIC, "1.21.4", 4096, 25565, "motd", 10,
                "normal", "survival", true, true);
        inst = new ServerInstance(server, cfg, Path.of("/datos/runtimes/java-21/bin/java"), "fabric-server-launch.jar",
                "0.16.10");
        mods = new ModManager(inst);
    }

    @Test
    void environmentComesFromTheServer() {
        CompatibilityChecker.Environment env = mods.environment();
        assertEquals("1.21.4", env.mcVersion());
        assertEquals("0.16.10", env.loaderVersion());
        assertEquals(21, env.javaMajor());
    }

    @Test
    void loaderIsDetectedOnOlderServers() throws IOException {
        ServerInstance old = new ServerInstance(inst.dir(), inst.config(), inst.javaPath(), inst.jarName());
        Files.createDirectories(inst.dir().resolve("libraries/net/fabricmc/fabric-loader/0.16.9"));
        Files.createDirectories(inst.dir().resolve("libraries/net/fabricmc/fabric-loader/0.16.10"));
        assertEquals("0.16.10", new ModManager(old).environment().loaderVersion());
    }

    @Test
    void stagingLocalJarsReportsOnlyNewProblems() throws IOException {
        Path src = Files.createDirectories(dir.resolve("descargas"));
        // Ya instalado y roto de antes: no debe salir como problema nuevo.
        TestJars.fabricMod(mods.modsDir(), "viejo", "1.0", "\"depends\": {\"minecraft\": \"1.16.x\"}");

        Path good = TestJars.fabricMod(src, "bueno", "1.0", "\"depends\": {\"minecraft\": \">=1.21\"}");
        ModManager.Staged ok = mods.stageLocal(List.of(good));
        assertEquals(List.of(), ok.introduced());
        mods.commit(ok);
        assertEquals(2, mods.list().size());
        assertFalse(Files.exists(ok.dir()));

        Path bad = TestJars.fabricMod(src, "necesita-api", "1.0", "\"depends\": {\"fabric-api\": \"*\"}");
        ModManager.Staged staged = mods.stageLocal(List.of(bad));
        assertTrue(staged.breaksSomething());
        assertEquals(List.of("fabric-api"), ModManager.missingDependencies(staged.introduced()));
        mods.discard(staged);
        assertEquals(2, mods.list().size());
    }

    @Test
    void disableEnableAndRemove() throws IOException {
        TestJars.fabricMod(mods.modsDir(), "a", "1.0", "");
        InstalledMod a = mods.list().get(0);
        mods.setEnabled(a, false);
        InstalledMod off = mods.list().get(0);
        assertFalse(off.enabled());
        assertEquals("a-1.0.jar", off.jarName());
        assertTrue(off.fileName().endsWith(".disabled"));

        mods.setEnabled(off, true);
        assertTrue(mods.list().get(0).enabled());

        mods.remove(mods.list().get(0));
        assertEquals(List.of(), mods.list());
    }

    @Test
    void modpackMustMatchLoaderAndVersion() throws IOException {
        Mrpack.Pack ok = Mrpack.parse(MrpackTest.INDEX);
        assertEquals(List.of(), mods.modpackBlockers(ok));
        Mrpack.Pack otherMc = Mrpack.parse(MrpackTest.INDEX.replace("\"minecraft\": \"1.21.4\"", "\"minecraft\": \"1.20.1\""));
        assertEquals(1, mods.modpackBlockers(otherMc).size());
        Mrpack.Pack forge = Mrpack.parse(MrpackTest.INDEX.replace("\"fabric-loader\"", "\"forge\""));
        assertTrue(mods.modpackBlockers(forge).get(0).contains("forge"));
    }
}
