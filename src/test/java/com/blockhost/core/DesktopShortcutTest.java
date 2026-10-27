package com.blockhost.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopShortcutTest {

    @TempDir Path dir;

    @Test
    void linuxDesktopEntryPointsToLauncherAndIcon() throws IOException {
        Path app = dir.resolve("BlockHost");
        Path exe = Files.createDirectories(app.resolve("bin")).resolve("BlockHost");
        Files.writeString(exe, "");
        Files.writeString(Files.createDirectories(app.resolve("lib")).resolve("BlockHost.png"), "png");

        Path file = DesktopShortcut.createLinux(exe, dir.resolve("Escritorio"), dir.resolve("applications"));
        String text = Files.readString(file);
        assertEquals(dir.resolve("Escritorio/BlockHost.desktop"), file);
        assertTrue(text.contains("Exec=\"" + exe.toAbsolutePath() + "\""), text);
        assertTrue(text.contains("Icon=" + app.resolve("lib/BlockHost.png")), text);
        assertTrue(Files.isExecutable(file));
        assertTrue(Files.exists(dir.resolve("applications/BlockHost.desktop")));
    }

    @Test
    void windowsScriptEscapesQuotes() {
        String s = DesktopShortcut.windowsScript(Path.of("/Users/O'Brien/BlockHost/BlockHost.exe"));
        assertTrue(s.contains("O''Brien"), s);
        assertTrue(s.contains("GetFolderPath('Desktop')"));
        assertTrue(s.contains(".IconLocation = '"));
    }

    @Test
    void notSupportedOutsideThePackagedApp() {
        assertEquals(null, DesktopShortcut.launcher());
    }
}
