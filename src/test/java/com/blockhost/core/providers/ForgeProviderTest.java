package com.blockhost.core.providers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ForgeProviderTest {

    @TempDir Path dir;

    @Test
    void neoforgeVersionsMapToMinecraft() {
        assertEquals("1.20.2", ForgeProvider.neoToMc("20.2.3-beta"));
        assertEquals("1.20.4", ForgeProvider.neoToMc("20.4.237"));
        assertEquals("1.21", ForgeProvider.neoToMc("21.0.167"));
        assertEquals("1.21.1", ForgeProvider.neoToMc("21.1.77"));
        assertEquals("1.21.10", ForgeProvider.neoToMc("21.10.5-beta"));
        assertEquals("26.1", ForgeProvider.neoToMc("26.1.0.3-beta"));
        assertEquals("26.1.1", ForgeProvider.neoToMc("26.1.1.7"));
        assertNull(ForgeProvider.neoToMc("47.1.3"));
    }

    @Test
    void neoforgePrefersLatestStable() {
        List<Object> versions = List.of("21.1.1-beta", "21.1.9", "21.1.77", "21.1.80-beta", "21.0.5", "21.3.1-beta");
        assertEquals("21.1.77", ForgeProvider.neoFor(versions, "1.21.1"));
        assertEquals("21.3.1-beta", ForgeProvider.neoFor(versions, "1.21.3"));
        assertNull(ForgeProvider.neoFor(versions, "1.20.4"));
        assertEquals(List.of("1.21.3", "1.21.1", "1.21"), ForgeProvider.neoMcVersions(versions));
    }

    @Test
    void forgePromotions() {
        Map<String, String> promos = new LinkedHashMap<>();
        promos.put("1.7.10-recommended", "10.13.4.1614");
        promos.put("1.12.2-recommended", "14.23.5.2859");
        promos.put("1.12.2-latest", "14.23.5.2860");
        promos.put("1.20.1-latest", "47.4.0");
        promos.put("1.20.1-recommended", "47.3.0");
        promos.put("1.21.4-latest", "54.1.0");
        assertEquals(List.of("1.21.4", "1.20.1", "1.12.2"), ForgeProvider.forgeMcVersions(promos));
        assertEquals("47.3.0", ForgeProvider.forgeFor(promos, "1.20.1"));
        assertEquals("54.1.0", ForgeProvider.forgeFor(promos, "1.21.4"));
    }

    @Test
    void detectsHowToLaunch() throws IOException {
        Path modern = Files.createDirectories(dir.resolve("modern"));
        Files.writeString(modern.resolve("run.sh"), "#!/usr/bin/env sh\njava @user_jvm_args.txt "
                + "@libraries/net/neoforged/neoforge/21.1.77/unix_args.txt \"$@\"\n");
        String launch = ForgeProvider.detectLaunch(modern, ForgeProvider.Flavor.NEOFORGE);
        assertEquals("@libraries/net/neoforged/neoforge/21.1.77/unix_args.txt", launch);
        assertEquals(List.of("@libraries/net/neoforged/neoforge/21.1.77/win_args.txt"), ForgeProvider.launchArgs(launch, true));
        assertEquals(List.of(launch), ForgeProvider.launchArgs(launch, false));

        Path shim = Files.createDirectories(dir.resolve("shim"));
        Files.writeString(shim.resolve("forge-1.21.4-54.1.0-shim.jar"), "x");
        Files.writeString(shim.resolve("run.bat"), "java @user_jvm_args.txt -jar forge-1.21.4-54.1.0-shim.jar %*\n");
        assertEquals("forge-1.21.4-54.1.0-shim.jar", ForgeProvider.detectLaunch(shim, ForgeProvider.Flavor.FORGE));

        Path legacy = Files.createDirectories(dir.resolve("legacy"));
        Files.writeString(legacy.resolve("forge-1.12.2-14.23.5.2860.jar"), "x");
        Files.writeString(legacy.resolve("forge-1.12.2-14.23.5.2860-installer.jar"), "x");
        assertEquals("forge-1.12.2-14.23.5.2860.jar", ForgeProvider.detectLaunch(legacy, ForgeProvider.Flavor.FORGE));
        assertEquals(List.of("-jar", "paper.jar"), ForgeProvider.launchArgs("paper.jar", true));
    }
}
