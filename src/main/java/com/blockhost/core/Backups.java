package com.blockhost.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Copias de seguridad del mundo en &lt;servidor&gt;/backups/*.zip: manuales ("backup-..."), automáticas ("auto-...",
 * se guardan solo las últimas) y la que se hace sola antes de restaurar ("antes-de-restaurar-...").
 */
public final class Backups {

    public static final String MANUAL = "backup-";
    public static final String AUTO = "auto-";
    public static final String BEFORE_RESTORE = "antes-de-restaurar-";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern NAME = Pattern.compile("^(backup-|auto-|antes-de-restaurar-)(\\d{8}-\\d{6})(?:-\\d+)?\\.zip$");

    private Backups() { }

    /** Una copia en la carpeta backups. */
    public record Backup(Path file, String kind, LocalDateTime time, long size) {
        public boolean auto() { return AUTO.equals(kind); }

        public String kindLabel() {
            return switch (kind) {
                case AUTO -> "Automática";
                case BEFORE_RESTORE -> "Antes de restaurar";
                default -> "Manual";
            };
        }
    }

    public static Path create(ServerInstance inst) throws IOException { return create(inst, MANUAL); }

    /** Comprime las carpetas del mundo. kind: MANUAL, AUTO o BEFORE_RESTORE. */
    public static Path create(ServerInstance inst, String kind) throws IOException {
        Path dir = inst.dir();
        Path backups = Files.createDirectories(dir.resolve("backups"));
        String stamp = LocalDateTime.now().format(STAMP);
        Path zip = backups.resolve(kind + stamp + ".zip");
        for (int i = 2; Files.exists(zip); i++) zip = backups.resolve(kind + stamp + "-" + i + ".zip");
        boolean any = false;
        Path tmp = zip.resolveSibling(zip.getFileName() + ".part");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (String w : worldDirs(dir)) {
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
            Files.deleteIfExists(tmp);
            throw e;
        }
        if (!any) {
            Files.deleteIfExists(tmp);
            throw new IOException("Todavía no hay mundo: arranca el servidor al menos una vez");
        }
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
        return zip;
    }

    /**
     * Copia con el servidor en marcha: se pausa el guardado automático para que el mundo no cambie a medias,
     * se fuerza a escribir todo a disco, se copia y se reanuda.
     */
    public static Path createWhileRunning(ServerInstance inst, ServerProcess process, String kind) throws Exception {
        if (!process.isAlive()) return create(inst, kind);
        process.send("save-off");
        process.send("save-all flush");
        Thread.sleep(5000); // margen para que el servidor termine de escribir
        try {
            return create(inst, kind);
        } finally {
            process.send("save-on");
        }
    }

    /** Las copias del servidor, de la más nueva a la más antigua. */
    public static List<Backup> list(ServerInstance inst) throws IOException {
        Path backups = inst.dir().resolve("backups");
        List<Backup> out = new ArrayList<>();
        if (!Files.isDirectory(backups)) return out;
        try (Stream<Path> s = Files.list(backups)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String n = f.getFileName().toString();
                if (!n.endsWith(".zip")) continue;
                Matcher m = NAME.matcher(n);
                LocalDateTime time = null;
                String kind = MANUAL;
                if (m.matches()) {
                    kind = m.group(1);
                    try {
                        time = LocalDateTime.parse(m.group(2), STAMP);
                    } catch (DateTimeParseException ignored) {
                        // se usa la fecha del fichero
                    }
                }
                if (time == null) {
                    time = LocalDateTime.ofInstant(Files.getLastModifiedTime(f).toInstant(), ZoneId.systemDefault());
                }
                out.add(new Backup(f, kind, time, Files.size(f)));
            }
        }
        out.sort(Comparator.comparing(Backup::time).thenComparing(b -> b.file().getFileName().toString()).reversed());
        return out;
    }

    /** Borra las copias automáticas más antiguas para quedarse con keep. Devuelve cuántas borró. */
    public static int prune(ServerInstance inst, int keep) throws IOException {
        int kept = 0, deleted = 0;
        for (Backup b : list(inst)) {
            if (!b.auto()) continue;
            if (kept < keep) { kept++; continue; }
            Files.deleteIfExists(b.file());
            deleted++;
        }
        return deleted;
    }

    /**
     * Sustituye el mundo actual por el de la copia. Con el servidor detenido. Antes guarda el mundo actual en
     * una copia "antes-de-restaurar" por si te arrepientes.
     *
     * @return la copia de seguridad del mundo que había, o null si no había mundo
     */
    public static Path restore(ServerInstance inst, Path zip) throws IOException {
        Path dir = inst.dir().toAbsolutePath().normalize();
        Path safety = null;
        if (worldDirs(dir).stream().anyMatch(w -> Files.isDirectory(dir.resolve(w)))) {
            safety = create(inst, BEFORE_RESTORE);
        }

        // se extrae primero aparte: si el zip está roto, el mundo actual sigue intacto
        Path tmp = dir.resolve("backups").resolve(".restaurando");
        JavaRuntimeManager.deleteRecursively(tmp);
        Files.createDirectories(tmp);
        List<String> roots = new ArrayList<>();
        try {
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    Path out = tmp.resolve(e.getName()).normalize();
                    if (!out.startsWith(tmp) || out.equals(tmp)) throw new IOException("Entrada no permitida: " + e.getName());
                    String root = tmp.relativize(out).getName(0).toString();
                    if (!roots.contains(root)) roots.add(root);
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (roots.isEmpty()) throw new IOException("La copia está vacía");
            for (String w : worldDirs(dir)) JavaRuntimeManager.deleteRecursively(dir.resolve(w));
            for (String r : roots) {
                JavaRuntimeManager.deleteRecursively(dir.resolve(r));
                Files.move(tmp.resolve(r), dir.resolve(r));
            }
        } finally {
            JavaRuntimeManager.deleteRecursively(tmp);
        }
        return safety;
    }

    /** Carpetas del mundo según level-name de server.properties: world, world_nether y world_the_end. */
    static List<String> worldDirs(Path dir) {
        String level = "world";
        Path props = dir.resolve("server.properties");
        if (Files.isRegularFile(props)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(props, StandardCharsets.UTF_8)) {
                p.load(r);
                String l = p.getProperty("level-name", "world").trim();
                if (!l.isEmpty() && !l.contains("..") && !l.contains("/") && !l.contains("\\")) level = l;
            } catch (IOException ignored) {
                // se usa "world"
            }
        }
        return List.of(level, level + "_nether", level + "_the_end");
    }
}
