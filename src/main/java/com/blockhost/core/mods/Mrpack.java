package com.blockhost.core.mods;

import com.blockhost.core.AppPaths;
import com.blockhost.core.Hashes;
import com.blockhost.core.Http;
import com.blockhost.core.Json;
import com.blockhost.core.ProgressListener;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Modpacks en formato Modrinth (.mrpack): https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack */
public final class Mrpack {

    /** Hosts de descarga que admite el formato; cualquier otro se rechaza. */
    private static final Set<String> ALLOWED_HOSTS = Set.of("cdn.modrinth.com", "github.com",
            "raw.githubusercontent.com", "gitlab.com", "edge.forgecdn.net", "mediafilez.forgecdn.net");

    /** Claves de "dependencies" del índice -> loader. */
    private static final Map<String, String> LOADERS = Map.of(
            "fabric-loader", "fabric", "quilt-loader", "quilt", "forge", "forge", "neoforge", "neoforge");

    private Mrpack() { }

    /**
     * @param serverEnv "required", "optional" o "unsupported" (este último no se instala en el servidor)
     */
    public record PackFile(String path, String sha1, String sha512, List<String> urls, long size, String serverEnv) {
        public boolean forServer() { return !"unsupported".equals(serverEnv); }
    }

    /**
     * Un modpack ya leído (de Modrinth o de CurseForge, que se convierte a este mismo formato).
     *
     * @param overrideDirs carpetas del archivo que se copian tal cual sobre el servidor, en orden
     * @param unavailable  ficheros que no se pueden descargar automáticamente (el autor no lo permite)
     */
    public record Pack(String name, String versionId, String summary, String mcVersion,
                       Map<String, String> dependencies, List<PackFile> files,
                       List<String> overrideDirs, List<String> unavailable) {

        /** "fabric", "quilt", "forge", "neoforge" o null si es vanilla. */
        public String loader() {
            for (String key : dependencies.keySet()) if (LOADERS.containsKey(key)) return LOADERS.get(key);
            return null;
        }

        public String loaderVersion() {
            for (Map.Entry<String, String> e : dependencies.entrySet()) {
                if (LOADERS.containsKey(e.getKey())) return e.getValue();
            }
            return null;
        }

        public List<PackFile> serverFiles() { return files.stream().filter(PackFile::forServer).toList(); }

        public String title() { return versionId == null ? name : name + " " + versionId; }
    }

    /** Lee un modpack de Modrinth (.mrpack) o de CurseForge (.zip con manifest.json). */
    public static Pack readAny(Path archive) throws Exception {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.getEntry("modrinth.index.json") != null) return read(archive);
            if (zip.getEntry("manifest.json") != null) return CurseForgePack.read(archive);
        } catch (java.util.zip.ZipException e) {
            throw new IOException("El archivo no es un modpack válido");
        }
        throw new IOException("No es un modpack de Modrinth (.mrpack) ni de CurseForge (.zip con manifest.json)");
    }

    public static Pack read(Path mrpack) throws IOException {
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            ZipEntry index = zip.getEntry("modrinth.index.json");
            if (index == null) throw new IOException("No es un modpack de Modrinth (.mrpack): falta modrinth.index.json");
            String text = new String(zip.getInputStream(index).readAllBytes(), StandardCharsets.UTF_8);
            return parse(text);
        } catch (java.util.zip.ZipException e) {
            throw new IOException("El archivo no es un .mrpack válido");
        }
    }

    static Pack parse(String indexJson) throws IOException {
        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(indexJson));
        } catch (IllegalArgumentException e) {
            throw new IOException("modrinth.index.json ilegible: " + e.getMessage());
        }
        if (!"minecraft".equals(Json.str(m.get("game")))) throw new IOException("El modpack no es de Minecraft");

        Map<String, String> deps = new LinkedHashMap<>();
        if (m.get("dependencies") instanceof Map<?, ?> d) d.forEach((k, v) -> deps.put(String.valueOf(k), String.valueOf(v)));
        String mc = deps.remove("minecraft");
        if (mc == null) throw new IOException("El modpack no indica su versión de Minecraft");

        List<PackFile> files = new ArrayList<>();
        if (m.get("files") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> f = Json.obj(o);
                List<String> urls = f.get("downloads") instanceof List<?> u ? u.stream().map(String::valueOf).toList() : List.of();
                String env = Json.str(Json.path(f, "env", "server"));
                files.add(new PackFile(Json.str(f.get("path")), Json.str(Json.path(f, "hashes", "sha1")),
                        Json.str(Json.path(f, "hashes", "sha512")), urls, Json.num(f.get("fileSize"), -1),
                        env == null ? "required" : env));
            }
        }
        return new Pack(Json.str(m.get("name")), Json.str(m.get("versionId")), Json.str(m.get("summary")), mc, deps, files,
                List.of("overrides", "server-overrides"), List.of());
    }

    /** Descarga a staging los ficheros del pack que van en el servidor, comprobando ruta, host y huella de cada uno. */
    static void downloadFiles(Pack pack, Path staging, ProgressListener p) throws Exception {
        List<PackFile> files = pack.serverFiles();
        int done = 0;
        for (PackFile f : files) {
            Path target = safeResolve(staging, f.path());
            String url = f.urls().stream().filter(Mrpack::allowedUrl).findFirst().orElseThrow(() ->
                    new IOException("«" + f.path() + "» no tiene una descarga de un sitio permitido"));
            p.onProgress("Descargando " + f.path() + " (" + (done + 1) + "/" + files.size() + ")",
                    (double) done / Math.max(1, files.size()));
            Http.download(url, target, f.path(), ProgressListener.NONE);
            Hashes.verify(target, f.sha1(), f.sha512());
            done++;
        }
    }

    /** Copia las carpetas de overrides del pack sobre dest (en Modrinth, server-overrides/ tiene prioridad). */
    static void extractOverrides(Pack pack, Path archive, Path dest) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (String dir : pack.overrideDirs()) {
                String prefix = dir.endsWith("/") ? dir : dir + "/";
                for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                    if (!e.getName().startsWith(prefix) || e.isDirectory()) continue;
                    String rel = e.getName().substring(prefix.length());
                    if (rel.isEmpty()) continue;
                    Path out = safeResolve(dest, rel);
                    Files.createDirectories(out.getParent());
                    try (var in = zip.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /** Descarga el archivo de una versión de modpack (Modrinth o CurseForge) a la caché y lo verifica. */
    public static Path download(ModRepository.Version v, ProgressListener p) throws Exception {
        if (v.file() == null) throw new IOException("Esa versión del modpack no tiene archivo");
        if (v.file().url() == null) throw new IOException("El autor de este modpack no permite descargarlo desde otras apps");
        Path dir = Files.createDirectories(AppPaths.cache().resolve("modpacks"));
        Path target = safeResolve(dir, v.file().fileName());
        Http.download(v.file().url(), target, "Modpack " + v.file().fileName(), p);
        Hashes.verify(target, v.file().sha1(), v.file().sha512());
        return target;
    }

    static boolean allowedUrl(String url) {
        try {
            URI u = URI.create(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null
                    && ALLOWED_HOSTS.contains(u.getHost().toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Resuelve una ruta del pack dentro de root, rechazando rutas absolutas o que intenten salir con "..". */
    static Path safeResolve(Path root, String rel) throws IOException {
        if (rel == null || rel.isBlank()) throw new IOException("Ruta vacía en el modpack");
        String r = rel.replace('\\', '/');
        if (r.startsWith("/") || r.matches("^[A-Za-z]:.*")) throw new IOException("Ruta no permitida en el modpack: " + rel);
        Path base = root.toAbsolutePath().normalize();
        Path out = base.resolve(r).normalize();
        if (!out.startsWith(base) || out.equals(base)) throw new IOException("Ruta no permitida en el modpack: " + rel);
        return out;
    }
}
