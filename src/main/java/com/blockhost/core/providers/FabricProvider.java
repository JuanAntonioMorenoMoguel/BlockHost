package com.blockhost.core.providers;

import com.blockhost.core.Http;
import com.blockhost.core.Json;
import com.blockhost.core.ProgressListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fabric vía meta.fabricmc.net. Descarga el lanzador de servidor, que baja el resto al primer arranque. */
public final class FabricProvider implements ServerProvider {

    private static final String META = "https://meta.fabricmc.net/v2/versions";

    @Override
    public List<String> listVersions() throws Exception {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(Http.getJson(META + "/game"))) {
            Map<String, Object> v = Json.obj(o);
            if (Json.bool(v.get("stable"), false)) out.add(Json.str(v.get("version")));
        }
        return out;
    }

    @Override
    public String install(String mcVersion, Path dir, ProgressListener p) throws Exception {
        p.log("Buscando el loader de Fabric para " + mcVersion + "...");
        String loader = null;
        for (Object o : Json.arr(Http.getJson(META + "/loader/" + mcVersion))) {
            Map<String, Object> l = Json.obj(Json.obj(o).get("loader"));
            if (loader == null) loader = Json.str(l.get("version"));
            if (Json.bool(l.get("stable"), false)) { loader = Json.str(l.get("version")); break; }
        }
        if (loader == null) throw new IllegalStateException("Fabric no soporta la versión " + mcVersion);

        String installer = null;
        for (Object o : Json.arr(Http.getJson(META + "/installer"))) {
            Map<String, Object> i = Json.obj(o);
            if (installer == null) installer = Json.str(i.get("version"));
            if (Json.bool(i.get("stable"), false)) { installer = Json.str(i.get("version")); break; }
        }

        String url = META + "/loader/" + mcVersion + "/" + loader + "/" + installer + "/server/jar";
        String jar = "fabric-server-launch.jar";
        p.log("Descargando Fabric loader " + loader + "...");
        Http.download(url, dir.resolve(jar), "Servidor Fabric", p);
        Files.createDirectories(dir.resolve("mods"));
        return jar;
    }
}
