package com.blockhost.core;

import com.blockhost.core.CommandCatalog.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCatalogTest {

    private static List<String> texts(List<Suggestion> s) { return s.stream().map(Suggestion::text).toList(); }

    @Test
    void commandsDependOnVersion() {
        CommandCatalog old = new CommandCatalog(ServerType.VANILLA, "1.12.2");
        CommandCatalog modern = new CommandCatalog(ServerType.VANILLA, "1.21.4");
        assertTrue(old.names().contains("testfor"));
        assertFalse(old.names().contains("execute") && old.get("execute").syntax().startsWith("as"));
        assertFalse(old.names().contains("tick"));
        assertFalse(modern.names().contains("testfor"));
        assertTrue(modern.names().contains("tick"));
        assertTrue(modern.names().contains("transfer"));
        assertFalse(new CommandCatalog(ServerType.VANILLA, "1.20.4").names().contains("transfer"));
        assertTrue(new CommandCatalog(ServerType.PAPER, "1.21.4").names().contains("plugins"));
        assertFalse(modern.names().contains("plugins"));
    }

    @Test
    void completesCommandNames() {
        CommandCatalog c = new CommandCatalog(ServerType.VANILLA, "1.21.4");
        assertEquals(List.of("weather", "whitelist", "worldborder"), texts(c.suggest("w", List.of())).stream()
                .filter(t -> t.length() > 1).toList());
        assertTrue(texts(c.suggest("/gam", List.of())).containsAll(List.of("gamemode", "gamerule")));
    }

    @Test
    void completesArgumentsAndPlayers() {
        CommandCatalog c = new CommandCatalog(ServerType.VANILLA, "1.21.4");
        assertEquals(List.of("creative"), texts(c.suggest("gamemode cr", List.of())));
        assertEquals(List.of("Ana", "Ñenjis", "@a", "@p", "@r", "@s", "@e"),
                texts(c.suggest("gamemode creative ", List.of("Ñenjis", "Ana"))));
        assertEquals(List.of("Ñenjis"), texts(c.suggest("op ñ", List.of("Ñenjis", "Ana"))));
        assertEquals(List.of("add", "remove"), texts(c.suggest("whitelist ", List.of())).subList(2, 4));
    }

    @Test
    void learnsCommandsFromHelp() {
        CommandCatalog c = new CommandCatalog(ServerType.FABRIC, "1.21.4");
        assertTrue(c.learn("/carpet (list|<rule>) [<value>]"));
        assertFalse(c.learn("Done (3.2s)! For help, type \"help\""));
        assertTrue(c.names().contains("carpet"));
        assertEquals(List.of("list"), texts(c.suggest("carpet l", List.of())));
        // un comando conocido conserva nuestra sintaxis, más útil que la de "help"
        c.learn("/gamemode <gamemode> [<target>]");
        assertEquals(List.of("survival"), texts(c.suggest("gamemode s", List.of())).subList(0, 1));
    }

    @Test
    void applyReplacesLastWord() {
        assertEquals("/gamemode ", CommandCatalog.apply("/gam", new Suggestion("gamemode", "")));
        assertEquals("op Ñenjis ", CommandCatalog.apply("op ñe", new Suggestion("Ñenjis", "")));
    }
}
