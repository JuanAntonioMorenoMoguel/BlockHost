package com.blockhost.core.mods;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetaFormatsTest {

    @Test
    void forgeModsToml() {
        String toml = """
                modLoader="javafml" # comentario
                loaderVersion="[47,)"
                license='MIT'
                [[mods]]
                modId="create"
                version="${file.jarVersion}"
                displayName="Create"
                description='''
                Un mod con # almohadillas
                y varias líneas = cosas
                '''
                [[dependencies.create]]
                    modId="forge"
                    mandatory=true
                    versionRange="[47.1.3,)"
                    side="BOTH"
                [[dependencies.create]]
                    modId = "minecraft"
                    mandatory = true
                    versionRange = "[1.20.1]"
                """;
        List<MetaFormats.TomlTable> t = MetaFormats.toml(toml);
        assertEquals("javafml", t.get(0).values().get("modLoader"));
        assertEquals("mods", t.get(1).name());
        assertEquals("create", t.get(1).values().get("modId"));
        assertEquals("Create", t.get(1).values().get("displayName"));
        assertEquals("dependencies.create", t.get(2).name());
        assertEquals(true, t.get(2).values().get("mandatory"));
        assertEquals("[47.1.3,)", t.get(2).values().get("versionRange"));
        assertEquals("[1.20.1]", t.get(3).values().get("versionRange"));
        assertEquals(4, t.size());
    }

    @Test
    void pluginYml() {
        String yml = """
                name: Essentials
                version: '2.21.0'
                main: com.earth2me.essentials.Essentials
                api-version: "1.13"
                depend: [Vault, 'LuckPerms']
                softdepend:
                  - WorldEdit
                  - "PlaceholderAPI"
                description: |
                  Texto: largo
                commands:
                  home:
                    description: Vuelve a casa # comentario
                """;
        Map<String, Object> y = MetaFormats.yaml(yml);
        assertEquals("Essentials", y.get("name"));
        assertEquals("2.21.0", y.get("version"));
        assertEquals("1.13", y.get("api-version"));
        assertEquals(List.of("Vault", "LuckPerms"), y.get("depend"));
        assertEquals(List.of("WorldEdit", "PlaceholderAPI"), y.get("softdepend"));
        assertEquals("Vuelve a casa", ((Map<?, ?>) ((Map<?, ?>) y.get("commands")).get("home")).get("description"));
    }

    @Test
    void paperPluginYmlNestedDependencies() {
        String yml = """
                name: MiPlugin
                version: 1.0
                api-version: '1.21'
                dependencies:
                  server:
                    Vault:
                      load: BEFORE
                      required: true
                    ProtocolLib:
                      required: false
                """;
        Map<String, Object> y = MetaFormats.yaml(yml);
        Map<?, ?> server = (Map<?, ?>) ((Map<?, ?>) y.get("dependencies")).get("server");
        assertEquals("true", ((Map<?, ?>) server.get("Vault")).get("required"));
        assertEquals("false", ((Map<?, ?>) server.get("ProtocolLib")).get("required"));
    }
}
