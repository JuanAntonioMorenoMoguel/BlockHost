package com.blockhost.core.providers;

import com.blockhost.core.Http;
import com.blockhost.core.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Metadatos oficiales de Mojang (piston-meta): versiones, jar del servidor y versión de Java requerida. */
public final class MojangMeta {

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private static volatile Map<String, Object> manifest;
    private static final Map<String, Map<String, Object>> VERSION_CACHE = new ConcurrentHashMap<>();

    private MojangMeta() { }

    private static Map<String, Object> manifest() throws Exception {
        if (manifest == null) manifest = Json.obj(Http.getJson(MANIFEST));
        return manifest;
    }

    public static List<String> releaseVersions() throws Exception {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(manifest().get("versions"))) {
            Map<String, Object> v = Json.obj(o);
            if ("release".equals(v.get("type"))) out.add(Json.str(v.get("id")));
        }
        return out; // el manifiesto ya viene de más nueva a más antigua
    }

    public static Map<String, Object> versionJson(String id) throws Exception {
        Map<String, Object> cached = VERSION_CACHE.get(id);
        if (cached != null) return cached;
        for (Object o : Json.arr(manifest().get("versions"))) {
            Map<String, Object> v = Json.obj(o);
            if (id.equals(v.get("id"))) {
                Map<String, Object> json = Json.obj(Http.getJson(Json.str(v.get("url"))));
                VERSION_CACHE.put(id, json);
                return json;
            }
        }
        throw new IllegalArgumentException("Versión de Minecraft desconocida: " + id);
    }

    public static String serverJarUrl(String id) throws Exception {
        String url = Json.str(Json.path(versionJson(id), "downloads", "server", "url"));
        if (url == null) throw new IllegalStateException("Mojang no publica servidor para la versión " + id);
        return url;
    }

    /** Java que pide Mojang para esa versión (8 en versiones antiguas que no lo indican). */
    public static int requiredJava(String id) throws Exception {
        return (int) Json.num(Json.path(versionJson(id), "javaVersion", "majorVersion"), 8);
    }
}
