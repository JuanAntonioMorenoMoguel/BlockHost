package com.blockhost.core;

import com.blockhost.core.providers.ForgeProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lanza y controla el proceso Java del servidor. Los listeners se llaman desde hilos de fondo. */
public final class ServerProcess {

    public enum Status { STOPPED, STARTING, RUNNING, STOPPING }

    private static final Pattern JOINED = Pattern.compile("]: (\\w{3,16}) joined the game");
    private static final Pattern LEFT = Pattern.compile("]: (\\w{3,16}) left the game");
    private static final Pattern DONE = Pattern.compile("]: Done \\(");

    private final ServerInstance instance;
    private final List<Consumer<String>> outputListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Status>> statusListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Set<String>>> playerListeners = new CopyOnWriteArrayList<>();
    private final Set<String> players = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    private volatile Process process;
    private volatile PrintWriter stdin;
    private volatile Status status = Status.STOPPED;

    public ServerProcess(ServerInstance instance) { this.instance = instance; }

    public void onOutput(Consumer<String> l) { outputListeners.add(l); }
    public void onStatus(Consumer<Status> l) { statusListeners.add(l); }
    public void onPlayers(Consumer<Set<String>> l) { playerListeners.add(l); }

    public Status status() { return status; }
    public boolean isAlive() { return process != null && process.isAlive(); }

    /** El proceso del servidor mientras está en marcha (para medir CPU y RAM). */
    public ProcessHandle handle() {
        Process p = process;
        return p != null && p.isAlive() ? p.toHandle() : null;
    }

    public synchronized void start() throws IOException {
        if (isAlive()) return;
        ServerConfig cfg = instance.config();
        List<String> cmd = new ArrayList<>();
        cmd.add(instance.javaPath().toString());
        cmd.add("-Xms" + cfg.ramMb() + "M");
        cmd.add("-Xmx" + cfg.ramMb() + "M");
        cmd.addAll(jvmFlags(cfg.ramMb()));
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        cmd.addAll(ForgeProvider.launchArgs(instance.jarName(), System.getProperty("os.name").toLowerCase().contains("win")));
        cmd.add("nogui");

        players.clear();
        firePlayers();
        setStatus(Status.STARTING);
        emit("[BlockHost] Iniciando: " + String.join(" ", cmd));

        try {
            process = new ProcessBuilder(cmd)
                    .directory(instance.dir().toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            setStatus(Status.STOPPED);
            throw e;
        }
        stdin = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);

        Process proc = process;
        Thread reader = new Thread(() -> readOutput(proc), "server-output-" + cfg.name());
        reader.setDaemon(true);
        reader.start();

        proc.onExit().thenAccept(p -> {
            try {
                reader.join(3000); // que salgan las últimas líneas antes del aviso de parada
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            emit("[BlockHost] El servidor se ha detenido (código " + p.exitValue() + ")");
            players.clear();
            firePlayers();
            setStatus(Status.STOPPED);
        });
    }

    private void readOutput(Process proc) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                emit(line);
                parse(line);
            }
        } catch (IOException ignored) {
            // el proceso terminó
        }
    }

    private void parse(String line) {
        if (status == Status.STARTING && DONE.matcher(line).find()) setStatus(Status.RUNNING);
        Matcher m = JOINED.matcher(line);
        if (m.find()) { players.add(m.group(1)); firePlayers(); return; }
        m = LEFT.matcher(line);
        if (m.find()) { players.remove(m.group(1)); firePlayers(); }
    }

    /** Envía un comando a la consola del servidor (sin la barra inicial). */
    public void send(String command) {
        PrintWriter w = stdin;
        if (w == null || !isAlive()) return;
        String c = command.startsWith("/") ? command.substring(1) : command;
        emit("> " + c);
        w.println(c);
    }

    /** Parada limpia con "stop"; si en 60 s no ha terminado, se fuerza. */
    public void stop() {
        Process proc = process;
        if (proc == null || !proc.isAlive()) return;
        setStatus(Status.STOPPING);
        send("stop");
        proc.onExit().orTimeout(60, TimeUnit.SECONDS).exceptionally(t -> {
            emit("[BlockHost] No respondió a tiempo, forzando cierre...");
            proc.destroyForcibly();
            return proc;
        });
    }

    /** Bloquea hasta que el proceso termine (para el cierre de la app). */
    public void stopAndWait(long seconds) throws InterruptedException {
        Process proc = process;
        if (proc == null || !proc.isAlive()) return;
        stop();
        if (!proc.waitFor(seconds, TimeUnit.SECONDS)) proc.destroyForcibly();
    }

    /** Flags de G1 recomendadas por la comunidad (Aikar) para servidores de Minecraft. */
    static List<String> jvmFlags(int ramMb) {
        boolean big = ramMb >= 12 * 1024;
        return List.of(
                "-XX:+UseG1GC",
                "-XX:+ParallelRefProcEnabled",
                "-XX:MaxGCPauseMillis=200",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+DisableExplicitGC",
                "-XX:+AlwaysPreTouch",
                "-XX:G1NewSizePercent=" + (big ? 40 : 30),
                "-XX:G1MaxNewSizePercent=" + (big ? 50 : 40),
                "-XX:G1HeapRegionSize=" + (big ? "16M" : "8M"),
                "-XX:G1ReservePercent=" + (big ? 15 : 20),
                "-XX:G1HeapWastePercent=5",
                "-XX:G1MixedGCCountTarget=4",
                "-XX:InitiatingHeapOccupancyPercent=" + (big ? 20 : 15),
                "-XX:G1MixedGCLiveThresholdPercent=90",
                "-XX:G1RSetUpdatingPauseTimePercent=5",
                "-XX:SurvivorRatio=32",
                "-XX:+PerfDisableSharedMem",
                "-XX:MaxTenuringThreshold=1");
    }

    private void emit(String line) { for (var l : outputListeners) l.accept(line); }

    private void setStatus(Status s) {
        status = s;
        for (var l : statusListeners) l.accept(s);
    }

    private void firePlayers() {
        Set<String> snapshot = Set.copyOf(players);
        for (var l : playerListeners) l.accept(snapshot);
    }
}
