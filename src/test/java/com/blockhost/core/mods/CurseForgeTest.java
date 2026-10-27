package com.blockhost.core.mods;

import com.blockhost.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Parseo de respuestas de CurseForge y de su formato de modpack (ejemplos con el formato documentado). */
class CurseForgeTest {

    static final String FILE = """
            {"id": 5000001, "modId": 306612, "displayName": "Fabric API 0.110.0+1.21.4",
             "fileName": "fabric-api-0.110.0+1.21.4.jar", "releaseType": 1, "fileDate": "2024-12-01T10:00:00Z",
             "fileLength": 2000000, "downloadUrl": "https://edge.forgecdn.net/files/5000/1/fabric-api.jar",
             "hashes": [{"value": "md5hash", "algo": 2}, {"value": "sha1hash", "algo": 1}],
             "gameVersions": ["1.21.4", "Fabric", "Server", "Client"],
             "dependencies": [{"modId": 1, "relationType": 3}, {"modId": 2, "relationType": 2},
                              {"modId": 3, "relationType": 5}, {"modId": 4, "relationType": 6}]}""";

    @TempDir Path dir;

    @Test
    void parsesFile() {
        ModRepository.Version v = CurseForge.version(Json.obj(Json.parse(FILE)));
        assertEquals("5000001", v.id());
        assertEquals("306612", v.projectId());
        assertEquals("release", v.versionType());
        assertEquals(List.of("fabric"), v.loaders());
        assertEquals("sha1hash", v.file().sha1());
        assertEquals("1", v.dependencies("required").get(0).projectId());
        assertEquals("3", v.dependencies("incompatible").get(0).projectId());
        assertEquals(1, v.dependencies("embedded").size());
    }

    @Test
    void fileWithoutDownloadUrlIsMarked() {
        ModRepository.Version v = CurseForge.version(Json.obj(Json.parse(
                FILE.replace("\"https://edge.forgecdn.net/files/5000/1/fabric-api.jar\"", "null"))));
        assertNull(v.file().url());
    }

    @Test
    void parsesProject() {
        ModRepository.Project p = CurseForge.project(Json.obj(Json.parse("""
                {"id": 306612, "name": "Fabric API", "slug": "fabric-api", "summary": "Core API",
                 "downloadCount": 1000, "logo": {"thumbnailUrl": "https://media.forgecdn.net/thumb.png"},
                 "authors": [{"name": "modmuss50"}], "links": {"websiteUrl": "https://www.curseforge.com/minecraft/mc-mods/fabric-api"}}
                """)), "mod");
        assertEquals("306612", p.id());
        assertEquals("curseforge", p.repo());
        assertEquals("modmuss50", p.author());
        assertEquals("https://media.forgecdn.net/thumb.png", p.iconUrl());
    }

    static final String MANIFEST = """
            {"minecraft": {"version": "1.21.1", "modLoaders": [{"id": "fabric-0.16.5", "primary": true}]},
             "manifestType": "minecraftModpack", "manifestVersion": 1, "name": "Pack CF", "version": "2.0",
             "files": [{"projectID": 10, "fileID": 100, "required": true},
                       {"projectID": 11, "fileID": 110, "required": false}],
             "overrides": "overrides"}""";

    @Test
    void parsesManifestAsFabricPack() throws IOException {
        CurseForgePack.Manifest m = CurseForgePack.parseManifest(MANIFEST);
        assertEquals("1.21.1", m.mcVersion());
        assertEquals(1, m.fileRefs().size()); // el opcional no se instala
        Mrpack.Pack pack = CurseForgePack.toPack(m, List.of(), List.of("Mod sin permiso"));
        assertEquals("fabric", pack.loader());
        assertEquals("0.16.5", pack.loaderVersion());
        assertEquals(List.of("overrides"), pack.overrideDirs());
        assertEquals("Pack CF 2.0", pack.title());

        Mrpack.Pack forge = CurseForgePack.toPack(CurseForgePack.parseManifest(MANIFEST.replace("fabric-0.16.5", "forge-52.0.1")),
                List.of(), List.of());
        assertEquals("forge", forge.loader());
    }

    @Test
    void readAnyDetectsFormat() throws Exception {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("modrinth.index.json", MrpackTest.INDEX);
        Path mr = TestJars.jar(dir.resolve("a.mrpack"), e);
        assertEquals("1.21.4", Mrpack.readAny(mr).mcVersion());

        Path other = TestJars.jar(dir.resolve("b.zip"), Map.of("readme.txt", "x"));
        assertThrows(IOException.class, () -> Mrpack.readAny(other));
        Files.writeString(dir.resolve("c.zip"), "no zip");
        assertThrows(IOException.class, () -> Mrpack.readAny(dir.resolve("c.zip")));
    }
}
