package com.blockhost.core.mods;

import com.blockhost.core.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Origen de un mod instalado desde Modrinth o un modpack. Se guarda en blockhost-mods.json.
 *
 * @param repo "modrinth" (null si vino de un modpack sin más datos)
 */
public record ModSource(String repo, String projectId, String versionId, String title, String via) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repo", repo);
        m.put("projectId", projectId);
        m.put("versionId", versionId);
        m.put("title", title);
        m.put("via", via);
        return m;
    }

    public static ModSource fromMap(Map<String, Object> m) {
        String via = Json.str(m.get("via"));
        String repo = Json.str(m.get("repo"));
        if (repo == null && "modrinth".equals(via)) repo = "modrinth"; // guardado por versiones anteriores
        return new ModSource(repo, Json.str(m.get("projectId")), Json.str(m.get("versionId")),
                Json.str(m.get("title")), via);
    }

    public boolean fromModpack() { return via != null && via.startsWith("modpack:"); }

    /** Página web del proyecto, si se sabe. */
    public String pageUrl() {
        if (projectId == null) return null;
        if ("modrinth".equals(repo)) return "https://modrinth.com/project/" + projectId;
        return null;
    }

    public String label() {
        if (fromModpack()) return "Modpack";
        if ("modrinth".equals(repo)) return "Modrinth";
        return "?";
    }
}
