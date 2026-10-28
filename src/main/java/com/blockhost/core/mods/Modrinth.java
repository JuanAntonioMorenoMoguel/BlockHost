package com.blockhost.core.mods;

import com.blockhost.core.Http;
import com.blockhost.core.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cliente mínimo de la API v2 de Modrinth (https://docs.modrinth.com/api). No necesita clave. */
public final class Modrinth implements ModRepository {

    public static final Modrinth INSTANCE = new Modrinth();
    private static final String API = "https://api.modrinth.com/v2";

    private Modrinth() { }

    @Override public String id() { return "modrinth"; }

    @Override public String displayName() { return "Modrinth"; }

    /** Los mods que solo funcionan en el cliente se excluyen: en un servidor no sirven. */
    @Override
    public List<Project> search(String query, String projectType, List<String> loaders, String mcVersion, int limit)
            throws Exception {
        List<List<String>> facets = new ArrayList<>();
        facets.add(List.of("project_type:" + projectType));
        if (loaders != null && !loaders.isEmpty()) facets.add(loaders.stream().map(l -> "categories:" + l).toList());
        if (mcVersion != null) facets.add(List.of("versions:" + mcVersion));
        if (projectType.equals("mod")) facets.add(List.of("server_side:required", "server_side:optional"));
        String url = API + "/search?limit=" + limit + "&index=relevance"
                + "&query=" + enc(query == null ? "" : query.trim())
                + "&facets=" + enc(Json.write(facets).replace("\n", ""));
        List<Project> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(Http.getJson(url)).get("hits"))) out.add(project(Json.obj(o)));
        return out;
    }

    @Override
    public Project project(String idOrSlug) throws Exception {
        Object o = Http.getJsonOrNull(API + "/project/" + enc(idOrSlug));
        return o == null ? null : project(Json.obj(o));
    }

    @Override
    public List<Version> versions(String idOrSlug, List<String> loaders, String mcVersion) throws Exception {
        StringBuilder url = new StringBuilder(API + "/project/" + enc(idOrSlug) + "/version?");
        if (loaders != null && !loaders.isEmpty()) {
            url.append("loaders=").append(enc(Json.write(new ArrayList<Object>(loaders)).replace("\n", ""))).append('&');
        }
        if (mcVersion != null) url.append("game_versions=").append(enc("[\"" + mcVersion + "\"]"));
        Object o = Http.getJsonOrNull(url.toString());
        List<Version> out = new ArrayList<>();
        if (o == null) return out;
        for (Object v : Json.arr(o)) out.add(version(Json.obj(v)));
        return out;
    }

    @Override
    public Version version(String projectId, String versionId) throws Exception {
        Object o = Http.getJsonOrNull(API + "/version/" + enc(versionId));
        return o == null ? null : version(Json.obj(o));
    }

    /** Identifica ficheros por su SHA-1: sha1 -> versión de Modrinth (los que no son de Modrinth no salen). */
    public Map<String, Version> versionsByHash(List<String> sha1s) throws Exception {
        Map<String, Version> out = new LinkedHashMap<>();
        if (sha1s.isEmpty()) return out;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hashes", new ArrayList<Object>(sha1s));
        body.put("algorithm", "sha1");
        Json.obj(Http.postJson(API + "/version_files", body))
                .forEach((hash, v) -> out.put(hash, version(Json.obj(v))));
        return out;
    }

    /**
     * La versión más reciente de cada fichero para esos loaders y esa versión de Minecraft: sha1 -> versión.
     * Si la más reciente es la que ya tienes, viene la misma; los que no son de Modrinth no salen.
     */
    public Map<String, Version> latestByHash(List<String> sha1s, List<String> loaders, String mcVersion) throws Exception {
        Map<String, Version> out = new LinkedHashMap<>();
        if (sha1s.isEmpty()) return out;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hashes", new ArrayList<Object>(sha1s));
        body.put("algorithm", "sha1");
        body.put("loaders", new ArrayList<Object>(loaders));
        body.put("game_versions", new ArrayList<Object>(List.of(mcVersion)));
        Json.obj(Http.postJson(API + "/version_files/update", body))
                .forEach((hash, v) -> out.put(hash, version(Json.obj(v))));
        return out;
    }

    // ---------------- parseo ----------------

    static Project project(Map<String, Object> m) {
        String id = Json.str(m.containsKey("project_id") ? m.get("project_id") : m.get("id"));
        String type = Json.str(m.get("project_type"));
        String slug = Json.str(m.get("slug"));
        String icon = Json.str(m.get("icon_url"));
        return new Project("modrinth", id, slug, Json.str(m.get("title")), Json.str(m.get("description")),
                Json.str(m.get("author")), Json.num(m.get("downloads"), 0), type,
                "unsupported".equals(Json.str(m.get("server_side"))), "required".equals(Json.str(m.get("client_side"))),
                icon == null || icon.isBlank() ? null : icon,
                "https://modrinth.com/" + (type == null ? "project" : type) + "/" + (slug != null ? slug : id));
    }

    static Version version(Map<String, Object> m) {
        List<Dependency> deps = new ArrayList<>();
        if (m.get("dependencies") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> d = Json.obj(o);
                deps.add(new Dependency(Json.str(d.get("project_id")), Json.str(d.get("version_id")),
                        Json.str(d.get("dependency_type"))));
            }
        }
        File primary = null;
        if (m.get("files") instanceof List<?> files) {
            for (Object o : files) {
                Map<String, Object> f = Json.obj(o);
                File file = new File(Json.str(f.get("url")), Json.str(f.get("filename")),
                        Json.str(Json.path(f, "hashes", "sha1")), Json.str(Json.path(f, "hashes", "sha512")),
                        Json.num(f.get("size"), -1));
                if (primary == null || Json.bool(f.get("primary"), false)) primary = file;
            }
        }
        return new Version("modrinth", Json.str(m.get("id")), Json.str(m.get("project_id")),
                Json.str(m.get("version_number")), Json.str(m.get("version_type")),
                strings(m.get("game_versions")), strings(m.get("loaders")), deps, primary);
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
