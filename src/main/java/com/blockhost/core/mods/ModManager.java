package com.blockhost.core.mods;

import com.blockhost.core.AppPaths;
import com.blockhost.core.Hashes;
import com.blockhost.core.Http;
import com.blockhost.core.JavaRuntimeManager;
import com.blockhost.core.Json;
import com.blockhost.core.ProgressListener;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerType;
import com.blockhost.core.mods.CompatibilityChecker.Environment;
import com.blockhost.core.mods.CompatibilityChecker.Issue;
import com.blockhost.core.providers.FabricProvider;
import com.blockhost.core.providers.ForgeProvider;

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
 * Mods de un servidor Fabric, Forge o NeoForge, o plugins de un servidor Paper: listar, activar/desactivar,
 * quitar, añadir .jar propios, instalar y actualizar desde Modrinth resolviendo dependencias, e instalar
 * modpacks .mrpack. Todo lo nuevo se descarga primero a una carpeta temporal y se comprueba su compatibilidad
 * antes de tocar la carpeta mods (o plugins).
 */
public final class ModManager {

    private static final String STORE = "blockhost-mods.json";
    private static final Pattern JAVA_DIR = Pattern.compile("java-(\\d+)");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ServerInstance instance;
    private final ServerType type;

    public ModManager(ServerInstance instance) {
        this.instance = instance;
        this.type = instance.config().type();
    }

    public ServerType type() { return type; }

    /** Loaders con los que se busca en Modrinth para este servidor. */
    public List<String> loaders() { return type.modrinthLoaders(); }

    public Path modsDir() throws IOException { return Files.createDirectories(instance.dir().resolve(type.addonFolder())); }

    // ---------------- estado ----------------

    public synchronized List<InstalledMod> list() throws IOException {
        Map<String, ModSource> sources = sources();
        List<InstalledMod> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(modsDir())) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                String n = f.getFileName().toString();
                boolean enabled = n.endsWith(".jar");
                if (!enabled && !n.endsWith(".jar" + InstalledMod.DISABLED_SUFFIX)) continue;
                out.add(read(f, enabled, sources, type.addonLoader()));
            }
        }
        out.sort(Comparator.comparing(m -> m.displayName().toLowerCase()));
        return out;
    }

    private static InstalledMod read(Path f, boolean enabled, Map<String, ModSource> sources, ModMetadata.Loader prefer) {
        String jar = f.getFileName().toString().replaceFirst("\\.disabled$", "");
        try {
            return new InstalledMod(f, enabled, ModMetadata.read(f, prefer), null, sources.get(jar));
        } catch (IOException e) {
            return new InstalledMod(f, enabled, null, e.getMessage(), sources.get(jar));
        }
    }

    /** Minecraft, loader y Java del servidor, para comprobar los rangos que piden los mods. */
    public Environment environment() {
        String loader = instance.loaderVersion() != null ? instance.loaderVersion()
                : type == ServerType.FABRIC ? detectLoader() : null;
        Matcher m = JAVA_DIR.matcher(String.valueOf(instance.javaPath()));
        int java = m.find() ? Integer.parseInt(m.group(1)) : 0;
        return new Environment(type.addonLoader(), instance.config().mcVersion(), loader, java);
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
    public record Staged(Path dir, List<StagedFile> files, List<Issue> introduced, List<String> notes, List<Path> replaces) {
        public Staged(Path dir, List<StagedFile> files, List<Issue> introduced, List<String> notes) {
            this(dir, files, introduced, notes, List.of());
        }

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

    /**
     * Descarga a la carpeta temporal lo que indica el plan y comprueba la compatibilidad. Las dependencias que
     * ya están instaladas con el mismo id de mod (p. ej. Fabric API bajada de otro sitio) no se duplican.
     */
    public Staged stage(InstallPlan plan, ProgressListener p) throws Exception {
        Path dir = newStagingDir();
        List<StagedFile> files = new ArrayList<>();
        List<String> notes = new ArrayList<>(plan.notes());
        try {
            Set<String> installedIds = new HashSet<>();
            for (InstalledMod m : list()) {
                if (m.enabled() && m.meta() != null && m.meta().id() != null) installedIds.add(m.meta().id().toLowerCase());
            }
            int i = 0;
            for (InstallPlan.Item it : plan.items()) {
                ModRepository.File f = it.version().file();
                Path target = Mrpack.safeResolve(dir, f.fileName());
                p.onProgress("Descargando " + it.project().title() + " (" + (++i) + "/" + plan.items().size() + ")", -1);
                Http.download(f.url(), target, it.project().title(), p);
                Hashes.verify(target, f.sha1(), f.sha512());
                String id = modId(target);
                if (it.requiredBy() != null && id != null && installedIds.contains(id.toLowerCase())) {
                    Files.delete(target);
                    notes.add(it.project().title() + " (que pide " + it.requiredBy() + ") ya lo tienes instalado.");
                    continue;
                }
                files.add(new StagedFile(target, new ModSource(it.project().repo(), it.project().id(), it.version().id(),
                        it.project().title(), it.project().repo())));
            }
        } catch (Exception e) {
            JavaRuntimeManager.deleteRecursively(dir);
            throw e;
        }
        return evaluate(dir, files, notes);
    }

    private String modId(Path jar) {
        try {
            return ModMetadata.read(jar, type.addonLoader()).id();
        } catch (IOException e) {
            return null;
        }
    }

    private Staged evaluate(Path dir, List<StagedFile> files, List<String> notes) throws IOException {
        return evaluate(dir, files, notes, List.of());
    }

    /** @param replaces ficheros instalados que desaparecen al confirmar (las versiones viejas de una actualización) */
    private Staged evaluate(Path dir, List<StagedFile> files, List<String> notes, List<Path> replaces) throws IOException {
        List<InstalledMod> current = list();
        List<InstalledMod> after = new ArrayList<>(current);
        Set<String> names = new HashSet<>();
        for (StagedFile f : files) names.add(f.file().getFileName().toString());
        after.removeIf(m -> names.contains(m.jarName()) || replaces.contains(m.file())); // mismo nombre = se sustituye
        for (StagedFile f : files) after.add(read(f.file(), true, Map.of(), type.addonLoader()));
        Environment env = environment();
        List<Issue> introduced = CompatibilityChecker.introduced(
                CompatibilityChecker.check(current, env), CompatibilityChecker.check(after, env));
        return new Staged(dir, files, introduced, notes, replaces);
    }

    /** Pasa los ficheros preparados a la carpeta mods, quita los que sustituyen y recuerda su origen. */
    public synchronized void commit(Staged staged) throws IOException {
        Path mods = modsDir();
        Map<String, Object> store = store();
        Map<String, Object> sources = Json.obj(store.computeIfAbsent("mods", k -> new LinkedHashMap<String, Object>()));
        for (Path old : staged.replaces()) {
            String n = old.getFileName().toString().replaceFirst("\\.disabled$", "");
            Files.deleteIfExists(old);
            sources.remove(n);
        }
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

    // ---------------- actualizaciones ----------------

    /** Una versión más nueva en Modrinth de un mod instalado. */
    public record Update(InstalledMod mod, ModRepository.Version version) {
        @Override
        public String toString() {
            return mod.displayName() + "  " + mod.version() + "  →  " + version.versionNumber();
        }
    }

    /**
     * Busca versiones más nuevas de los mods activos para este Minecraft y loader. Solo se pueden actualizar
     * los que están en Modrinth (se reconocen por la huella del fichero, aunque se añadieran a mano).
     * Si tienes una versión estable, no se ofrecen betas.
     */
    public List<Update> checkUpdates(ProgressListener p) throws Exception {
        p.onProgress("Buscando actualizaciones en Modrinth...", -1);
        Map<String, InstalledMod> byHash = new LinkedHashMap<>();
        for (InstalledMod m : list()) if (m.enabled()) byHash.put(Hashes.sha1(m.file()), m);
        if (byHash.isEmpty()) return List.of();
        List<String> hashes = new ArrayList<>(byHash.keySet());
        Map<String, ModRepository.Version> current = Modrinth.INSTANCE.versionsByHash(hashes);
        Map<String, ModRepository.Version> latest = Modrinth.INSTANCE.latestByHash(hashes, loaders(),
                instance.config().mcVersion());
        List<Update> out = new ArrayList<>();
        for (Map.Entry<String, ModRepository.Version> e : latest.entrySet()) {
            InstalledMod mod = byHash.get(e.getKey());
            ModRepository.Version v = e.getValue();
            if (mod == null || v.file() == null || v.file().url() == null) continue;
            if (e.getKey().equalsIgnoreCase(v.file().sha1())) continue; // ya es la última
            ModRepository.Version now = current.get(e.getKey());
            if (now != null && now.id().equals(v.id())) continue;
            if (now != null && "release".equals(now.versionType()) && !"release".equals(v.versionType())) continue;
            out.add(new Update(mod, v));
        }
        out.sort(Comparator.comparing(u -> u.mod().displayName().toLowerCase()));
        return out;
    }

    /** Descarga las versiones nuevas y comprueba cómo queda todo sin las viejas. Se confirma con commit. */
    public Staged stageUpdates(List<Update> updates, ProgressListener p) throws Exception {
        Path dir = newStagingDir();
        List<StagedFile> files = new ArrayList<>();
        List<Path> replaces = new ArrayList<>();
        try {
            int i = 0;
            for (Update u : updates) {
                ModRepository.File f = u.version().file();
                Path target = Mrpack.safeResolve(dir, f.fileName());
                p.onProgress("Descargando " + u.mod().displayName() + " " + u.version().versionNumber()
                        + " (" + (++i) + "/" + updates.size() + ")", -1);
                Http.download(f.url(), target, u.mod().displayName(), p);
                Hashes.verify(target, f.sha1(), f.sha512());
                files.add(new StagedFile(target, new ModSource("modrinth", u.version().projectId(), u.version().id(),
                        u.mod().displayName(), "modrinth")));
                replaces.add(u.mod().file());
            }
        } catch (Exception e) {
            JavaRuntimeManager.deleteRecursively(dir);
            throw e;
        }
        return evaluate(dir, files, List.of(), replaces);
    }

    // ---------------- Modrinth ----------------

    /**
     * Qué hay que descargar para instalar un proyecto: la versión más reciente compatible con este Minecraft
     * y Fabric, más sus dependencias obligatorias (recursivamente) que no estén ya instaladas.
     */
    public InstallPlan plan(ModRepository repo, String projectId, ProgressListener p) throws Exception {
        return plan(repo, List.of(projectId), p);
    }

    /** Igual que plan(repo, id), para varios proyectos a la vez (p. ej. todas las dependencias que faltan). */
    public InstallPlan plan(ModRepository repo, List<String> projectIds, ProgressListener p) throws Exception {
        String mc = instance.config().mcVersion();
        p.onProgress("Comprobando qué mods tienes ya...", -1);
        Map<String, String> installed = installedProjects(); // repo:projectId -> nombre

        List<InstallPlan.Item> items = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, String> incompatible = new LinkedHashMap<>(); // projectId -> quién lo declara
        Set<String> seen = new HashSet<>();
        Deque<String[]> queue = new ArrayDeque<>(); // {projectId, versionId|null, pedido por|null}
        for (String root : projectIds) queue.add(new String[]{root, null, null});

        while (!queue.isEmpty()) {
            String[] next = queue.poll();
            String requestedBy = next[2];
            ModRepository.Project project = repo.project(next[0]);
            if (project == null) {
                problems.add("No existe en " + repo.displayName() + ": " + next[0]);
                continue;
            }
            if (!seen.add(project.id())) continue;
            p.onProgress("Buscando versión compatible de " + project.title() + "...", -1);

            if (installed.containsKey(repo.id() + ":" + project.id())) {
                if (requestedBy == null) problems.add(project.title() + " ya está instalado.");
                continue;
            }
            if (project.clientOnly()) {
                if (requestedBy == null) problems.add(project.title() + " solo funciona en el juego de los jugadores, "
                        + "no en un servidor.");
                else notes.add(project.title() + " (que pide " + requestedBy + ") es solo de cliente: no se instala en el servidor.");
                continue;
            }

            ModRepository.Version v = null;
            if (next[1] != null) {
                ModRepository.Version pinned = repo.version(project.id(), next[1]);
                if (pinned != null && pinned.gameVersions().contains(mc)
                        && pinned.loaders().stream().anyMatch(loaders()::contains)) v = pinned;
            }
            if (v == null) v = ModRepository.best(repo.versions(project.id(), loaders(), mc));
            String who = requestedBy != null ? " (lo necesita " + requestedBy + ")" : "";
            if (v == null || v.file() == null) {
                problems.add(project.title() + who + " no tiene versión para " + type.displayName + " y Minecraft " + mc + ".");
                continue;
            }
            if (v.file().url() == null) {
                problems.add(project.title() + who + " no tiene enlace de descarga. Bájalo de "
                        + project.pageUrl() + " y añádelo con «Añadir .jar desde tu PC».");
                continue;
            }
            items.add(new InstallPlan.Item(project, v, requestedBy));

            for (ModRepository.Dependency d : v.dependencies()) {
                String pid = d.projectId();
                if (pid == null && d.versionId() != null) {
                    ModRepository.Version dv = repo.version(null, d.versionId());
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
            String key = repo.id() + ":" + e.getKey();
            String other = installed.containsKey(key) ? installed.get(key)
                    : planned.contains(e.getKey()) ? titleOf(items, e.getKey()) : null;
            if (other != null) problems.add(e.getValue() + " es incompatible con " + other + ".");
        }
        return new InstallPlan(items, problems, notes);
    }

    /**
     * Ids de los mods que faltan según la comprobación. Suelen coincidir con el slug de Modrinth (los nombres
     * de plugin, en minúsculas).
     */
    public static List<String> missingDependencies(List<Issue> issues) {
        List<String> ids = new ArrayList<>();
        for (Issue i : issues) {
            String id = i.missingId() == null ? null : i.missingId().toLowerCase().replace(' ', '-');
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private static String titleOf(List<InstallPlan.Item> items, String projectId) {
        return items.stream().filter(i -> i.project().id().equals(projectId))
                .map(i -> i.project().title()).findFirst().orElse(projectId);
    }

    /**
     * Proyectos ya presentes como "repo:projectId": por lo guardado al instalarlos y, si no, por la huella
     * del fichero en Modrinth.
     */
    private Map<String, String> installedProjects() throws Exception {
        Map<String, String> out = new HashMap<>();
        Map<String, InstalledMod> byHash = new HashMap<>();
        for (InstalledMod m : list()) {
            ModSource src = m.source();
            if (src != null && src.repo() != null && src.projectId() != null) {
                out.put(src.repo() + ":" + src.projectId(), m.displayName());
            } else {
                byHash.put(Hashes.sha1(m.file()), m);
            }
        }
        if (!byHash.isEmpty()) {
            try {
                Modrinth.INSTANCE.versionsByHash(new ArrayList<>(byHash.keySet())).forEach((hash, v) ->
                        out.put("modrinth:" + v.projectId(), byHash.get(hash).displayName()));
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
        ServerType packType = ServerType.forModpackLoader(loader);
        if (packType == null) {
            out.add("El modpack usa " + (loader == null ? "Minecraft sin mods" : loader)
                    + " y BlockHost instala modpacks de Fabric, Forge y NeoForge.");
        } else if (packType != type) {
            out.add("El modpack es para " + packType.displayName + " y este servidor es " + type.displayName
                    + ". Crea un servidor nuevo desde el modpack.");
        }
        if (!pack.mcVersion().equals(instance.config().mcVersion())) {
            out.add("El modpack es para Minecraft " + pack.mcVersion() + " y este servidor es "
                    + instance.config().mcVersion() + ". Crea un servidor nuevo desde el modpack.");
        }
        return out;
    }

    /**
     * Instala el modpack: descarga sus mods de servidor, pone la versión del loader que pide y
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
                p.onProgress("Instalando " + type.displayName + " " + loader + " (el que pide el modpack)...", -1);
                if (type == ServerType.FABRIC) {
                    FabricProvider.installLoader(pack.mcVersion(), loader, instance.dir(), p);
                } else {
                    ForgeProvider.Flavor flavor = type == ServerType.FORGE ? ForgeProvider.Flavor.FORGE : ForgeProvider.Flavor.NEOFORGE;
                    instance.setJarName(ForgeProvider.install(flavor, pack.mcVersion(), loader, instance.dir(),
                            instance.javaPath(), p));
                }
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
                                    new ModSource(null, null, null, null, "modpack:" + pack.name()).toMap());
                        }
                    }
                }
                Mrpack.extractOverrides(pack, mrpackFile, instance.dir());

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
