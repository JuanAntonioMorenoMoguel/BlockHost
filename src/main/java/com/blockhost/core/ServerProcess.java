package com.blockhost.core;

import com.blockhost.core.providers.ForgeProvider;
import com.blockhost.core.providers.ServerProvider;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lanza y controla el proceso Java del servidor. Los listeners se llaman desde hilos de fondo. */
public final class ServerProcess {

    public enum Status { STOPPED, STARTING, RUNNING, STOPPING }

    // Se aplican al mensaje, sin la cabecera "[hora] [hilo/INFO]: ". Los nombres admiten más que A-Z0-9_
    // porque en modo offline, con Geyser (".Nombre") o con algunos mods pueden llevar otros caracteres.
    private static final Pattern JOINED = Pattern.compile("^(\\S{1,32}?)(?: \\(formerly known as \\S+\\))? joined the game");
    private static final Pattern LEFT = Pattern.compile("^(\\S{1,32}) left the game");
    private static final Pattern LOGGED_IN = Pattern.compile("^(\\S{1,32}?)\\[[^\\]]*] logged in with entity id");
    /** "There are 1 of a max of 20 players online: A, B" (1.13+) o "There are 1/20 players online:" (antes, nombres en la línea siguiente). */
    private static final Pattern LIST = Pattern.compile("^There are (\\d+) ?(?:of a max of|/) ?\\d+ players online:(.*)$");
    private static final Pattern DONE = Pattern.compile("^Done \\(");
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d?]*[ -/]*[@-~]");
    private static final long LIST_EVERY_S = 30;

    private final ServerInstance instance;
    private final List<Consumer<String>> outputListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Status>> statusListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Set<String>>> playerListeners = new CopyOnWriteArrayList<>();
    private final Set<String> players = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    private volatile Process process;
    private volatile PrintWriter stdin;
    private volatile Status status = Status.STOPPED;
    private volatile int namesNextLine = -1;          // formato antiguo de "list": los nombres vienen después
    /** Respuestas de comandos internos que se ocultan en la consola hasta cierto momento. */
    private record Quiet(long until, Predicate<String> filter) { }
    private final List<Quiet> quiet = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> rawListeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService ticker;

    public ServerProcess(ServerInstance instance) { this.instance = instance; }

    public void onOutput(Consumer<String> l) { outputListeners.add(l); }
    public void onStatus(Consumer<Status> l) { statusListeners.add(l); }
    public void onPlayers(Consumer<Set<String>> l) { playerListeners.add(l); }
    /** Cada mensaje del servidor (sin cabecera ni colores), también los ocultos de comandos internos. */
    public void onMessage(Consumer<String> l) { rawListeners.add(l); }

    public Set<String> players() { return Set.copyOf(players); }

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

        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "server-list-" + cfg.name());
            t.setDaemon(true);
            return t;
        });
        // por si se escapa algún aviso de entrada o salida (otros formatos, mods...), se repasa la lista cada poco
        ticker.scheduleWithFixedDelay(() -> { if (status == Status.RUNNING) requestList(); }, LIST_EVERY_S, LIST_EVERY_S, TimeUnit.SECONDS);
        ScheduledExecutorService myTicker = ticker;

        proc.onExit().thenAccept(p -> {
            myTicker.shutdownNow();
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
                String clean = ANSI.matcher(line).replaceAll("");
                String msg = message(clean);
                boolean hidden = isQuiet(msg);
                if (!hidden) emit(clean);
                parse(msg);
                for (var l : rawListeners) l.accept(msg);
            }
        } catch (IOException ignored) {
            // el proceso terminó
        }
    }

    /** Quita la cabecera del log ("[12:00:00] [Server thread/INFO] [minecraft/X]: ") y deja el mensaje. */
    static String message(String line) {
        int i = line.indexOf("]: ");
        return i >= 0 && line.startsWith("[") ? line.substring(i + 3).trim() : line.trim();
    }

    /** Analiza un mensaje ya sin cabecera. Paquete para los tests. */
    void parse(String msg) {
        if (status == Status.STARTING && DONE.matcher(msg).find()) {
            setStatus(Status.RUNNING);
            requestList();
        }
        if (namesNextLine >= 0) {
            int expected = namesNextLine;
            namesNextLine = -1;
            if (expected > 0 && !msg.isEmpty()) { setPlayers(msg); return; }
        }
        Matcher m = LIST.matcher(msg);
        if (m.find()) {
            String names = m.group(2).trim();
            int count = Integer.parseInt(m.group(1));
            if (names.isEmpty() && count > 0) namesNextLine = count;
            else setPlayers(names);
            return;
        }
        m = JOINED.matcher(msg);
        if (m.find()) { players.add(m.group(1)); firePlayers(); return; }
        m = LOGGED_IN.matcher(msg);
        if (m.find()) { players.add(m.group(1)); firePlayers(); return; }
        m = LEFT.matcher(msg);
        if (m.find()) { players.remove(m.group(1)); firePlayers(); }
    }

    private void setPlayers(String commaSeparated) {
        players.clear();
        for (String n : commaSeparated.split(",")) {
            String name = n.trim();
            if (!name.isEmpty()) players.add(name);
        }
        firePlayers();
    }

    /**
     * Pide la lista de conectados sin ensuciar la consola. Se usa "minecraft:list" para que un plugin
     * (Essentials...) no cambie el formato de la respuesta.
     */
    public void requestList() {
        boolean namespaced = ServerProvider.VERSION_ORDER.compare(instance.config().mcVersion(), "1.13") >= 0;
        sendQuiet(namespaced ? "minecraft:list" : "list", msg -> LIST.matcher(msg).find() || namesNextLine >= 0, 3000);
    }

    /** Envía un comando sin mostrarlo, y oculta durante ms las líneas de respuesta que cumplan filter. */
    public void sendQuiet(String command, Predicate<String> filter, long ms) {
        PrintWriter w = stdin;
        if (w == null || !isAlive()) return;
        long now = System.currentTimeMillis();
        quiet.removeIf(q -> q.until() < now);
        quiet.add(new Quiet(now + ms, filter));
        w.println(command);
    }

    private boolean isQuiet(String msg) {
        long now = System.currentTimeMillis();
        for (Quiet q : quiet) if (q.until() >= now && q.filter().test(msg)) return true;
        return false;
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
