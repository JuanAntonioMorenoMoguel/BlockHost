package com.blockhost.core.providers;

import com.blockhost.core.Http;
import com.blockhost.core.JavaRuntimeManager;
import com.blockhost.core.Json;
import com.blockhost.core.ProgressListener;
import com.blockhost.core.mods.VersionPredicate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Forge y NeoForge. Los dos se instalan igual: se descarga su instalador y se ejecuta con
 * {@code --installServer}, que baja Minecraft y las librerías y deja un script de arranque.
 *
 * <p>Cómo se arranca depende de la versión: las modernas usan un fichero de argumentos
 * ({@code @libraries/.../unix_args.txt}) y las antiguas un .jar. Se guarda como "nombre del jar": si empieza por
 * "@" es un fichero de argumentos (ver {@link #launchArgs}).
 */
public final class ForgeProvider implements ServerProvider {

    public enum Flavor {
        FORGE("Forge"), NEOFORGE("NeoForge");

        public final String displayName;

        Flavor(String displayName) { this.displayName = displayName; }
    }

    private static final String FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
    private static final String NEO_VERSIONS = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";
    private static final String NEO_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
    /** Antes de 1.12.2 los instaladores de Forge tienen otras rutas y no merece la pena soportarlos. */
    private static final String OLDEST_FORGE = "1.12.2";

    private static final Pattern ARGS_FILE = Pattern.compile("@(libraries/\\S+?)(unix|win)_args\\.txt");
    private static final Pattern JAR_ARG = Pattern.compile("-jar\\s+\"?([^\\s\"]+\\.jar)");

    private final Flavor flavor;

    public ForgeProvider(Flavor flavor) { this.flavor = flavor; }

    @Override
    public List<String> listVersions() throws Exception {
        return flavor == Flavor.FORGE ? forgeMcVersions(promos()) : neoMcVersions(neoVersions());
    }

    /** Instala la versión recomendada del loader para esa versión de Minecraft. */
    @Override
    public String install(String mcVersion, Path dir, ProgressListener p) throws Exception {
        Path java = new JavaRuntimeManager().ensureJava(MojangMeta.requiredJava(mcVersion), p);
        return install(flavor, mcVersion, latestLoader(mcVersion), dir, java, p);
    }

    public String latestLoader(String mcVersion) throws Exception {
        String v = flavor == Flavor.FORGE ? forgeFor(promos(), mcVersion) : neoFor(neoVersions(), mcVersion);
        if (v == null) throw new IllegalStateException(flavor.displayName + " no tiene versión para Minecraft " + mcVersion);
        return v;
    }

    // ---------------- versiones ----------------

    private static Map<String, String> promos() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Object promos = Json.obj(Http.getJson(FORGE_PROMOS)).get("promos");
        if (promos instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }

    private static List<Object> neoVersions() throws Exception {
        return Json.arr(Json.obj(Http.getJson(NEO_VERSIONS)).get("versions"));
    }

    /** promos: {"1.20.1-latest": "47.4.0", "1.20.1-recommended": "47.3.0", ...} -> versiones de Minecraft. */
    static List<String> forgeMcVersions(Map<String, String> promos) {
        List<String> out = new ArrayList<>();
        for (String key : promos.keySet()) {
            String mc = key.replaceFirst("-(latest|recommended)$", "");
            if (ServerProvider.isStableVersion(mc) && !out.contains(mc)
                    && ServerProvider.VERSION_ORDER.compare(mc, OLDEST_FORGE) >= 0) out.add(mc);
        }
        out.sort(ServerProvider.VERSION_ORDER.reversed());
        return out;
    }

    /** La recomendada; si no hay, la última. */
    static String forgeFor(Map<String, String> promos, String mc) {
        String v = promos.get(mc + "-recommended");
        return v != null ? v : promos.get(mc + "-latest");
    }

    /**
     * Versión de Minecraft de una de NeoForge: "21.1.77" -> 1.21.1, "21.0.5" -> 1.21, "20.4.237" -> 1.20.4.
     * Desde 2026 Minecraft numera por año y NeoForge lo copia con una cuarta cifra: "26.1.0.3" -> 26.1.
     */
    static String neoToMc(String neo) {
        String[] p = neo.replaceFirst("[-+].*", "").split("\\.");
        if (p.length < 3) return null;
        try {
            int major = Integer.parseInt(p[0]), minor = Integer.parseInt(p[1]), patch = Integer.parseInt(p[2]);
            if (major >= 25) {
                if (p.length < 4) return null;
                return major + "." + minor + (patch != 0 ? "." + patch : "");
            }
            if (major < 20) return null;
            return "1." + major + (minor != 0 ? "." + minor : "");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean neoStable(String v) { return !v.contains("-"); }

    static List<String> neoMcVersions(List<Object> versions) {
        List<String> out = new ArrayList<>();
        for (Object o : versions) {
            String mc = neoToMc(String.valueOf(o));
            if (mc != null && ServerProvider.isStableVersion(mc) && !out.contains(mc)) out.add(mc);
        }
        out.sort(ServerProvider.VERSION_ORDER.reversed());
        return out;
    }

    /** La estable más reciente para esa versión de Minecraft; si solo hay betas, la beta más reciente. */
    static String neoFor(List<Object> versions, String mc) {
        Comparator<String> order = VersionPredicate::compare;
        List<String> forMc = versions.stream().map(String::valueOf).filter(v -> mc.equals(neoToMc(v))).toList();
        return forMc.stream().filter(ForgeProvider::neoStable).max(order)
                .orElseGet(() -> forMc.stream().max(order).orElse(null));
    }

    // ---------------- instalación ----------------

    /**
     * Descarga el instalador y lo ejecuta en dir con el Java del servidor.
     *
     * @param loaderVersion versión de Forge ("47.3.0") o NeoForge ("21.1.77")
     * @return cómo arrancarlo: un .jar o "@ruta/unix_args.txt"
     */
    public static String install(Flavor flavor, String mcVersion, String loaderVersion, Path dir, Path java,
                                 ProgressListener p) throws Exception {
        String v = loaderVersion.startsWith(mcVersion + "-") ? loaderVersion.substring(mcVersion.length() + 1) : loaderVersion;
        String url = flavor == Flavor.FORGE
                ? FORGE_MAVEN + mcVersion + "-" + v + "/forge-" + mcVersion + "-" + v + "-installer.jar"
                : NEO_MAVEN + v + "/neoforge-" + v + "-installer.jar";
        Path installer = dir.resolve(flavor.name().toLowerCase() + "-installer.jar");
        p.log("Descargando el instalador de " + flavor.displayName + " " + v + "...");
        Http.download(url, installer, "Instalador de " + flavor.displayName, p);

        p.onProgress("Instalando " + flavor.displayName + " " + v + " (descarga Minecraft y librerías, tarda un poco)...", -1);
        Process proc = new ProcessBuilder(java.toString(), "-jar", installer.getFileName().toString(), "--installServer")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        List<String> tail = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                tail.add(line);
                if (tail.size() > 15) tail.remove(0);
                if (line.startsWith("Downloading") || line.startsWith("Extracting") || line.contains("Processor")) {
                    p.onProgress(flavor.displayName + ": " + shorten(line), -1);
                }
            }
        }
        if (!proc.waitFor(20, TimeUnit.MINUTES)) {
            proc.destroyForcibly();
            throw new IOException("El instalador de " + flavor.displayName + " no terminó a tiempo");
        }
        if (proc.exitValue() != 0) {
            throw new IOException("El instalador de " + flavor.displayName + " falló:\n" + String.join("\n", tail));
        }
        Files.deleteIfExists(installer);
        Files.deleteIfExists(dir.resolve(installer.getFileName() + ".log"));
        Files.deleteIfExists(dir.resolve("installer.log"));
        Files.createDirectories(dir.resolve("mods"));

        String launch = detectLaunch(dir, flavor);
        if (launch == null) throw new IOException(flavor.displayName + " se instaló pero no se encontró cómo arrancarlo");
        p.log(flavor.displayName + " " + v + " instalado");
        return launch;
    }

    private static String shorten(String s) { return s.length() > 90 ? s.substring(0, 87) + "..." : s; }

    /**
     * Mira el run.sh/run.bat que deja el instalador; si no hay (versiones antiguas), el .jar de Forge de la carpeta.
     */
    static String detectLaunch(Path dir, Flavor flavor) throws IOException {
        for (String script : List.of("run.sh", "run.bat")) {
            Path f = dir.resolve(script);
            if (!Files.isRegularFile(f)) continue;
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = ARGS_FILE.matcher(text);
            if (m.find()) return "@" + m.group(1) + "unix_args.txt";
            m = JAR_ARG.matcher(text);
            if (m.find() && Files.isRegularFile(dir.resolve(m.group(1)))) return m.group(1);
        }
        String prefix = flavor.name().toLowerCase() + "-";
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(f -> f.getFileName().toString())
                    .filter(n -> n.startsWith(prefix) && n.endsWith(".jar") && !n.contains("installer"))
                    .min(Comparator.comparing((String n) -> !n.endsWith("-shim.jar")).thenComparing(n -> n))
                    .orElse(null);
        }
    }

    /** Argumentos tras las opciones de memoria: "-jar x.jar" o "@.../win_args.txt" según el sistema. */
    public static List<String> launchArgs(String launch, boolean windows) {
        if (launch.startsWith("@")) {
            return List.of(windows ? launch.replace("unix_args.txt", "win_args.txt") : launch);
        }
        return List.of("-jar", launch);
    }
}
