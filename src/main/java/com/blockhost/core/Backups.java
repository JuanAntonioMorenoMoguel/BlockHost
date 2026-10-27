package com.blockhost.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Copias de seguridad del mundo en &lt;servidor&gt;/backups/*.zip. */
public final class Backups {

    private static final List<String> WORLD_DIRS = List.of("world", "world_nether", "world_the_end");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Backups() { }

    public static Path create(ServerInstance inst) throws IOException {
        Path dir = inst.dir();
        Path backups = Files.createDirectories(dir.resolve("backups"));
        Path zip = backups.resolve("backup-" + LocalDateTime.now().format(STAMP) + ".zip");
        boolean any = false;
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String w : WORLD_DIRS) {
                Path world = dir.resolve(w);
                if (!Files.isDirectory(world)) continue;
                any = true;
                try (Stream<Path> files = Files.walk(world)) {
                    for (Path f : files.filter(Files::isRegularFile).toList()) {
                        if (f.getFileName().toString().equals("session.lock")) continue; // bloqueado mientras corre
                        out.putNextEntry(new ZipEntry(dir.relativize(f).toString().replace('\\', '/')));
                        Files.copy(f, out);
                        out.closeEntry();
                    }
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(zip);
            throw e;
        }
        if (!any) {
            Files.deleteIfExists(zip);
            throw new IOException("Todavía no hay mundo: arranca el servidor al menos una vez");
        }
        return zip;
    }
}
