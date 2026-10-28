package com.blockhost.core.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NAT-PMP (RFC 6886), la alternativa a UPnP de Apple y de muchos routers con firmware libre. Los routers con PCP
 * (RFC 6887) también lo entienden.
 */
public final class NatPmp {

    private static final int PORT = 5351;
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b");

    private NatPmp() { }

    /** IP pública que ve el router, o null si no habla NAT-PMP. */
    public static String externalIp(String gateway) {
        byte[] r = request(gateway, new byte[]{0, 0}, 12);
        if (r == null || (r[1] & 0xff) != 128 || result(r) != 0) return null;
        return (r[8] & 0xff) + "." + (r[9] & 0xff) + "." + (r[10] & 0xff) + "." + (r[11] & 0xff);
    }

    /**
     * Abre (lifetime &gt; 0) o cierra (lifetime 0) un puerto.
     *
     * @return el puerto externo concedido, o -1 si el router no contesta o lo rechaza
     */
    public static int map(String gateway, int port, boolean tcp, int lifetimeSeconds) {
        ByteBuffer b = ByteBuffer.allocate(12);
        b.put((byte) 0).put((byte) (tcp ? 2 : 1)).putShort((short) 0)
                .putShort((short) port).putShort((short) (lifetimeSeconds == 0 ? 0 : port)).putInt(lifetimeSeconds);
        byte[] r = request(gateway, b.array(), 16);
        if (r == null || (r[1] & 0xff) != 128 + (tcp ? 2 : 1) || result(r) != 0) return -1;
        return ((r[10] & 0xff) << 8) | (r[11] & 0xff);
    }

    private static int result(byte[] r) { return ((r[2] & 0xff) << 8) | (r[3] & 0xff); }

    /** Reintenta con esperas de 250, 500 y 1000 ms, como pide el RFC (abreviado). */
    private static byte[] request(String gateway, byte[] msg, int expected) {
        try (DatagramSocket s = new DatagramSocket()) {
            InetAddress gw = InetAddress.getByName(gateway);
            int wait = 250;
            for (int attempt = 0; attempt < 3; attempt++, wait *= 2) {
                s.send(new DatagramPacket(msg, msg.length, gw, PORT));
                s.setSoTimeout(wait);
                byte[] buf = new byte[64];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (p.getLength() >= expected && p.getAddress().equals(gw)) return buf;
            }
        } catch (IOException ignored) {
            // sin red o sin router
        }
        return null;
    }

    // ---------------- puerta de enlace ----------------

    /**
     * Posibles routers: la puerta de enlace que dice el sistema y, por si acaso, la .1 de cada red local.
     */
    public static List<String> gateways(List<String> localIps) {
        Set<String> out = new LinkedHashSet<>(systemGateways());
        for (String ip : localIps) {
            int dot = ip.lastIndexOf('.');
            if (dot > 0) out.add(ip.substring(0, dot) + ".1");
        }
        return new ArrayList<>(out);
    }

    private static List<String> systemGateways() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) return parseWindowsRoutes(run("route", "print", "-4", "0.0.0.0"));
            if (os.contains("mac")) return firstIp(run("route", "-n", "get", "default"), "gateway:");
            Path routes = Path.of("/proc/net/route");
            if (Files.isReadable(routes)) return parseLinuxRoutes(Files.readString(routes));
        } catch (Exception ignored) {
            // se prueba con la .1
        }
        return List.of();
    }

    /** "0.0.0.0  0.0.0.0  192.168.1.1  192.168.1.34  25" → 192.168.1.1. Paquete para los tests. */
    static List<String> parseWindowsRoutes(String out) {
        List<String> gws = new ArrayList<>();
        for (String line : out.split("\\R")) {
            String[] t = line.trim().split("\\s+");
            if (t.length >= 4 && t[0].equals("0.0.0.0") && t[1].equals("0.0.0.0") && IPV4.matcher(t[2]).matches()) {
                gws.add(t[2]);
            }
        }
        return gws;
    }

    /** /proc/net/route: destino 00000000 y gateway en hexadecimal little-endian. */
    static List<String> parseLinuxRoutes(String table) {
        List<String> gws = new ArrayList<>();
        for (String line : table.split("\\R")) {
            String[] t = line.trim().split("\\s+");
            if (t.length < 3 || !t[1].equals("00000000") || t[2].equals("00000000")) continue;
            long v = Long.parseLong(t[2], 16);
            gws.add((v & 0xff) + "." + ((v >> 8) & 0xff) + "." + ((v >> 16) & 0xff) + "." + ((v >> 24) & 0xff));
        }
        return gws;
    }

    private static List<String> firstIp(String out, String label) {
        for (String line : out.split("\\R")) {
            if (line.trim().startsWith(label)) {
                Matcher m = IPV4.matcher(line);
                if (m.find()) return List.of(m.group(1));
            }
        }
        return List.of();
    }

    static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            out = String.join("\n", r.lines().toList());
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        return out;
    }
}
