package com.blockhost.core.mods;

import com.blockhost.core.Http;
import com.blockhost.core.Json;
import com.blockhost.core.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente de la API oficial de CurseForge (https://docs.curseforge.com/rest-api/). Exige una clave de API
 * gratuita (https://console.curseforge.com): se toma de la variable BLOCKHOST_CURSEFORGE_API_KEY, de los
 * ajustes de la app o de la que se incruste al compilar (-PcurseforgeApiKey=...).
 */
public final class CurseForge implements ModRepository {

    public static final CurseForge INSTANCE = new CurseForge();
    public static final String SETTINGS_KEY = "curseforgeApiKey";
    public static final String CONSOLE_URL = "https://console.curseforge.com/";

    private static final String API = "https://api.curseforge.com/v1";
    private static final int MINECRAFT = 432;
    private static final int CLASS_MODS = 6;
    private static final int CLASS_MODPACKS = 4471;
    private static final Map<String, Integer> LOADER_TYPES = Map.of("forge", 1, "fabric", 4, "quilt", 5, "neoforge", 6);
    private static final List<String> LOADER_NAMES = List.of("fabric", "forge", "neoforge", "quilt");

    private CurseForge() { }

    @Override public String id() { return "curseforge"; }

    @Override public String displayName() { return "CurseForge"; }

    @Override public boolean available() { return apiKey() != null; }

    public static String apiKey() {
        String env = System.getenv("BLOCKHOST_CURSEFORGE_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        String saved = Settings.get(SETTINGS_KEY);
        if (saved != null && !saved.isBlank()) return saved;
        try (InputStream in = CurseForge.class.getResourceAsStream("/com/blockhost/curseforge.key")) {
            if (in != null) {
                String k = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!k.isEmpty()) return k;
            }
        } catch (IOException ignored) {
            // sin clave incrustada
        }
        return null;
    }

    private static String[] auth() throws IOException {
        String key = apiKey();
        if (key == null) throw new IOException("Falta la clave de API de CurseForge");
        return new String[]{"x-api-key", key, "Accept", "application/json"};
    }

    // ---------------- consultas ----------------

    @Override
    public List<Project> search(String query, String projectType, String loader, String mcVersion, int limit)
            throws Exception {
        StringBuilder url = new StringBuilder(API + "/mods/search?gameId=" + MINECRAFT
                + "&classId=" + (projectType.equals("modpack") ? CLASS_MODPACKS : CLASS_MODS)
                + "&sortField=2&sortOrder=desc&pageSize=" + Math.min(50, limit)
                + "&searchFilter=" + enc(query == null ? "" : query.trim()));
        if (loader != null && LOADER_TYPES.containsKey(loader)) url.append("&modLoaderType=").append(LOADER_TYPES.get(loader));
        if (mcVersion != null) url.append("&gameVersion=").append(enc(mcVersion));
        List<Project> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(Http.getJson(url.toString(), auth())).get("data"))) {
            out.add(project(Json.obj(o), projectType));
        }
        return out;
    }

    @Override
    public Project project(String id) throws Exception {
        Object o = Http.getJsonOrNull(API + "/mods/" + enc(id), auth());
        if (o == null) return null;
        Map<String, Object> m = Json.obj(Json.obj(o).get("data"));
        return project(m, Json.num(m.get("classId"), CLASS_MODS) == CLASS_MODPACKS ? "modpack" : "mod");
    }

    @Override
    public List<Version> versions(String projectId, String loader, String mcVersion) throws Exception {
        StringBuilder url = new StringBuilder(API + "/mods/" + enc(projectId) + "/files?pageSize=50");
        if (loader != null && LOADER_TYPES.containsKey(loader)) url.append("&modLoaderType=").append(LOADER_TYPES.get(loader));
        if (mcVersion != null) url.append("&gameVersion=").append(enc(mcVersion));
        Object o = Http.getJsonOrNull(url.toString(), auth());
        List<Map<String, Object>> files = new ArrayList<>();
        if (o != null) for (Object f : Json.arr(Json.obj(o).get("data"))) files.add(Json.obj(f));
        files.sort(Comparator.comparing((Map<String, Object> f) -> String.valueOf(f.get("fileDate"))).reversed());
        List<Version> out = new ArrayList<>();
        for (Map<String, Object> f : files) {
            Version v = version(f);
            // CurseForge a veces etiqueta mal el loader: si el fichero dice otro loader y no el pedido, se descarta.
            if (loader != null && !v.loaders().isEmpty() && !v.loaders().contains(loader)) continue;
            out.add(v);
        }
        return out;
    }

    @Override
    public Version version(String projectId, String versionId) throws Exception {
        Object o = Http.getJsonOrNull(API + "/mods/" + enc(projectId) + "/files/" + enc(versionId), auth());
        return o == null ? null : version(Json.obj(Json.obj(o).get("data")));
    }

    /** Varios ficheros a la vez (para los modpacks, que los listan por id). */
    public List<Version> files(List<Long> fileIds) throws Exception {
        List<Version> out = new ArrayList<>();
        if (fileIds.isEmpty()) return out;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileIds", new ArrayList<Object>(fileIds));
        for (Object o : Json.arr(Json.obj(Http.postJson(API + "/mods/files", body, auth())).get("data"))) {
            out.add(version(Json.obj(o)));
        }
        return out;
    }

    /** Varios proyectos a la vez: id -> classId (6 = mod, 12 = paquete de recursos, 6552 = shaders...). */
    public Map<String, Long> classIds(List<Long> modIds) throws Exception {
        Map<String, Long> out = new LinkedHashMap<>();
        if (modIds.isEmpty()) return out;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modIds", new ArrayList<Object>(modIds));
        for (Object o : Json.arr(Json.obj(Http.postJson(API + "/mods", body, auth())).get("data"))) {
            Map<String, Object> m = Json.obj(o);
            out.put(String.valueOf(Json.num(m.get("id"), 0)), Json.num(m.get("classId"), CLASS_MODS));
        }
        return out;
    }

    // ---------------- parseo ----------------

    static Project project(Map<String, Object> m, String projectType) {
        String id = String.valueOf(Json.num(m.get("id"), 0));
        String author = null;
        if (m.get("authors") instanceof List<?> a && !a.isEmpty()) author = Json.str(Json.path(a.get(0), "name"));
        String page = Json.str(Json.path(m, "links", "websiteUrl"));
        if (page == null) page = "https://www.curseforge.com/minecraft/" + (projectType.equals("modpack") ? "modpacks" : "mc-mods")
                + "/" + Json.str(m.get("slug"));
        return new Project("curseforge", id, Json.str(m.get("slug")), Json.str(m.get("name")), Json.str(m.get("summary")),
                author, Json.num(m.get("downloadCount"), 0), projectType, false, false,
                Json.str(Json.path(m, "logo", "thumbnailUrl")), page);
    }

    static Version version(Map<String, Object> f) {
        List<String> gameVersions = f.get("gameVersions") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        List<String> loaders = gameVersions.stream().map(String::toLowerCase).filter(LOADER_NAMES::contains).toList();

        List<Dependency> deps = new ArrayList<>();
        if (f.get("dependencies") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> d = Json.obj(o);
                String type = switch ((int) Json.num(d.get("relationType"), 0)) {
                    case 3 -> "required";
                    case 5 -> "incompatible";
                    case 1, 6 -> "embedded";
                    default -> "optional";
                };
                deps.add(new Dependency(String.valueOf(Json.num(d.get("modId"), 0)), null, type));
            }
        }
        String sha1 = null;
        if (f.get("hashes") instanceof List<?> hashes) {
            for (Object o : hashes) {
                if (Json.num(Json.path(o, "algo"), 0) == 1) sha1 = Json.str(Json.path(o, "value"));
            }
        }
        String type = switch ((int) Json.num(f.get("releaseType"), 1)) {
            case 2 -> "beta";
            case 3 -> "alpha";
            default -> "release";
        };
        String url = Json.str(f.get("downloadUrl"));
        File file = new File(url == null || url.isBlank() ? null : url, Json.str(f.get("fileName")), sha1, null,
                Json.num(f.get("fileLength"), -1));
        return new Version("curseforge", String.valueOf(Json.num(f.get("id"), 0)), String.valueOf(Json.num(f.get("modId"), 0)),
                Json.str(f.get("displayName")), type, gameVersions, loaders, deps, file);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
