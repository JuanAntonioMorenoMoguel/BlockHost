package com.blockhost.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupsTest {

    @TempDir Path dir;
    private ServerInstance inst;

    @BeforeEach
    void setUp() {
        ServerConfig cfg = new ServerConfig("Prueba", ServerType.PAPER, "1.21.4", 2048, 25565, "motd", 10,
                "normal", "survival", true, true);
        inst = new ServerInstance(dir, cfg, Path.of("java"), "paper.jar");
    }

    @Test
    void noWorldNoBackup() {
        assertThrows(IOException.class, () -> Backups.create(inst));
        assertEquals(List.of(), assertDoesNotThrowList());
    }

    private List<Backups.Backup> assertDoesNotThrowList() {
        try {
            return Backups.list(inst);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void createListAndRestoreUsesLevelName() throws IOException {
        Files.writeString(dir.resolve("server.properties"), "level-name=mimundo\n");
        Files.createDirectories(dir.resolve("mimundo/region"));
        Files.writeString(dir.resolve("mimundo/level.dat"), "v1");
        Files.writeString(dir.resolve("mimundo/session.lock"), "lock");
        Files.createDirectories(dir.resolve("mimundo_nether"));
        Files.writeString(dir.resolve("mimundo_nether/n.dat"), "nether");

        Path zip = Backups.create(inst);
        assertTrue(zip.getFileName().toString().startsWith(Backups.MANUAL));

        // el mundo cambia y se restaura
        Files.writeString(dir.resolve("mimundo/level.dat"), "v2");
        Files.writeString(dir.resolve("mimundo/nuevo.dat"), "x");
        Path safety = Backups.restore(inst, zip);
        assertNotNull(safety);
        assertEquals("v1", Files.readString(dir.resolve("mimundo/level.dat")));
        assertFalse(Files.exists(dir.resolve("mimundo/nuevo.dat")));
        assertFalse(Files.exists(dir.resolve("mimundo/session.lock")));
        assertEquals("nether", Files.readString(dir.resolve("mimundo_nether/n.dat")));

        List<Backups.Backup> all = Backups.list(inst);
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(b -> b.kind().equals(Backups.BEFORE_RESTORE)));
    }

    @Test
    void pruneKeepsOnlyLatestAutomatic() throws IOException {
        Path backups = Files.createDirectories(dir.resolve("backups"));
        for (String n : List.of("auto-20260101-100000.zip", "auto-20260102-100000.zip", "auto-20260103-100000.zip",
                "backup-20250101-100000.zip")) {
            Files.writeString(backups.resolve(n), "zip");
        }
        assertEquals(1, Backups.prune(inst, 2));
        assertFalse(Files.exists(backups.resolve("auto-20260101-100000.zip")));
        assertTrue(Files.exists(backups.resolve("auto-20260103-100000.zip")));
        assertTrue(Files.exists(backups.resolve("backup-20250101-100000.zip"))); // las manuales no se tocan
        assertEquals("auto-20260103-100000.zip", Backups.list(inst).get(0).file().getFileName().toString());
    }

    @Test
    void autoBackupSettingsArePersisted() throws IOException {
        inst.setAutoBackup(3, 5);
        inst.save();
        ServerInstance loaded = ServerInstance.load(dir);
        assertEquals(3, loaded.autoBackupHours());
        assertEquals(5, loaded.autoBackupKeep());
    }
}
