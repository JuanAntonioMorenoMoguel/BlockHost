package com.blockhost.core;

import com.blockhost.core.PlayerLists.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerListsTest {

    @TempDir Path dir;

    @Test
    void offlineUuidMatchesTheServer() {
        assertEquals("b50ad385-829d-3141-a216-7e7d7539ba7f", PlayerLists.offlineUuid("Notch"));
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", PlayerLists.dashed("069a79f444e94726a5befca90e38aaf5"));
    }

    @Test
    void editsFilesWhileStopped() throws Exception {
        PlayerLists lists = new PlayerLists(dir, false);
        lists.add(Kind.OPS, "Steve", null);
        lists.add(Kind.WHITELIST, "Alex", null);
        lists.add(Kind.WHITELIST, "alex", null); // ya está
        lists.add(Kind.BANNED, "Griefer", "Rompió la base");

        assertEquals("Steve", lists.read(Kind.OPS).get(0).name());
        assertEquals("nivel 4", lists.read(Kind.OPS).get(0).detail());
        assertEquals(1, lists.read(Kind.WHITELIST).size());
        assertEquals("Rompió la base", lists.read(Kind.BANNED).get(0).detail());
        assertTrue(Files.readString(dir.resolve("banned-players.json")).contains("\"expires\""));

        lists.remove(Kind.WHITELIST, "ALEX");
        assertEquals(0, lists.read(Kind.WHITELIST).size());
        assertThrows(IllegalArgumentException.class, () -> lists.add(Kind.OPS, "no vale!", null));
    }

    @Test
    void whitelistSwitchKeepsOtherProperties() throws Exception {
        Files.writeString(dir.resolve("server.properties"), "motd=Hola\nwhite-list=false\n");
        PlayerLists lists = new PlayerLists(dir, true);
        assertFalse(lists.whitelistEnabled());
        lists.setWhitelistEnabled(true);
        assertTrue(lists.whitelistEnabled());
        assertTrue(Files.readString(dir.resolve("server.properties")).contains("motd=Hola"));
    }

    @Test
    void commandsForARunningServer() {
        assertEquals("op Steve", PlayerLists.command(Kind.OPS, true, "Steve", null));
        assertEquals("whitelist remove Alex", PlayerLists.command(Kind.WHITELIST, false, "Alex", null));
        assertEquals("ban Bob por tramposo", PlayerLists.command(Kind.BANNED, true, "Bob", "por tramposo"));
        assertEquals("pardon Bob", PlayerLists.command(Kind.BANNED, false, "Bob", null));
    }
}
