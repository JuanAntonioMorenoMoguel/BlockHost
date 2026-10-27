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
public final class Modrinth {

    private static final String API = "https://api.modrinth.com/v2";

    private Modrinth() { }

    public record Project(String id, String slug, String title, String description, String author, long downloads,
                          String projectType, String clientSide, String serverSide, List<String> gameVersions) {

        /** El servidor no lo carga: solo tiene sentido en el juego de cada jugador. */
        public boolean clientOnly() { return "unsupported".equals(serverSide); }

        public String url() { return "https://modrinth.com/" + projectType + "/" + slug; }

        @Override
        public String toString() { return title; }
    }

    public record File(String url, String fileName, String sha1, String sha512, long size) { }

    public record Dependency(String projectId, String versionId, String type) { }

    public record Version(String id, String projectId, String name, String versionNumber, String versionType,
                          List<String> gameVersions, List<String> loaders, List<Dependency> dependencies, File file) {

        public List<Dependency> dependencies(String type) {
            return dependencies.stream().filter(d -> type.equals(d.type())).toList();
        }
    }

    // ---------------- consultas ----------------

    /**
     * Busca mods o modpacks compatibles con el loader y la versión de Minecraft (null = cualquiera).
     * Los mods que solo funcionan en el cliente se excluyen: en un servidor no sirven.
     */
    public static List<Project> search(String query, String projectType, String loader, String mcVersion, int limit)
            throws Exception {
        List<List<String>> facets = new ArrayList<>();
        facets.add(List.of("project_type:" + projectType));
        if (loader != null) facets.add(List.of("categories:" + loader));
        if (mcVersion != null) facets.add(List.of("versions:" + mcVersion));
        if (projectType.equals("mod")) facets.add(List.of("server_side:required", "server_side:optional"));
        String url = API + "/search?limit=" + limit + "&index=relevance"
                + "&query=" + enc(query == null ? "" : query.trim())
                + "&facets=" + enc(Json.write(facets).replace("\n", ""));
        List<Project> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(Http.getJson(url)).get("hits"))) out.add(project(Json.obj(o)));
        return out;
    }

    /** Proyecto por id o slug; null si no existe. */
    public static Project project(String idOrSlug) throws Exception {
        Object o = Http.getJsonOrNull(API + "/project/" + enc(idOrSlug));
        return o == null ? null : project(Json.obj(o));
    }

    /** Versiones del proyecto para ese loader y versión de Minecraft (null = todas), de más nueva a más antigua. */
    public static List<Version> versions(String idOrSlug, String loader, String mcVersion) throws Exception {
        StringBuilder url = new StringBuilder(API + "/project/" + enc(idOrSlug) + "/version?");
        if (loader != null) url.append("loaders=").append(enc("[\"" + loader + "\"]")).append('&');
        if (mcVersion != null) url.append("game_versions=").append(enc("[\"" + mcVersion + "\"]"));
        Object o = Http.getJsonOrNull(url.toString());
        List<Version> out = new ArrayList<>();
        if (o == null) return out;
        for (Object v : Json.arr(o)) out.add(version(Json.obj(v)));
        return out;
    }

    public static Version version(String versionId) throws Exception {
        Object o = Http.getJsonOrNull(API + "/version/" + enc(versionId));
        return o == null ? null : version(Json.obj(o));
    }

    /** Identifica ficheros por su SHA-1: sha1 -> versión de Modrinth (los que no son de Modrinth no salen). */
    public static Map<String, Version> versionsByHash(List<String> sha1s) throws Exception {
        Map<String, Version> out = new LinkedHashMap<>();
        if (sha1s.isEmpty()) return out;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hashes", new ArrayList<Object>(sha1s));
        body.put("algorithm", "sha1");
        Json.obj(Http.postJson(API + "/version_files", body))
                .forEach((hash, v) -> out.put(hash, version(Json.obj(v))));
        return out;
    }

    /** La más reciente de tipo release; si no hay ninguna, la más reciente (beta/alpha). */
    public static Version best(List<Version> versions) {
        for (Version v : versions) if ("release".equals(v.versionType())) return v;
        return versions.isEmpty() ? null : versions.get(0);
    }

    // ---------------- parseo ----------------

    static Project project(Map<String, Object> m) {
        String id = Json.str(m.containsKey("project_id") ? m.get("project_id") : m.get("id"));
        Object versions = m.containsKey("game_versions") ? m.get("game_versions") : m.get("versions");
        return new Project(id, Json.str(m.get("slug")), Json.str(m.get("title")), Json.str(m.get("description")),
                Json.str(m.get("author")), Json.num(m.get("downloads"), 0), Json.str(m.get("project_type")),
                Json.str(m.get("client_side")), Json.str(m.get("server_side")), strings(versions));
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
        return new Version(Json.str(m.get("id")), Json.str(m.get("project_id")), Json.str(m.get("name")),
                Json.str(m.get("version_number")), Json.str(m.get("version_type")),
                strings(m.get("game_versions")), strings(m.get("loaders")), deps, primary);
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
