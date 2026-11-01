package com.blockhost.core.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortProbeTest {

    /** Hace lo mismo que un servicio de estado: handshake, petición de estado y ping. */
    @Test
    void answersLikeAMinecraftServer() throws Exception {
        try (PortProbe.Responder r = PortProbe.Responder.start(0); Socket s = new Socket("127.0.0.1", r.port())) {
            OutputStream out = s.getOutputStream();
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            hs.write(0x00);
            PortProbe.Responder.writeVarInt(hs, 767);
            byte[] host = "85.1.2.3".getBytes(StandardCharsets.UTF_8);
            PortProbe.Responder.writeVarInt(hs, host.length);
            hs.write(host);
            hs.write(new byte[]{(byte) 0x63, (byte) 0xDD}); // 25565
            PortProbe.Responder.writeVarInt(hs, 1);
            send(out, hs.toByteArray());
            send(out, new byte[]{0x00});

            DataInputStream in = new DataInputStream(s.getInputStream());
            int len = PortProbe.Responder.readVarInt(in);
            byte[] packet = new byte[len];
            in.readFully(packet);
            assertEquals(0x00, packet[0]);
            String json = new String(packet, 1, packet.length - 1, StandardCharsets.UTF_8);
            assertTrue(json.contains("Prueba de puerto de BlockHost"));

            send(out, new byte[]{0x01, 0, 0, 0, 0, 0, 0, 0, 42});
            len = PortProbe.Responder.readVarInt(in);
            byte[] pong = new byte[len];
            in.readFully(pong);
            assertEquals(9, pong.length);
            assertEquals(42, pong[8]);
        }
    }

    private static void send(OutputStream out, byte[] body) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PortProbe.Responder.writeVarInt(b, body.length);
        b.write(body);
        out.write(b.toByteArray());
        out.flush();
    }

    @Test
    void udpCannotBeChecked() {
        var r = PortProbe.check("85.1.2.3", 24454, "UDP", false);
        assertEquals(PortProbe.Verdict.UNKNOWN, r.verdict());
        assertTrue(r.why().contains("UDP"));
    }
}
