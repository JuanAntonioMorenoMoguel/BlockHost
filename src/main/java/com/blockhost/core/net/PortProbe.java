package com.blockhost.core.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Comprueba desde internet si llega un puerto TCP cualquiera, aunque no haya un servidor de Minecraft en él:
 * mientras dura la prueba escucha en ese puerto y contesta como un servidor de Minecraft (solo a la consulta de
 * estado), y le pide a {@link ReachabilityCheck} que lo consulte desde fuera.
 *
 * <p>Los puertos UDP no se pueden comprobar así: no hay forma de que un servicio externo sepa si un paquete UDP
 * ha llegado.
 */
public final class PortProbe {

    public enum Verdict { REACHABLE, UNREACHABLE, UNKNOWN }

    /** @param why explicación para enseñar */
    public record Result(Verdict verdict, String why) { }

    private PortProbe() { }

    /**
     * @param minecraftRunning si en ese puerto hay un servidor de Minecraft de BlockHost en marcha
     */
    public static Result check(String publicIp, int port, String protocol, boolean minecraftRunning) {
        if ("UDP".equalsIgnoreCase(protocol)) {
            return new Result(Verdict.UNKNOWN, "Los puertos UDP no se pueden comprobar desde fuera. Pruébalo con el "
                    + "programa que lo usa (por ejemplo, entrando al chat de voz).");
        }
        if (publicIp == null) return new Result(Verdict.UNKNOWN, "No se sabe tu IP pública (¿sin internet?).");
        if (minecraftRunning) return fromCheck(ReachabilityCheck.check(publicIp, port), port);

        Responder r;
        try {
            r = Responder.start(port);
        } catch (IOException e) {
            // ocupado por otro programa: solo se puede saber si ese programa resulta ser Minecraft
            ReachabilityCheck.Outcome o = ReachabilityCheck.check(publicIp, port);
            if (o.result() == ReachabilityCheck.Result.REACHABLE) return fromCheck(o, port);
            return new Result(Verdict.UNKNOWN, "Otro programa está usando el puerto " + port
                    + " en este PC y no se puede comprobar. Ciérralo y vuelve a probar.");
        }
        try {
            return fromCheck(ReachabilityCheck.check(publicIp, port), port);
        } finally {
            r.close();
        }
    }

    private static Result fromCheck(ReachabilityCheck.Outcome o, int port) {
        return switch (o.result()) {
            case REACHABLE -> new Result(Verdict.REACHABLE, "Llega desde internet (comprobado con " + o.service() + ").");
            case UNREACHABLE -> new Result(Verdict.UNREACHABLE, "No llega desde internet (comprobado con " + o.service()
                    + "). Mira que apunte a este PC y que el Firewall lo permita. Si lo acabas de abrir, espera un minuto.");
            case UNKNOWN -> new Result(Verdict.UNKNOWN, "No se pudo comprobar: los servicios de comprobación no responden.");
        };
    }

    // ---------------- servidor de mentira ----------------

    /** Contesta a la consulta de estado de Minecraft (handshake → status → ping) en un puerto. */
    static final class Responder implements AutoCloseable {
        static final String STATUS = "{\"version\":{\"name\":\"BlockHost\",\"protocol\":767},"
                + "\"players\":{\"max\":0,\"online\":0},\"description\":{\"text\":\"Prueba de puerto de BlockHost\"}}";

        private final ServerSocket server;
        private final Thread thread;

        private Responder(ServerSocket server) {
            this.server = server;
            this.thread = new Thread(this::loop, "port-probe-" + server.getLocalPort());
            this.thread.setDaemon(true);
        }

        static Responder start(int port) throws IOException {
            ServerSocket s = new ServerSocket();
            s.bind(new InetSocketAddress(port));
            Responder r = new Responder(s);
            r.thread.start();
            return r;
        }

        int port() { return server.getLocalPort(); }

        private void loop() {
            while (!server.isClosed()) {
                try (Socket c = server.accept()) {
                    c.setSoTimeout(5000);
                    serve(c.getInputStream(), c.getOutputStream());
                } catch (IOException ignored) {
                    // conexión cortada o socket cerrado
                }
            }
        }

        static void serve(InputStream rawIn, OutputStream out) throws IOException {
            DataInputStream in = new DataInputStream(rawIn);
            readPacket(in); // handshake: no hace falta mirarlo
            while (true) {
                byte[] p = readPacket(in);
                if (p.length == 0) return;
                if (p[0] == 0x00) { // petición de estado
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    body.write(0x00);
                    byte[] json = STATUS.getBytes(StandardCharsets.UTF_8);
                    writeVarInt(body, json.length);
                    body.write(json);
                    writePacket(out, body.toByteArray());
                } else if (p[0] == 0x01) { // ping: se devuelve igual
                    writePacket(out, p);
                    return;
                } else {
                    return;
                }
            }
        }

        private static byte[] readPacket(DataInputStream in) throws IOException {
            int len = readVarInt(in);
            if (len < 0 || len > 4096) throw new IOException("paquete raro");
            byte[] b = new byte[len];
            in.readFully(b);
            return b;
        }

        private static void writePacket(OutputStream out, byte[] body) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            writeVarInt(b, body.length);
            b.write(body);
            out.write(b.toByteArray());
            out.flush();
        }

        static int readVarInt(InputStream in) throws IOException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = in.read();
                if (b < 0) throw new IOException("fin de la conexión");
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new IOException("VarInt demasiado largo");
        }

        static void writeVarInt(OutputStream out, int value) throws IOException {
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // ya cerrado
            }
        }
    }
}
