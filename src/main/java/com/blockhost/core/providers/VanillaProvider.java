package com.blockhost.core.providers;

import com.blockhost.core.Http;
import com.blockhost.core.ProgressListener;

import java.nio.file.Path;
import java.util.List;

public final class VanillaProvider implements ServerProvider {

    @Override
    public List<String> listVersions() throws Exception {
        return MojangMeta.releaseVersions();
    }

    @Override
    public String install(String mcVersion, Path dir, ProgressListener p) throws Exception {
        String jar = "server.jar";
        p.log("Descargando servidor Vanilla " + mcVersion + "...");
        Http.download(MojangMeta.serverJarUrl(mcVersion), dir.resolve(jar), "Servidor Vanilla", p);
        return jar;
    }
}
