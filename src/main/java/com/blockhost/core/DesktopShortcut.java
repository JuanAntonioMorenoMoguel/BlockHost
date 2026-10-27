package com.blockhost.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Acceso directo a BlockHost en el escritorio. Solo tiene sentido con la app empaquetada (BlockHost.exe de
 * {@code gradlew app}): desde Gradle no hay un ejecutable al que apuntar.
 */
public final class DesktopShortcut {

    /** Clave de ajustes: ya se intentó crear al primer arranque (no se vuelve a crear si el usuario lo borra). */
    public static final String SETTING = "desktopShortcutCreated";
    private static final String NAME = "BlockHost";

    private DesktopShortcut() { }

    /** El ejecutable de la app empaquetada, o null si se ejecuta desde Gradle o un IDE. */
    public static Path launcher() {
        String p = System.getProperty("jpackage.app-path");
        return p == null || p.isBlank() ? null : Path.of(p);
    }

    public static boolean supported() {
        String os = System.getProperty("os.name").toLowerCase();
        return launcher() != null && (os.contains("win") || os.contains("linux"));
    }

    /** Crea (o rehace) el acceso directo y devuelve su ruta. */
    public static Path create() throws IOException, InterruptedException {
        Path exe = launcher();
        if (exe == null) throw new IllegalStateException("Solo se puede crear desde la app instalada (BlockHost.exe)");
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return createWindows(exe);
        if (os.contains("linux")) return createLinux(exe, linuxDesktopDir(),
                Path.of(System.getProperty("user.home"), ".local", "share", "applications"));
        throw new UnsupportedOperationException("Accesos directos no disponibles en este sistema");
    }

    // ---------------- Windows ----------------

    /** El .lnk lo crea PowerShell (WScript.Shell): sabe dónde está el escritorio aunque OneDrive lo mueva. */
    private static Path createWindows(Path exe) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-Command", windowsScript(exe))
                .redirectErrorStream(true).start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("PowerShell no respondió al crear el acceso directo");
        }
        if (p.exitValue() != 0 || out.isEmpty()) throw new IOException("No se pudo crear el acceso directo: " + out);
        String[] lines = out.split("\\R");
        return Path.of(lines[lines.length - 1].trim());
    }

    static String windowsScript(Path exe) {
        String target = ps(exe.toAbsolutePath().toString());
        String dir = ps(exe.toAbsolutePath().getParent().toString());
        return "$ErrorActionPreference = 'Stop'; "
                + "$lnk = Join-Path ([Environment]::GetFolderPath('Desktop')) '" + NAME + ".lnk'; "
                + "$s = (New-Object -ComObject WScript.Shell).CreateShortcut($lnk); "
                + "$s.TargetPath = '" + target + "'; "
                + "$s.WorkingDirectory = '" + dir + "'; "
                + "$s.IconLocation = '" + target + ",0'; "
                + "$s.Description = 'Servidores de Minecraft en tu PC'; "
                + "$s.Save(); Write-Output $lnk";
    }

    /** Literal de PowerShell entre comillas simples: la comilla se escribe doble. */
    private static String ps(String s) { return s.replace("'", "''"); }

    // ---------------- Linux ----------------

    /** Escribe BlockHost.desktop en el escritorio y en el menú de aplicaciones. */
    static Path createLinux(Path exe, Path desktopDir, Path applicationsDir) throws IOException {
        // jpackage: <app>/bin/BlockHost y el icono en <app>/lib/BlockHost.png
        Path icon = exe.toAbsolutePath().getParent().resolveSibling("lib").resolve(NAME + ".png");
        String entry = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=" + NAME + "\n"
                + "Comment=Servidores de Minecraft en tu PC\n"
                + "Exec=\"" + exe.toAbsolutePath() + "\"\n"
                + (Files.exists(icon) ? "Icon=" + icon + "\n" : "")
                + "Terminal=false\n"
                + "Categories=Game;\n";
        Files.createDirectories(applicationsDir);
        Files.writeString(applicationsDir.resolve(NAME + ".desktop"), entry, StandardCharsets.UTF_8);
        Files.createDirectories(desktopDir);
        Path file = desktopDir.resolve(NAME + ".desktop");
        Files.writeString(file, entry, StandardCharsets.UTF_8);
        file.toFile().setExecutable(true); // GNOME/KDE solo lanzan .desktop marcados como ejecutables
        return file;
    }

    /** Respeta el nombre traducido del escritorio (p. ej. ~/Escritorio) si xdg-user-dir lo sabe. */
    private static Path linuxDesktopDir() {
        try {
            Process p = new ProcessBuilder("xdg-user-dir", "DESKTOP").redirectErrorStream(true).start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isEmpty()) return Path.of(out);
        } catch (IOException ignored) {
            // sin xdg-user-dir: ~/Desktop
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Path.of(System.getProperty("user.home"), "Desktop");
    }
}
