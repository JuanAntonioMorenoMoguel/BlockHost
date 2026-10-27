package com.blockhost.core.mods;

import com.blockhost.core.AppPaths;
import com.blockhost.core.Hashes;
import com.blockhost.core.Http;
import com.blockhost.core.JavaRuntimeManager;
import com.blockhost.core.Json;
import com.blockhost.core.ProgressListener;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.mods.CompatibilityChecker.Environment;
import com.blockhost.core.mods.CompatibilityChecker.Issue;
import com.blockhost.core.providers.FabricProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Mods de un servidor Fabric: listar, activar/desactivar, quitar, añadir .jar propios, instalar desde Modrinth
 * resolviendo dependencias, e instalar modpacks .mrpack. Todo lo nuevo se descarga primero a una carpeta
 * temporal y se comprueba su compatibilidad antes de tocar la carpeta mods.
 */
public final class ModManager {

    public static final String LOADER = "fabric";
    private static final String STORE = "blockhost-mods.json";
    private static final Pattern JAVA_DIR = Pattern.compile("java-(\\d+)");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ServerInstance instance;

    public ModManager(ServerInstance instance) { this.instance = instance; }

    public Path modsDir() throws IOException { return Files.createDirectories(instance.dir().resolve("mods")); }

    // ---------------- estado ----------------

    public synchronized List<InstalledMod> list() throws IOException {
        Map<String, ModSource> sources = sources();
        List<InstalledMod> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(modsDir())) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                String n = f.getFileName().toString();
                boolean enabled = n.endsWith(".jar");
                if (!enabled && !n.endsWith(".jar" + InstalledMod.DISABLED_SUFFIX)) continue;
                out.add(read(f, enabled, sources));
            }
        }
        out.sort(Comparator.comparing(m -> m.displayName().toLowerCase()));
        return out;
    }

    private static InstalledMod read(Path f, boolean enabled, Map<String, ModSource> sources) {
        String jar = f.getFileName().toString().replaceFirst("\\.disabled$", "");
        try {
            return new InstalledMod(f, enabled, ModMetadata.read(f), null, sources.get(jar));
        } catch (IOException e) {
            return new InstalledMod(f, enabled, null, e.getMessage(), sources.get(jar));
        }
    }

    /** Minecraft, Fabric Loader y Java del servidor, para comprobar los rangos que piden los mods. */
    public Environment environment() {
        String loader = instance.loaderVersion() != null ? instance.loaderVersion() : detectLoader();
        Matcher m = JAVA_DIR.matcher(String.valueOf(instance.javaPath()));
        int java = m.find() ? Integer.parseInt(m.group(1)) : 0;
        return new Environment(instance.config().mcVersion(), loader, java);
    }

    /** Servidores creados antes de guardar loaderVersion: se deduce de las librerías que baja el lanzador. */
    private String detectLoader() {
        Path libs = instance.dir().resolve("libraries/net/fabricmc/fabric-loader");
        if (!Files.isDirectory(libs)) return null;
        try (Stream<Path> s = Files.list(libs)) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .max(VersionPredicate::compare).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public List<Issue> check() throws IOException { return CompatibilityChecker.check(list(), environment()); }

    public Map<String, Object> modpackInfo() throws IOException {
        Object o = store().get("modpack");
        return o instanceof Map<?, ?> ? Json.obj(o) : null;
    }

    // ---------------- cambios sencillos ----------------

    public synchronized void setEnabled(InstalledMod mod, boolean enabled) throws IOException {
        if (mod.enabled() == enabled) return;
        Path target = mod.file().resolveSibling(enabled ? mod.jarName() : mod.jarName() + InstalledMod.DISABLED_SUFFIX);
        Files.move(mod.file(), target, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized void remove(InstalledMod mod) throws IOException {
        Files.deleteIfExists(mod.file());
        Map<String, Object> store = store();
        Json.obj(store.computeIfAbsent("mods", k -> new LinkedHashMap<String, Object>())).remove(mod.jarName());
        saveStore(store);
    }

    // ---------------- instalación por fases: preparar -> comprobar -> confirmar ----------------

    /** Un fichero listo para pasar a la carpeta mods. */
    public record StagedFile(Path file, ModSource source) { }

    /**
     * Ficheros ya descargados en una carpeta temporal y su efecto en la compatibilidad.
     *
     * @param introduced problemas que aparecerían al instalarlos y que hoy no existen
     * @param notes      avisos de la preparación (dependencias opcionales, mods de cliente omitidos...)
     */
    public record Staged(Path dir, List<StagedFile> files, List<Issue> introduced, List<String> notes) {
        public boolean breaksSomething() { return CompatibilityChecker.hasErrors(introduced); }
    }

    /** Prepara .jar elegidos por el usuario desde su disco. */
    public Staged stageLocal(List<Path> jars) throws IOException {
        Path dir = newStagingDir();
        List<StagedFile> files = new ArrayList<>();
        for (Path j : jars) {
            Path copy = dir.resolve(j.getFileName().toString());
            Files.copy(j, copy, StandardCopyOption.REPLACE_EXISTING);
            files.add(new StagedFile(copy, null));
        }
        return evaluate(dir, files, List.of());
    }

    /** Descarga a la carpeta temporal lo que indica el plan y comprueba la compatibilidad. */
    public Staged stage(InstallPlan plan, ProgressListener p) throws Exception {
        Path dir = newStagingDir();
        List<StagedFile> files = new ArrayList<>();
        try {
            int i = 0;
            for (InstallPlan.Item it : plan.items()) {
                Modrinth.File f = it.version().file();
                Path target = Mrpack.safeResolve(dir, f.fileName());
                p.onProgress("Descargando " + it.project().title() + " (" + (++i) + "/" + plan.items().size() + ")", -1);
                Http.download(f.url(), target, it.project().title(), p);
                Hashes.verify(target, f.sha1(), f.sha512());
                files.add(new StagedFile(target, new ModSource(it.project().id(), it.version().id(),
                        it.project().title(), "modrinth")));
            }
        } catch (Exception e) {
            JavaRuntimeManager.deleteRecursively(dir);
            throw e;
        }
        return evaluate(dir, files, plan.notes());
    }

    private Staged evaluate(Path dir, List<StagedFile> files, List<String> notes) throws IOException {
        List<InstalledMod> current = list();
        List<InstalledMod> after = new ArrayList<>(current);
        Set<String> names = new HashSet<>();
        for (StagedFile f : files) names.add(f.file().getFileName().toString());
        after.removeIf(m -> names.contains(m.jarName())); // mismo nombre de fichero = se sustituye
        for (StagedFile f : files) after.add(read(f.file(), true, Map.of()));
        Environment env = environment();
        List<Issue> introduced = CompatibilityChecker.introduced(
                CompatibilityChecker.check(current, env), CompatibilityChecker.check(after, env));
        return new Staged(dir, files, introduced, notes);
    }

    /** Pasa los ficheros preparados a la carpeta mods y recuerda su origen. */
    public synchronized void commit(Staged staged) throws IOException {
        Path mods = modsDir();
        Map<String, Object> store = store();
        Map<String, Object> sources = Json.obj(store.computeIfAbsent("mods", k -> new LinkedHashMap<String, Object>()));
        for (StagedFile f : staged.files()) {
            String name = f.file().getFileName().toString();
            Files.deleteIfExists(mods.resolve(name + InstalledMod.DISABLED_SUFFIX));
            Files.move(f.file(), mods.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            if (f.source() != null) sources.put(name, f.source().toMap());
            else sources.remove(name);
        }
        saveStore(store);
        discard(staged);
    }

    public void discard(Staged staged) {
        try {
            JavaRuntimeManager.deleteRecursively(staged.dir());
        } catch (IOException ignored) {
            // es la caché: se limpiará en otra ocasión
        }
    }

    // ---------------- Modrinth ----------------

    /**
     * Qué hay que descargar para instalar un proyecto de Modrinth: la versión más reciente compatible con
     * este Minecraft y Fabric, más sus dependencias obligatorias (recursivamente) que no estén ya instaladas.
     */
    public InstallPlan plan(String projectIdOrSlug, ProgressListener p) throws Exception {
        return plan(List.of(projectIdOrSlug), p);
    }

    /** Igual que plan(String), para varios proyectos a la vez (p. ej. todas las dependencias que faltan). */
    public InstallPlan plan(List<String> projectIdsOrSlugs, ProgressListener p) throws Exception {
        String mc = instance.config().mcVersion();
        p.onProgress("Comprobando qué mods tienes ya...", -1);
        Map<String, String> installed = installedProjects(); // projectId -> nombre

        List<InstallPlan.Item> items = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, String> incompatible = new LinkedHashMap<>(); // projectId -> quién lo declara
        Set<String> seen = new HashSet<>();
        Deque<String[]> queue = new ArrayDeque<>(); // {projectId, versionId|null, pedido por|null}
        for (String root : projectIdsOrSlugs) queue.add(new String[]{root, null, null});

        while (!queue.isEmpty()) {
            String[] next = queue.poll();
            String requestedBy = next[2];
            Modrinth.Project project = Modrinth.project(next[0]);
            if (project == null) {
                problems.add("No existe en Modrinth: " + next[0]);
                continue;
            }
            if (!seen.add(project.id())) continue;
            p.onProgress("Buscando versión compatible de " + project.title() + "...", -1);

            if (installed.containsKey(project.id())) {
                if (requestedBy == null) problems.add(project.title() + " ya está instalado.");
                continue;
            }
            if (project.clientOnly()) {
                if (requestedBy == null) problems.add(project.title() + " solo funciona en el juego de los jugadores, "
                        + "no en un servidor.");
                else notes.add(project.title() + " (que pide " + requestedBy + ") es solo de cliente: no se instala en el servidor.");
                continue;
            }

            Modrinth.Version v = null;
            if (next[1] != null) {
                Modrinth.Version pinned = Modrinth.version(next[1]);
                if (pinned != null && pinned.gameVersions().contains(mc) && pinned.loaders().contains(LOADER)) v = pinned;
            }
            if (v == null) v = Modrinth.best(Modrinth.versions(project.id(), LOADER, mc));
            if (v == null || v.file() == null) {
                problems.add(project.title() + (requestedBy != null ? " (lo necesita " + requestedBy + ")" : "")
                        + " no tiene versión para Fabric y Minecraft " + mc + ".");
                continue;
            }
            items.add(new InstallPlan.Item(project, v, requestedBy));

            for (Modrinth.Dependency d : v.dependencies()) {
                String pid = d.projectId();
                if (pid == null && d.versionId() != null) {
                    Modrinth.Version dv = Modrinth.version(d.versionId());
                    pid = dv == null ? null : dv.projectId();
                }
                if (pid == null) continue;
                switch (String.valueOf(d.type())) {
                    case "required" -> queue.add(new String[]{pid, d.versionId(), project.title()});
                    case "incompatible" -> incompatible.put(pid, project.title());
                    default -> { } // optional / embedded: no hace falta
                }
            }
        }

        Set<String> planned = new HashSet<>();
        items.forEach(it -> planned.add(it.project().id()));
        for (Map.Entry<String, String> e : incompatible.entrySet()) {
            String other = installed.containsKey(e.getKey()) ? installed.get(e.getKey())
                    : planned.contains(e.getKey()) ? titleOf(items, e.getKey()) : null;
            if (other != null) problems.add(e.getValue() + " es incompatible con " + other + ".");
        }
        return new InstallPlan(items, problems, notes);
    }

    /** Ids de los mods que faltan según la comprobación. En Fabric suelen coincidir con el slug de Modrinth. */
    public static List<String> missingDependencies(List<Issue> issues) {
        List<String> ids = new ArrayList<>();
        for (Issue i : issues) {
            if (i.missingId() != null && !ids.contains(i.missingId())) ids.add(i.missingId());
        }
        return ids;
    }

    private static String titleOf(List<InstallPlan.Item> items, String projectId) {
        return items.stream().filter(i -> i.project().id().equals(projectId))
                .map(i -> i.project().title()).findFirst().orElse(projectId);
    }

    /** Proyectos de Modrinth ya presentes: por lo guardado al instalarlos y, si no, por la huella del fichero. */
    private Map<String, String> installedProjects() throws Exception {
        Map<String, String> out = new HashMap<>();
        Map<String, InstalledMod> byHash = new HashMap<>();
        for (InstalledMod m : list()) {
            if (m.source() != null && m.source().projectId() != null) out.put(m.source().projectId(), m.displayName());
            else byHash.put(Hashes.sha1(m.file()), m);
        }
        if (!byHash.isEmpty()) {
            try {
                Modrinth.versionsByHash(new ArrayList<>(byHash.keySet())).forEach((hash, v) ->
                        out.put(v.projectId(), byHash.get(hash).displayName()));
            } catch (Exception ignored) {
                // sin esta consulta solo se pierde detectar duplicados de mods añadidos a mano
            }
        }
        return out;
    }

    // ---------------- modpacks ----------------

    /** Motivos por los que el pack no se puede instalar en este servidor (vacío = se puede). */
    public List<String> modpackBlockers(Mrpack.Pack pack) {
        List<String> out = new ArrayList<>();
        String loader = pack.loader();
        if (!LOADER.equals(loader)) {
            out.add("El modpack usa " + (loader == null ? "Minecraft sin mods" : loader)
                    + " y BlockHost de momento solo instala modpacks de Fabric.");
        }
        if (!pack.mcVersion().equals(instance.config().mcVersion())) {
            out.add("El modpack es para Minecraft " + pack.mcVersion() + " y este servidor es "
                    + instance.config().mcVersion() + ". Crea un servidor nuevo desde el modpack.");
        }
        return out;
    }

    /**
     * Instala el modpack: descarga sus mods de servidor, pone la versión de Fabric Loader que pide y
     * aplica sus overrides. Los mods que hubiera se apartan a una carpeta "mods-antes-de-..." por si acaso.
     *
     * @return problemas de compatibilidad tras instalarlo
     */
    public List<Issue> installModpack(Mrpack.Pack pack, Path mrpackFile, ProgressListener p) throws Exception {
        List<String> blockers = modpackBlockers(pack);
        if (!blockers.isEmpty()) throw new IllegalStateException(String.join("\n", blockers));

        Path staging = newStagingDir();
        try {
            Mrpack.downloadFiles(pack, staging, p);

            String loader = pack.loaderVersion();
            if (loader != null && !loader.equals(environment().loaderVersion())) {
                p.onProgress("Instalando Fabric Loader " + loader + " (el que pide el modpack)...", -1);
                FabricProvider.installLoader(pack.mcVersion(), loader, instance.dir(), p);
                instance.setLoaderVersion(loader);
                instance.save();
            }

            synchronized (this) {
                Path mods = modsDir();
                try (Stream<Path> s = Files.list(mods)) {
                    if (s.findAny().isPresent()) {
                        Path old = instance.dir().resolve("mods-antes-de-" + LocalDateTime.now().format(STAMP));
                        Files.move(mods, old);
                        p.log("Tus mods anteriores se han movido a " + old.getFileName());
                        Files.createDirectories(mods);
                    }
                }

                p.onProgress("Copiando archivos del modpack...", -1);
                Map<String, Object> sources = new LinkedHashMap<>();
                try (Stream<Path> s = Files.walk(staging)) {
                    for (Path f : s.filter(Files::isRegularFile).toList()) {
                        Path rel = staging.relativize(f);
                        Path target = instance.dir().resolve(rel);
                        Files.createDirectories(target.getParent());
                        Files.move(f, target, StandardCopyOption.REPLACE_EXISTING);
                        if (rel.getNameCount() == 2 && rel.getName(0).toString().equals("mods")) {
                            sources.put(rel.getFileName().toString(),
                                    new ModSource(null, null, null, "modpack:" + pack.name()).toMap());
                        }
                    }
                }
                Mrpack.extractOverrides(mrpackFile, instance.dir());

                Map<String, Object> store = new LinkedHashMap<>();
                Map<String, Object> packInfo = new LinkedHashMap<>();
                packInfo.put("name", pack.name());
                packInfo.put("version", pack.versionId());
                packInfo.put("mcVersion", pack.mcVersion());
                packInfo.put("loaderVersion", loader);
                store.put("modpack", packInfo);
                store.put("mods", sources);
                saveStore(store);
            }
        } finally {
            JavaRuntimeManager.deleteRecursively(staging);
        }
        p.onProgress("Modpack instalado", 1);
        return check();
    }

    // ---------------- utilidades ----------------

    private static Path newStagingDir() throws IOException {
        return Files.createDirectories(AppPaths.cache().resolve("staging").resolve(UUID.randomUUID().toString()));
    }

    private Map<String, Object> store() throws IOException {
        Path f = instance.dir().resolve(STORE);
        if (!Files.exists(f)) return new LinkedHashMap<>();
        try {
            return Json.obj(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            return new LinkedHashMap<>(); // fichero roto: se pierde solo el origen de los mods, no los mods
        }
    }

    private Map<String, ModSource> sources() throws IOException {
        Map<String, ModSource> out = new HashMap<>();
        if (store().get("mods") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> { if (v instanceof Map<?, ?>) out.put(String.valueOf(k), ModSource.fromMap(Json.obj(v))); });
        }
        return out;
    }

    private void saveStore(Map<String, Object> store) throws IOException {
        Files.writeString(instance.dir().resolve(STORE), Json.write(store), StandardCharsets.UTF_8);
    }
}
