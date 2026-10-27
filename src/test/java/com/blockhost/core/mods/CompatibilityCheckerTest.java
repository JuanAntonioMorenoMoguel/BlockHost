package com.blockhost.core.mods;

import com.blockhost.core.mods.CompatibilityChecker.Environment;
import com.blockhost.core.mods.CompatibilityChecker.Issue;
import com.blockhost.core.mods.CompatibilityChecker.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityCheckerTest {

    private static final Environment ENV = new Environment("1.21.4", "0.16.10", 21);

    @TempDir Path dir;

    private InstalledMod mod(Path jar) {
        try {
            return new InstalledMod(jar, true, ModMetadata.read(jar), null, null);
        } catch (IOException e) {
            return new InstalledMod(jar, true, null, e.getMessage(), null);
        }
    }

    private List<Issue> check(Path... jars) {
        List<InstalledMod> mods = new ArrayList<>();
        for (Path j : jars) mods.add(mod(j));
        return CompatibilityChecker.check(mods, ENV);
    }

    @Test
    void compatibleSetHasNoIssues() throws IOException {
        Path api = TestJars.fabricMod(dir, "fabric-api", "0.110.0+1.21.4", "\"provides\": [\"fabric\"]");
        Path sodium = TestJars.fabricMod(dir, "lithium", "0.14.3",
                "\"depends\": {\"minecraft\": \"~1.21.4\", \"fabricloader\": \">=0.15\", \"java\": \">=21\", \"fabric\": \"*\"}");
        assertEquals(List.of(), check(api, sodium));
    }

    @Test
    void wrongMinecraftVersion() throws IOException {
        Path m = TestJars.fabricMod(dir, "oldmod", "1.0", "\"depends\": {\"minecraft\": \"1.20.x\"}");
        List<Issue> issues = check(m);
        assertEquals(1, issues.size());
        assertEquals(Severity.ERROR, issues.get(0).severity());
        assertTrue(issues.get(0).message().contains("Minecraft"), issues.get(0).message());
        assertEquals(null, issues.get(0).missingId());
    }

    @Test
    void missingDependencyIsReportedWithItsId() throws IOException {
        Path m = TestJars.fabricMod(dir, "needs-api", "1.0", "\"depends\": {\"fabric-api\": \">=0.100\"}");
        List<Issue> issues = check(m);
        assertEquals(1, issues.size());
        assertEquals("fabric-api", issues.get(0).missingId());
    }

    @Test
    void dependencyTooOld() throws IOException {
        Path lib = TestJars.fabricMod(dir, "cloth-config", "10.0.0", "");
        Path m = TestJars.fabricMod(dir, "uses-cloth", "1.0", "\"depends\": {\"cloth-config\": \">=15\"}");
        List<Issue> issues = check(lib, m);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).message().contains("10.0.0"));
        assertEquals("cloth-config", issues.get(0).missingId()); // se puede intentar actualizar desde Modrinth
    }

    @Test
    void breaksIsAnError() throws IOException {
        Path a = TestJars.fabricMod(dir, "optifabric", "1.0", "");
        Path b = TestJars.fabricMod(dir, "sodium-like", "1.0", "\"breaks\": {\"optifabric\": \"*\"}");
        assertTrue(CompatibilityChecker.hasErrors(check(a, b)));
    }

    @Test
    void nestedJarsSatisfyDependencies() throws IOException {
        byte[] inner = TestJars.bytes(Map.of("fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"fabric-api-base\",\"version\":\"0.4.0\"}"));
        Path outer = TestJars.jar(dir.resolve("bundle.jar"), Map.of(
                "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"bundle\",\"version\":\"1.0\","
                        + "\"jars\":[{\"file\":\"META-INF/jars/base.jar\"}]}",
                "META-INF/jars/base.jar", inner));
        Path m = TestJars.fabricMod(dir, "consumer", "1.0", "\"depends\": {\"fabric-api-base\": \">=0.4\"}");
        assertEquals(List.of(), check(outer, m));
    }

    @Test
    void duplicatesClientOnlyAndOtherLoaders() throws IOException {
        Path a = TestJars.fabricMod(dir.resolve("a"), "dup", "1.0", "");
        Path b = TestJars.fabricMod(dir.resolve("b"), "dup", "2.0", "");
        Path client = TestJars.fabricMod(dir, "minimap", "1.0", "\"environment\": \"client\"");
        Path forge = TestJars.jar(dir.resolve("forge.jar"), Map.of("META-INF/mods.toml", "modLoader=\"javafml\""));
        Path neo = TestJars.jar(dir.resolve("neo.jar"), Map.of("META-INF/mods.toml", "", "META-INF/neoforge.mods.toml", ""));
        Path plugin = TestJars.jar(dir.resolve("plugin.jar"), Map.of("plugin.yml", "name: x"));
        List<Issue> issues = check(a, b, client, forge, neo, plugin);

        assertEquals(2, issues.stream().filter(i -> i.message().contains("repetido")).count());
        assertTrue(issues.stream().anyMatch(i -> i.severity() == Severity.WARNING && i.mod().equals("MINIMAP")));
        assertTrue(issues.stream().anyMatch(i -> i.file().equals("forge.jar") && i.message().contains("Forge")));
        assertTrue(issues.stream().anyMatch(i -> i.file().equals("neo.jar") && i.message().contains("NeoForge")));
        assertTrue(issues.stream().anyMatch(i -> i.file().equals("plugin.jar") && i.message().contains("Paper")));
    }

    @Test
    void disabledModsAreIgnoredAndUnknownLoaderSkipsCheck() throws IOException {
        Path m = TestJars.fabricMod(dir, "needs-loader", "1.0", "\"depends\": {\"fabricloader\": \">=99\"}");
        InstalledMod off = new InstalledMod(m, false, ModMetadata.read(m), null, null);
        assertEquals(List.of(), CompatibilityChecker.check(List.of(off), ENV));
        InstalledMod on = mod(m);
        assertEquals(1, CompatibilityChecker.check(List.of(on), ENV).size());
        assertEquals(List.of(), CompatibilityChecker.check(List.of(on), new Environment("1.21.4", null, 0)));
    }

    @Test
    void corruptJar() throws IOException {
        Path bad = dir.resolve("bad.jar");
        java.nio.file.Files.writeString(bad, "no soy un zip");
        List<Issue> issues = check(bad);
        assertEquals(1, issues.size());
        assertFalse(issues.get(0).message().isBlank());
    }
}
