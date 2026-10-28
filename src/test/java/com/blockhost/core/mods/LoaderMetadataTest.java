package com.blockhost.core.mods;

import com.blockhost.core.mods.CompatibilityChecker.Environment;
import com.blockhost.core.mods.CompatibilityChecker.Issue;
import com.blockhost.core.mods.ModMetadata.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mods de Forge/NeoForge y plugins de Paper: lectura de sus datos y comprobación de compatibilidad. */
class LoaderMetadataTest {

    @TempDir Path dir;

    static Path forgeMod(Path dir, String file, String id, String version, String deps) throws IOException {
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\""
                + version + "\"\ndisplayName=\"" + id.toUpperCase() + "\"\nlogoFile=\"logo.png\"\n" + deps;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("META-INF/mods.toml", toml);
        e.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 9.9.9\n");
        return TestJars.jar(dir.resolve(file), e);
    }

    static String dep(String owner, String id, String range, boolean mandatory) {
        return "[[dependencies." + owner + "]]\nmodId=\"" + id + "\"\nmandatory=" + mandatory + "\nversionRange=\""
                + range + "\"\nside=\"BOTH\"\n";
    }

    static Path plugin(Path dir, String name, String yml) throws IOException {
        return TestJars.jar(dir.resolve(name + ".jar"), Map.of("plugin.yml", "name: " + name + "\nversion: 1.0\n" + yml));
    }

    private static List<Issue> check(Environment env, Loader prefer, Path... jars) throws IOException {
        List<InstalledMod> mods = new ArrayList<>();
        for (Path j : jars) mods.add(new InstalledMod(j, true, ModMetadata.read(j, prefer), null, null));
        return CompatibilityChecker.check(mods, env);
    }

    @Test
    void readsForgeModsToml() throws IOException {
        Path jar = forgeMod(dir, "create.jar", "create", "${file.jarVersion}",
                dep("create", "forge", "[47,)", true) + dep("create", "minecraft", "[1.20.1,1.21)", true)
                        + dep("create", "jei", "[15,)", false));
        ModMetadata m = ModMetadata.read(jar, Loader.FORGE);
        assertEquals(Loader.FORGE, m.loader());
        assertEquals("create", m.id());
        assertEquals("9.9.9", m.version()); // del MANIFEST
        assertEquals("logo.png", m.icon());
        assertEquals(List.of("[47,)"), m.depends().get("forge"));
        assertEquals(List.of("[1.20.1,1.21)"), m.depends().get("minecraft"));
        assertNull(m.depends().get("jei")); // opcional
    }

    @Test
    void forgeCompatibility() throws IOException {
        Environment env = new Environment(Loader.FORGE, "1.20.1", "47.3.0", 17);
        Path ok = forgeMod(dir, "a.jar", "alpha", "1.0", dep("alpha", "forge", "[47,)", true)
                + dep("alpha", "minecraft", "[1.20.1,1.21)", true));
        assertEquals(List.of(), check(env, Loader.FORGE, ok));

        Path needsLib = forgeMod(dir, "b.jar", "beta", "1.0", dep("beta", "geckolib", "[4.0,)", true));
        List<Issue> issues = check(env, Loader.FORGE, needsLib);
        assertEquals(1, issues.size());
        assertEquals("geckolib", issues.get(0).missingId());

        Path wrongMc = forgeMod(dir, "c.jar", "gamma", "1.0", dep("gamma", "minecraft", "[1.19,1.20)", true));
        issues = check(env, Loader.FORGE, wrongMc);
        assertEquals(1, issues.size());
        assertNull(issues.get(0).missingId());
        assertTrue(issues.get(0).message().contains("Minecraft"), issues.get(0).message());

        Path fabric = TestJars.fabricMod(dir, "fabricmod", "1.0", null);
        issues = check(env, Loader.FORGE, fabric);
        assertTrue(issues.get(0).message().contains("Fabric"), issues.get(0).message());
    }

    @Test
    void neoforgeTypeRequiredAndMultiLoaderJars() throws IOException {
        String neo = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"multi\"\nversion=\"2.0\"\n"
                + "[[dependencies.multi]]\nmodId=\"neoforge\"\ntype=\"required\"\nversionRange=\"[21.1,)\"\n"
                + "[[dependencies.multi]]\nmodId=\"oldthing\"\ntype=\"incompatible\"\nversionRange=\"[1.0,2.0)\"\n";
        String forge = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"multi\"\nversion=\"2.0\"\n";
        Path jar = TestJars.jar(dir.resolve("multi.jar"), Map.of("META-INF/neoforge.mods.toml", neo, "META-INF/mods.toml", forge));

        ModMetadata asNeo = ModMetadata.read(jar, Loader.NEOFORGE);
        assertTrue(asNeo.supports(Loader.NEOFORGE) && asNeo.supports(Loader.FORGE));
        assertEquals(List.of("[21.1,)"), asNeo.depends().get("neoforge"));
        assertEquals(List.of("[1.0,2.0)"), asNeo.breaks().get("oldthing"));
        assertEquals(List.of(), check(new Environment(Loader.NEOFORGE, "1.21.1", "21.1.77", 21), Loader.NEOFORGE, jar));
        assertEquals(List.of(), check(new Environment(Loader.FORGE, "1.21.1", "52.0.1", 21), Loader.FORGE, jar));

        List<Issue> old = check(new Environment(Loader.NEOFORGE, "1.21.1", "21.0.10", 21), Loader.NEOFORGE, jar);
        assertEquals(1, old.size(), old.toString());
    }

    @Test
    void forgeJarInJarCountsAsInstalled() throws IOException {
        String libToml = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"mixinextras\"\nversion=\"0.4.1\"\n";
        byte[] lib = TestJars.bytes(Map.of("META-INF/mods.toml", libToml));
        String toml = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"host\"\nversion=\"1.0\"\n"
                + dep("host", "mixinextras", "[0.3,)", true);
        Path jar = TestJars.jar(dir.resolve("host.jar"), Map.of("META-INF/mods.toml", toml,
                "META-INF/jarjar/mixinextras-0.4.1.jar", lib));
        assertEquals(List.of(), check(new Environment(Loader.FORGE, "1.20.1", "47.3.0", 17), Loader.FORGE, jar));
    }

    @Test
    void paperPlugins() throws IOException {
        Environment env = new Environment(Loader.BUKKIT, "1.21.4", null, 21);
        Path vault = plugin(dir, "Vault", "api-version: 1.13\n");
        Path eco = plugin(dir, "EcoShop", "api-version: '1.20'\ndepend: [vault]\nsoftdepend: [PlaceholderAPI]\n");
        assertEquals(List.of(), check(env, Loader.BUKKIT, vault, eco));

        List<Issue> missing = check(env, Loader.BUKKIT, eco);
        assertEquals(1, missing.size());
        assertEquals("vault", missing.get(0).missingId());
        assertEquals(List.of("vault"), ModManager.missingDependencies(missing));

        Path future = plugin(dir, "Future", "api-version: '1.22'\n");
        List<Issue> tooNew = check(env, Loader.BUKKIT, future);
        assertEquals(1, tooNew.size());
        assertTrue(tooNew.get(0).message().contains("Minecraft"), tooNew.get(0).message());

        Path mod = TestJars.fabricMod(dir, "unmod", "1.0", null);
        assertTrue(check(env, Loader.BUKKIT, mod).get(0).message().contains("no un plugin"));
    }
}
