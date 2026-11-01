package com.blockhost.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldShareTest {

    @TempDir Path tmp;
    private ServerInstance inst;
    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createDirectories(tmp.resolve("server"));
        ServerConfig cfg = new ServerConfig("Mi servidor ñ", ServerType.PAPER, "1.21.4", 2048, 25565, "motd", 10,
                "normal", "survival", true, true);
        inst = new ServerInstance(dir, cfg, Path.of("java"), "paper.jar");
    }

    @Test
    void exportFileNameIsSafe() {
        String n = WorldShare.exportFileName(inst);
        assertTrue(n.matches("Mi-servidor-n-mundo-\\d{4}-\\d{2}-\\d{2}\\.zip"), n);
    }

    @Test
    void exportAndImportIntoServerWithOtherLevelName() throws Exception {
        Files.createDirectories(dir.resolve("world/region"));
        Files.writeString(dir.resolve("world/level.dat"), "amigo");
        Files.createDirectories(dir.resolve("world_nether"));
        Files.writeString(dir.resolve("world_nether/level.dat"), "nether");
        Path zip = WorldShare.export(inst, null, tmp.resolve("out/compartido.zip"));
        assertTrue(Files.isRegularFile(zip));

        Path other = Files.createDirectories(tmp.resolve("other"));
        Files.writeString(other.resolve("server.properties"), "level-name=mimundo\n");
        Files.createDirectories(other.resolve("mimundo"));
        Files.writeString(other.resolve("mimundo/level.dat"), "viejo");
        ServerInstance o = new ServerInstance(other, inst.config(), Path.of("java"), "paper.jar");

        Path safety = WorldShare.importWorld(o, zip);
        assertNotNull(safety);
        assertEquals("amigo", Files.readString(other.resolve("mimundo/level.dat")));
        assertEquals("nether", Files.readString(other.resolve("mimundo_nether/level.dat")));
        assertFalse(Files.exists(other.resolve("world")));
        assertFalse(Files.exists(other.resolve("backups/.importando")));
    }

    @Test
    void importsSingleplayerFolderAndNestedZip() throws Exception {
        Path saves = Files.createDirectories(tmp.resolve("saves/Mi Mundo/DIM-1"));
        Files.writeString(saves.getParent().resolve("level.dat"), "sp");
        Files.writeString(saves.getParent().resolve("session.lock"), "x");
        assertNull(WorldShare.importWorld(inst, saves.getParent()));
        assertEquals("sp", Files.readString(dir.resolve("world/level.dat")));
        assertTrue(Files.isDirectory(dir.resolve("world/DIM-1")));
        assertFalse(Files.exists(dir.resolve("world/session.lock")));

        // zip hecho a mano con el mundo dentro de una carpeta cualquiera
        Path zip = tmp.resolve("a.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("descargas/Survival/level.dat"));
            out.write("zip".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertNotNull(WorldShare.importWorld(inst, zip));
        assertEquals("zip", Files.readString(dir.resolve("world/level.dat")));
        assertFalse(Files.exists(dir.resolve("world/DIM-1")));
    }

    @Test
    void rejectsArchivesWithoutWorld() throws IOException {
        Path zip = tmp.resolve("b.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("leeme.txt"));
            out.closeEntry();
        }
        Files.createDirectories(dir.resolve("world"));
        Files.writeString(dir.resolve("world/level.dat"), "intacto");
        assertThrows(IOException.class, () -> WorldShare.importWorld(inst, zip));
        assertEquals("intacto", Files.readString(dir.resolve("world/level.dat")));

        Path html = tmp.resolve("c.zip");
        Files.writeString(html, "<html>login</html>");
        assertThrows(IOException.class, () -> WorldShare.importWorld(inst, html));
    }

    @Test
    void shareLinksBecomeDirectDownloads() throws IOException {
        assertEquals("https://drive.usercontent.google.com/download?id=1AbC-d_9&export=download&confirm=t",
                WorldShare.directDownloadUrl("https://drive.google.com/file/d/1AbC-d_9/view?usp=sharing"));
        assertEquals("https://drive.usercontent.google.com/download?id=XYZ&export=download&confirm=t",
                WorldShare.directDownloadUrl("drive.google.com/open?id=XYZ"));
        assertEquals("https://www.dropbox.com/scl/fi/abc/m.zip?rlkey=k&dl=1",
                WorldShare.directDownloadUrl("https://www.dropbox.com/scl/fi/abc/m.zip?rlkey=k&dl=0"));
        assertEquals("https://litter.catbox.moe/abc.zip", WorldShare.directDownloadUrl(" https://litter.catbox.moe/abc.zip "));
        assertThrows(IOException.class,
                () -> WorldShare.directDownloadUrl("https://drive.google.com/drive/folders/abc"));
    }

    @Test
    void uploadSendsMultipartAndReturnsLink() throws Exception {
        Path zip = tmp.resolve("m.zip");
        Files.write(zip, new byte[300_000]);
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            received.set(ex.getRequestHeaders().getFirst("Content-Type") + "|" + body.length + "|"
                    + new String(body, 0, 400, StandardCharsets.ISO_8859_1));
            byte[] resp = "https://litter.catbox.moe/abc123.zip\n".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(resp);
            }
        });
        server.start();
        try {
            String url = WorldShare.upload("http://127.0.0.1:" + server.getAddress().getPort() + "/api", zip,
                    ProgressListener.NONE);
            assertEquals("https://litter.catbox.moe/abc123.zip", url);
            String r = received.get();
            assertTrue(r.startsWith("multipart/form-data; boundary="), r);
            assertTrue(Integer.parseInt(r.split("\\|")[1]) > 300_000);
            assertTrue(r.contains("name=\"reqtype\"\r\n\r\nfileupload"), r);
            assertTrue(r.contains("name=\"time\"\r\n\r\n72h"), r);
            assertTrue(r.contains("name=\"fileToUpload\"; filename=\"m.zip\""), r);
        } finally {
            server.stop(0);
        }
    }
}
