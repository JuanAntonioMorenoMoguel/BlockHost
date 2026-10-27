package com.blockhost.core.mods;

import com.blockhost.core.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** Origen de un mod instalado desde Modrinth (o desde un modpack). Se guarda en blockhost-mods.json. */
public record ModSource(String projectId, String versionId, String title, String via) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", projectId);
        m.put("versionId", versionId);
        m.put("title", title);
        m.put("via", via);
        return m;
    }

    public static ModSource fromMap(Map<String, Object> m) {
        return new ModSource(Json.str(m.get("projectId")), Json.str(m.get("versionId")),
                Json.str(m.get("title")), Json.str(m.get("via")));
    }
}
