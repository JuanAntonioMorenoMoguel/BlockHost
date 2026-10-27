package com.blockhost.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Descarga y cachea el Java que necesita cada servidor (Eclipse Temurin vía API de Adoptium).
 * Independiente del Java con el que se ejecuta la propia app.
 */
public final class JavaRuntimeManager {

    private static final int[] LTS = {8, 11, 17, 21, 25};

    /** Adoptium solo publica JRE de versiones LTS: se redondea a la siguiente LTS (16 -> 17). */
    public static int normalize(int major) {
        for (int lts : LTS) if (major <= lts) return lts;
        return major;
    }

    public Path ensureJava(int requiredMajor, ProgressListener p) throws Exception {
        int major = normalize(requiredMajor);
        Path home = AppPaths.runtimes().resolve("java-" + major);
        Optional<Path> existing = findJavaBinary(home);
        if (existing.isPresent()) {
            p.log("Java " + major + " ya instalado");
            return existing.get();
        }

        String os = os();
        String arch = arch();
        try {
            download(major, os, arch, home, p);
        } catch (IOException e) {
            // Java 8 no existe para macOS ARM: se usa x64 con Rosetta.
            if (!arch.equals("x64") && os.equals("mac")) download(major, os, "x64", home, p);
            else throw e;
        }
        return findJavaBinary(home).orElseThrow(() ->
                new IllegalStateException("No se encontró bin/java tras instalar Java " + major));
    }

    private void download(int major, String os, String arch, Path home, ProgressListener p) throws Exception {
        String url = "https://api.adoptium.net/v3/binary/latest/" + major + "/ga/" + os + "/" + arch
                + "/jre/hotspot/normal/eclipse";
        boolean zip = os.equals("windows");
        Path archive = AppPaths.cache().resolve("temurin-" + major + "-" + os + "-" + arch + (zip ? ".zip" : ".tar.gz"));

        p.log("Descargando Java " + major + " (Eclipse Temurin)...");
        Http.download(url, archive, "Java " + major, p);

        p.onProgress("Extrayendo Java " + major + "...", -1);
        Path tmp = home.resolveSibling(home.getFileName() + ".tmp");
        deleteRecursively(tmp);
        Files.createDirectories(tmp);
        if (zip) unzip(archive, tmp);
        else untar(archive, tmp);
        deleteRecursively(home);
        Files.move(tmp, home, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(archive);
    }

    static Optional<Path> findJavaBinary(Path home) throws IOException {
        if (!Files.isDirectory(home)) return Optional.empty();
        String exe = os().equals("windows") ? "java.exe" : "java";
        try (Stream<Path> s = Files.walk(home, 6)) {
            return s.filter(f -> f.getFileName().toString().equals(exe))
                    .filter(f -> f.getParent() != null && f.getParent().getFileName().toString().equals("bin"))
                    .filter(Files::isRegularFile)
                    .findFirst();
        }
    }

    static void unzip(Path archive, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("Entrada de zip fuera del destino: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** tar viene de serie en Linux, macOS y Windows 10+; conserva los permisos de ejecución. */
    static void untar(Path archive, Path dest) throws IOException, InterruptedException {
        Process proc = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", dest.toString())
                .redirectErrorStream(true).start();
        String output;
        try (InputStream in = proc.getInputStream()) {
            output = new String(in.readAllBytes());
        }
        if (proc.waitFor() != 0) throw new IOException("Error al extraer Java: " + output);
    }

    public static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.delete(f);
        }
    }

    static String os() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "mac";
        return "linux";
    }

    static String arch() {
        String a = System.getProperty("os.arch").toLowerCase();
        return (a.contains("aarch64") || a.contains("arm64")) ? "aarch64" : "x64";
    }
}
