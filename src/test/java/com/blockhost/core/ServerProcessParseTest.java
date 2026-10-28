package com.blockhost.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerProcessParseTest {

    private ServerProcess process;

    @BeforeEach
    void setUp() {
        ServerConfig cfg = new ServerConfig("Prueba", ServerType.PAPER, "1.21.4", 2048, 25565, "motd", 10,
                "normal", "survival", false, true);
        process = new ServerProcess(new ServerInstance(Path.of("x"), cfg, Path.of("java"), "paper.jar"));
    }

    private void line(String raw) {
        String clean = raw.replaceAll("\u001B\\[[;\\d?]*[ -/]*[@-~]", "");
        process.parse(ServerProcess.message(clean));
    }

    @Test
    void joinAndLeaveWithColorsAndUnicodeNames() {
        line("\u001B[33m[12:00:01 INFO]: \u001B[93mÑenjis joined the game\u001B[0m");
        line("[12:00:02] [Server thread/INFO]: .Bedrock_1 joined the game");
        assertEquals(Set.of("Ñenjis", ".Bedrock_1"), process.players());
        line("[12:00:03 INFO]: Ñenjis left the game");
        assertEquals(Set.of(".Bedrock_1"), process.players());
    }

    @Test
    void loggedInAndRenamed() {
        line("[12:00:01] [Server thread/INFO] [minecraft/PlayerList]: Steve[/127.0.0.1:50000] logged in with entity id 12 at (1, 2, 3)");
        line("[12:00:02] [Server thread/INFO]: Alex (formerly known as Alexa) joined the game");
        assertEquals(Set.of("Steve", "Alex"), process.players());
    }

    @Test
    void listResponseReplacesPlayers() {
        line("[12:00:01 INFO]: Viejo joined the game");
        line("[12:00:05 INFO]: There are 2 of a max of 20 players online: Ana, Ñenjis");
        assertEquals(Set.of("Ana", "Ñenjis"), process.players());
        line("[12:00:06 INFO]: There are 0 of a max of 20 players online:");
        assertEquals(Set.of(), process.players());
    }

    @Test
    void oldListFormatWithNamesOnNextLine() {
        line("[12:00:05] [Server thread/INFO]: There are 1/20 players online:");
        line("[12:00:05] [Server thread/INFO]: Notch");
        assertEquals(Set.of("Notch"), process.players());
    }
}
