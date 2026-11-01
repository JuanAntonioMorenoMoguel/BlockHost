package com.blockhost.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compartir mundos: exportar el mundo de un servidor a un .zip (para dejarlo en Google Drive o mandarlo), subirlo
 * para tener un enlace, e importar un mundo desde un .zip, una carpeta o un enlace. Al importar vale tanto un
 * mundo exportado por BlockHost como uno de un jugador (la carpeta de .minecraft/saves o un zip de ella).
 */
public final class WorldShare {

    /** Litterbox (catbox.moe): subida anónima, sin cuenta ni clave, hasta 1 GB, el enlace dura 72 horas. */
    public static final String UPLOAD_URL = "https://litterbox.catbox.moe/resources/internals/api.php";
    public static final long MAX_UPLOAD = 1000L * 1024 * 1024;
    public static final int LINK_HOURS = 72;

    private static final Pattern DRIVE_FILE = Pattern.compile("drive\\.google\\.com/file/(?:u/\\d+/)?d/([\\w-]+)");
    private static final Pattern DRIVE_ID = Pattern.compile("drive\\.google\\.com/(?:open|uc)\\?(?:.*&)?id=([\\w-]+)");

    private WorldShare() { }

    /** Una carpeta sincronizada con la nube (Google Drive, OneDrive o Dropbox) donde dejar el zip. */
    public record CloudFolder(String name, Path path) { }

    /** Nombre de archivo para exportar: "Mi servidor" -> "Mi-servidor-mundo-2026-10-30.zip". */
    public static String exportFileName(ServerInstance inst) {
        String base = Normalizer.normalize(inst.config().name(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "servidor";
        return base + "-mundo-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".zip";
    }

    /** Comprime el mundo a target. Si el servidor está en marcha, pausa el guardado mientras tanto. */
    public static Path export(ServerInstance inst, ServerProcess process, Path target) throws Exception {
        return Backups.whileSavingPaused(process, () -> {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Backups.zipWorld(inst.dir(), target);
            return target;
        });
    }

    /** Sube el zip y devuelve el enlace de descarga (caduca a las {@link #LINK_HOURS} horas). */
    public static String upload(Path zip, ProgressListener p) throws IOException, InterruptedException {
        return upload(UPLOAD_URL, zip, p);
    }

    static String upload(String endpoint, Path zip, ProgressListener p) throws IOException, InterruptedException {
        long size = Files.size(zip);
        if (size > MAX_UPLOAD) {
            throw new IOException(String.format("El mundo ocupa %.0f MB y el límite para subirlo es 1 GB. "
                    + "Guárdalo en Google Drive y comparte el enlace desde allí.", size / 1048576.0));
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("reqtype", "fileupload");
        fields.put("time", LINK_HOURS + "h");
        String body = Http.uploadFile(endpoint, fields, "fileToUpload", zip, "Subiendo el mundo", p).trim();
        if (!body.startsWith("https://") || body.contains(" ") || body.contains("\n")) {
            throw new IOException("El servicio de subida no devolvió un enlace: "
                    + (body.length() > 200 ? body.substring(0, 200) + "..." : body));
        }
        return body;
    }

    /**
     * Convierte un enlace de compartir en uno de descarga directa: Google Drive ("Cualquier persona con el enlace"),
     * Dropbox y OneDrive. Los demás se devuelven tal cual.
     */
    public static String directDownloadUrl(String link) throws IOException {
        String url = link.trim();
        if (url.isEmpty()) throw new IOException("Pega el enlace del mundo");
        if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
        if (url.contains("drive.google.com/drive/") && url.contains("/folders/")) {
            throw new IOException("Es el enlace de una carpeta de Google Drive. Comparte el archivo .zip del mundo, "
                    + "no la carpeta.");
        }
        Matcher m = DRIVE_FILE.matcher(url);
        if (!m.find()) m = DRIVE_ID.matcher(url);
        if (m.find(0)) {
            // confirm=t evita la página de "no se puede analizar en busca de virus" de los archivos grandes
            return "https://drive.usercontent.google.com/download?id=" + m.group(1) + "&export=download&confirm=t";
        }
        if (url.matches("(?i)^https?://(www\\.)?dropbox\\.com/.*")) {
            if (url.matches(".*[?&]dl=0.*")) return url.replaceFirst("([?&])dl=0", "$1dl=1");
            if (!url.matches(".*[?&]dl=1.*")) return url + (url.contains("?") ? "&" : "?") + "dl=1";
            return url;
        }
        if (url.matches("(?i)^https?://(1drv\\.ms|onedrive\\.live\\.com)/.*") && !url.contains("download=1")) {
            return url + (url.contains("?") ? "&" : "?") + "download=1";
        }
        return url;
    }

    /** Descarga el mundo del enlace a target y comprueba que es un zip. */
    public static void download(String link, Path target, ProgressListener p) throws IOException, InterruptedException {
        Http.download(directDownloadUrl(link), target, "Descargando el mundo", p);
        if (!isZip(target)) {
            Files.deleteIfExists(target);
            throw new IOException("El enlace no lleva a un archivo .zip. Si es de Google Drive, compártelo como "
                    + "«Cualquier persona con el enlace»; si era de BlockHost, puede que haya caducado.");
        }
    }

    static boolean isZip(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            byte[] b = in.readNBytes(4);
            return b.length == 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
        }
    }

    /**
     * Sustituye el mundo del servidor (detenido) por el de source, que puede ser un .zip o una carpeta. Se busca
     * el level.dat, así que valen mundos exportados de otro servidor (con su _nether y _the_end) y mundos de un
     * jugador. Antes guarda el mundo actual en una copia "antes-de-restaurar".
     *
     * @return la copia de seguridad del mundo que había, o null si no había mundo
     */
    public static Path importWorld(ServerInstance inst, Path source) throws IOException {
        Path dir = inst.dir().toAbsolutePath().normalize();
        Path tmp = dir.resolve("backups").resolve(".importando");
        JavaRuntimeManager.deleteRecursively(tmp);
        Files.createDirectories(tmp);
        try {
            if (Files.isDirectory(source)) {
                copyTree(source, tmp.resolve(source.getFileName() == null ? "world" : source.getFileName().toString()));
            } else {
                if (!isZip(source)) throw new IOException("No es un archivo .zip");
                Backups.unzip(source, tmp);
            }
            Path world = findWorld(tmp).orElseThrow(() -> new IOException(
                    "No hay ningún mundo de Minecraft dentro (falta el archivo level.dat)."));

            // con el mundo de un servidor Paper/Spigot, el Nether y el End van en carpetas aparte junto a él
            Path nether = null, end = null;
            if (!world.equals(tmp)) {
                String base = world.getFileName().toString();
                nether = dirOrNull(world.resolveSibling(base + "_nether"));
                end = dirOrNull(world.resolveSibling(base + "_the_end"));
            }

            Path safety = null;
            List<String> worlds = Backups.worldDirs(dir);
            if (worlds.stream().anyMatch(w -> Files.isDirectory(dir.resolve(w)))) {
                safety = Backups.create(inst, Backups.BEFORE_RESTORE);
            }
            for (String w : worlds) JavaRuntimeManager.deleteRecursively(dir.resolve(w));
            Files.deleteIfExists(world.resolve("session.lock"));
            Files.move(world, dir.resolve(worlds.get(0)));
            if (nether != null) Files.move(nether, dir.resolve(worlds.get(1)));
            if (end != null) Files.move(end, dir.resolve(worlds.get(2)));
            return safety;
        } finally {
            JavaRuntimeManager.deleteRecursively(tmp);
        }
    }

    /** La carpeta con level.dat menos profunda; a igual nivel, la del mundo principal y luego por nombre. */
    static Optional<Path> findWorld(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root, 6)) {
            return s.filter(f -> f.getFileName().toString().equals("level.dat") && Files.isRegularFile(f))
                    .map(Path::getParent)
                    .min(Comparator.comparingInt(Path::getNameCount)
                            .thenComparing(WorldShare::isDimensionDir) // world antes que world_nether
                            .thenComparing(Path::toString));
        }
    }

    private static boolean isDimensionDir(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString();
        return n.endsWith("_nether") || n.endsWith("_the_end");
    }

    private static Path dirOrNull(Path p) { return Files.isDirectory(p) ? p : null; }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : s.toList()) {
                Path out = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(out);
                else if (!f.getFileName().toString().equals("session.lock")) {
                    Files.createDirectories(out.getParent());
                    Files.copy(f, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Carpetas de Google Drive para escritorio, OneDrive y Dropbox que haya en este PC, por ese orden. Google Drive
     * aparece como una unidad (G:) con "Mi unidad" o "My Drive" dentro.
     */
    public static List<CloudFolder> cloudFolders() {
        List<CloudFolder> out = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> candidates = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            for (char c = 'D'; c <= 'Z'; c++) {
                Path root = Path.of(c + ":\\");
                candidates.add(root.resolve("Mi unidad"));
                candidates.add(root.resolve("My Drive"));
            }
        }
        candidates.add(home.resolve("Google Drive").resolve("Mi unidad"));
        candidates.add(home.resolve("Google Drive").resolve("My Drive"));
        candidates.add(home.resolve("Google Drive"));
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                out.add(new CloudFolder("Google Drive", p));
                break;
            }
        }
        String oneDrive = System.getenv("OneDrive");
        if (oneDrive != null && !oneDrive.isBlank() && Files.isDirectory(Path.of(oneDrive))) {
            out.add(new CloudFolder("OneDrive", Path.of(oneDrive)));
        }
        if (Files.isDirectory(home.resolve("Dropbox"))) out.add(new CloudFolder("Dropbox", home.resolve("Dropbox")));
        return out;
    }
}
