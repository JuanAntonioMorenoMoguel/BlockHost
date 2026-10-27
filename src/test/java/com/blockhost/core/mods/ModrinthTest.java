package com.blockhost.core.mods;

import com.blockhost.core.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parseo de respuestas de la API (ejemplos con el formato de https://docs.modrinth.com/api). */
class ModrinthTest {

    static final String VERSION = """
            {"id": "v2", "project_id": "AANobbMI", "name": "Sodium 0.6.0", "version_number": "mc1.21.4-0.6.0",
             "version_type": "release", "game_versions": ["1.21.4"], "loaders": ["fabric"],
             "dependencies": [
               {"version_id": null, "project_id": "P7dR8mSH", "file_name": null, "dependency_type": "required"},
               {"version_id": null, "project_id": "YL57xq9U", "file_name": null, "dependency_type": "incompatible"}],
             "files": [
               {"hashes": {"sha1": "s1", "sha512": "s5"}, "url": "https://cdn.modrinth.com/a-sources.jar",
                "filename": "a-sources.jar", "primary": false, "size": 1},
               {"hashes": {"sha1": "p1", "sha512": "p5"}, "url": "https://cdn.modrinth.com/a.jar",
                "filename": "a.jar", "primary": true, "size": 2}]}""";

    @Test
    void parsesVersionAndPicksPrimaryFile() {
        Modrinth.Version v = Modrinth.version(Json.obj(Json.parse(VERSION)));
        assertEquals("a.jar", v.file().fileName());
        assertEquals("p1", v.file().sha1());
        assertEquals(1, v.dependencies("required").size());
        assertEquals("YL57xq9U", v.dependencies("incompatible").get(0).projectId());
    }

    @Test
    void parsesSearchHitAndProject() {
        Modrinth.Project hit = Modrinth.project(Json.obj(Json.parse("""
                {"project_id": "AANobbMI", "slug": "sodium", "title": "Sodium", "author": "jellysquid3",
                 "project_type": "mod", "downloads": 100, "client_side": "required", "server_side": "unsupported",
                 "versions": ["1.21.4"]}""")));
        assertEquals("AANobbMI", hit.id());
        assertTrue(hit.clientOnly());
        assertEquals("https://modrinth.com/mod/sodium", hit.url());

        Modrinth.Project p = Modrinth.project(Json.obj(Json.parse("""
                {"id": "P7dR8mSH", "slug": "fabric-api", "title": "Fabric API", "project_type": "mod",
                 "client_side": "optional", "server_side": "optional", "game_versions": ["1.21.4"]}""")));
        assertEquals("P7dR8mSH", p.id());
        assertEquals(List.of("1.21.4"), p.gameVersions());
    }

    @Test
    void bestPrefersReleases() {
        Modrinth.Version beta = Modrinth.version(Json.obj(Json.parse(VERSION.replace("\"release\"", "\"beta\""))));
        Modrinth.Version rel = Modrinth.version(Json.obj(Json.parse(VERSION)));
        assertEquals(rel, Modrinth.best(List.of(beta, rel)));
        assertEquals(beta, Modrinth.best(List.of(beta)));
        assertEquals(null, Modrinth.best(List.of()));
    }
}
