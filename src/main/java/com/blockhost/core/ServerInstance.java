package com.blockhost.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Un servidor ya instalado en disco. Se persiste en &lt;carpeta&gt;/blockhost.json. */
public final class ServerInstance {

    public static final String FILE = "blockhost.json";

    private final Path dir;
    private ServerConfig config;
    private Path javaPath;
    private String jarName;
    private String loaderVersion; // versión de Fabric Loader; null en Vanilla/Paper o servidores antiguos

    public ServerInstance(Path dir, ServerConfig config, Path javaPath, String jarName) {
        this(dir, config, javaPath, jarName, null);
    }

    public ServerInstance(Path dir, ServerConfig config, Path javaPath, String jarName, String loaderVersion) {
        this.dir = dir;
        this.config = config;
        this.javaPath = javaPath;
        this.jarName = jarName;
        this.loaderVersion = loaderVersion;
    }

    public Path dir() { return dir; }
    public ServerConfig config() { return config; }
    public Path javaPath() { return javaPath; }
    public String jarName() { return jarName; }
    public String loaderVersion() { return loaderVersion; }

    public void setConfig(ServerConfig config) { this.config = config; }
    public void setJavaPath(Path javaPath) { this.javaPath = javaPath; }
    public void setLoaderVersion(String loaderVersion) { this.loaderVersion = loaderVersion; }

    public void save() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("config", config.toMap());
        m.put("javaPath", javaPath.toString());
        m.put("jarName", jarName);
        if (loaderVersion != null) m.put("loaderVersion", loaderVersion);
        Files.writeString(dir.resolve(FILE), Json.write(m), StandardCharsets.UTF_8);
    }

    public static ServerInstance load(Path dir) throws IOException {
        Map<String, Object> m = Json.obj(Json.parse(Files.readString(dir.resolve(FILE), StandardCharsets.UTF_8)));
        return new ServerInstance(dir,
                ServerConfig.fromMap(Json.obj(m.get("config"))),
                Path.of(Json.str(m.get("javaPath"))),
                Json.str(m.get("jarName")),
                Json.str(m.get("loaderVersion")));
    }

    @Override
    public String toString() { return config.name(); }
}
