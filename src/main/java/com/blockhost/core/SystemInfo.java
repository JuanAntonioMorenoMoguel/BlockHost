package com.blockhost.core;

import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SystemInfo {

    private SystemInfo() { }

    public static long totalMemoryMb() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return os.getTotalMemorySize() / (1024 * 1024);
        } catch (Throwable t) {
            return 8192;
        }
    }

    /** Un cuarto de la RAM del equipo, entre 2 y 8 GB, redondeado a 512 MB. */
    public static int recommendedRamMb() {
        long rec = Math.max(2048, Math.min(8192, totalMemoryMb() / 4));
        return (int) (rec / 512 * 512);
    }

    /** Máximo asignable dejando al menos 2 GB al sistema. */
    public static int maxRamMb() {
        long max = Math.max(1024, totalMemoryMb() - 2048);
        return (int) (Math.min(max, 32768) / 512 * 512);
    }

    /** IPs IPv4 de la red local (192.168.x.x, 10.x.x.x...), para que se conecten desde la misma red. */
    public static List<String> localIPv4() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = ni.getDisplayName().toLowerCase();
                if (name.contains("virtual") || name.contains("vmware") || name.contains("hyper-v")
                        || name.contains("wsl") || name.contains("docker")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) ips.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
            // sin red: lista vacía
        }
        return ips;
    }
}
