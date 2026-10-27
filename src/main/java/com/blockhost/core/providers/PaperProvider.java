package com.blockhost.core.providers;

import com.blockhost.core.Http;
import com.blockhost.core.Json;
import com.blockhost.core.ProgressListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Paper vía la API Fill v3 de PaperMC (https://fill.papermc.io). */
public final class PaperProvider implements ServerProvider {

    private static final String API = "https://fill.papermc.io/v3/projects/paper";

    @Override
    public List<String> listVersions() throws Exception {
        Object versions = Json.obj(Http.getJson(API)).get("versions");
        List<String> out = new ArrayList<>();
        // v3 agrupa: {"1.21": ["1.21.4", ...], ...}. Se acepta también una lista plana.
        if (versions instanceof Map<?, ?> groups) {
            for (Object list : groups.values()) collect(list, out);
        } else {
            collect(versions, out);
        }
        out.sort(VERSION_ORDER.reversed());
        return out;
    }

    private static void collect(Object list, List<String> out) {
        for (Object v : Json.arr(list)) {
            String s = Json.str(v);
            if (ServerProvider.isStableVersion(s) && !out.contains(s)) out.add(s);
        }
    }

    @Override
    public String install(String mcVersion, Path dir, ProgressListener p) throws Exception {
        p.log("Buscando la última build de Paper para " + mcVersion + "...");
        List<Object> builds = Json.arr(Http.getJson(API + "/versions/" + mcVersion + "/builds"));
        Map<String, Object> best = null;
        for (Object o : builds) {
            Map<String, Object> b = Json.obj(o);
            boolean stable = "STABLE".equalsIgnoreCase(Json.str(b.get("channel")));
            if (best == null) { best = b; continue; }
            boolean bestStable = "STABLE".equalsIgnoreCase(Json.str(best.get("channel")));
            long id = Json.num(b.get("id"), 0), bestId = Json.num(best.get("id"), 0);
            if ((stable && !bestStable) || (stable == bestStable && id > bestId)) best = b;
        }
        if (best == null) throw new IllegalStateException("Paper no tiene builds para " + mcVersion);

        String url = Json.str(Json.path(best, "downloads", "server:default", "url"));
        if (url == null) throw new IllegalStateException("La build de Paper no tiene descarga de servidor");
        String jar = "paper.jar";
        p.log("Descargando Paper " + mcVersion + " (build " + Json.num(best.get("id"), 0) + ")...");
        Http.download(url, dir.resolve(jar), "Servidor Paper", p);
        return jar;
    }
}
