package com.blockhost.core;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Mide CPU y memoria del proceso del servidor y del equipo. Se llama cada pocos segundos: la CPU del proceso
 * sale de la diferencia de tiempo de CPU entre dos llamadas.
 */
public final class ProcessStats {

    /**
     * @param processCpu   % de CPU del servidor sobre el total del equipo (0–100), o -1 si no se sabe
     * @param processRamMb memoria que ocupa el servidor, o -1 si no se sabe
     * @param systemCpu    % de CPU de todo el equipo, o -1
     * @param systemUsedMb memoria usada en el equipo
     * @param systemTotalMb memoria total del equipo
     */
    public record Sample(double processCpu, long processRamMb, double systemCpu, long systemUsedMb, long systemTotalMb) { }

    private static final int CORES = Runtime.getRuntime().availableProcessors();

    private long lastPid = -1;
    private long lastCpuNanos = -1;
    private long lastWallNanos = -1;

    /** @param handle el proceso del servidor, o null si está detenido */
    public synchronized Sample sample(ProcessHandle handle) {
        double procCpu = -1;
        long procRam = -1;
        if (handle != null && handle.isAlive()) {
            long now = System.nanoTime();
            long cpu = handle.info().totalCpuDuration().map(Duration::toNanos).orElse(-1L);
            if (cpu >= 0 && handle.pid() == lastPid && lastCpuNanos >= 0 && now > lastWallNanos) {
                procCpu = Math.max(0, Math.min(100, 100.0 * (cpu - lastCpuNanos) / ((now - lastWallNanos) * (double) CORES)));
            }
            lastPid = handle.pid();
            lastCpuNanos = cpu;
            lastWallNanos = now;
            procRam = residentMb(handle.pid());
        } else {
            lastPid = -1;
        }

        double sysCpu = -1;
        long total = SystemInfo.totalMemoryMb(), used = -1;
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = os.getCpuLoad();
            sysCpu = load < 0 ? -1 : load * 100;
            used = (os.getTotalMemorySize() - os.getFreeMemorySize()) / (1024 * 1024);
        } catch (Throwable ignored) {
            // JVM sin com.sun.management
        }
        return new Sample(procCpu, procRam, sysCpu, used, total);
    }

    /** Memoria residente del proceso: /proc en Linux, tasklist en Windows, ps en macOS. */
    static long residentMb(long pid) {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("linux")) {
                for (String line : Files.readAllLines(Path.of("/proc", String.valueOf(pid), "status"))) {
                    if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("\\D", "")) / 1024;
                }
                return -1;
            }
            if (os.contains("win")) {
                // "java.exe","1234","Console","1","2.345.678 K"  (el separador de miles depende del idioma)
                String out = run("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH");
                String[] cols = out.trim().split("\",\"");
                if (cols.length < 5) return -1;
                String kb = cols[cols.length - 1].replaceAll("\\D", "");
                return kb.isEmpty() ? -1 : Long.parseLong(kb) / 1024;
            }
            String kb = run("ps", "-o", "rss=", "-p", String.valueOf(pid)).trim();
            return kb.isEmpty() ? -1 : Long.parseLong(kb.replaceAll("\\D", "")) / 1024;
        } catch (IOException | NumberFormatException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        return out;
    }
}
