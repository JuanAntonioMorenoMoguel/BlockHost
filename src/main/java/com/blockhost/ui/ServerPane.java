package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.Backups;
import com.blockhost.core.JavaRuntimeManager;
import com.blockhost.core.ServerConfig;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerProcess;
import com.blockhost.core.ServerProcess.Status;
import com.blockhost.core.SystemInfo;
import com.blockhost.core.mods.CompatibilityChecker;
import com.blockhost.core.mods.ModManager;
import com.blockhost.core.providers.MojangMeta;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Panel de un servidor: estado, controles, consola en vivo, jugadores conectados y (en Fabric) mods. */
public final class ServerPane extends BorderPane {

    private static final int CONSOLE_MAX_CHARS = 400_000;

    private final ServerInstance instance;
    private final ServerProcess process;
    private final HostServices host;

    private final Label statusLabel = new Label();
    private final Button startBtn = new Button("▶ Iniciar");
    private final Button stopBtn = new Button("■ Detener");
    private final Button backupBtn = new Button("Copia de seguridad");
    private final Button folderBtn = new Button("Abrir carpeta");
    private final TextArea console = new TextArea();
    private final TextField input = new TextField();
    private final Button sendBtn = new Button("Enviar");
    private final Label playersTitle = new Label("Jugadores (0)");
    private final ListView<String> playersList = new ListView<>();
    private final ModsPane modsPane;

    // La consola recibe líneas desde un hilo de fondo: se agrupan para no saturar el hilo de JavaFX.
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();

    public ServerPane(ServerInstance instance, HostServices host) {
        this.instance = instance;
        this.host = host;
        this.process = new ServerProcess(instance);
        this.modsPane = instance.config().type().supportsMods() ? new ModsPane(instance, host) : null;

        setTop(buildHeader());
        setCenter(buildCenter());
        setPadding(new Insets(16));

        process.onOutput(this::appendLine);
        process.onStatus(s -> Platform.runLater(() -> updateStatus(s)));
        process.onPlayers(p -> Platform.runLater(() -> updatePlayers(p)));
        updateStatus(Status.STOPPED);
    }

    public boolean isRunning() { return process.isAlive(); }

    public void stopAndWait() {
        try {
            process.stopAndWait(60);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------- construcción ----------------

    private VBox buildHeader() {
        ServerConfig c = instance.config();

        Label name = new Label(c.name());
        name.getStyleClass().add(Styles.TITLE_2);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statusLabel.getStyleClass().add(Styles.TITLE_4);
        HBox titleRow = new HBox(12, name, spacer, statusLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label meta = new Label(String.format("%s %s · %.1f GB RAM · puerto %d",
                c.type().displayName, c.mcVersion(), c.ramMb() / 1024.0, c.port()));
        meta.getStyleClass().add(Styles.TEXT_MUTED);

        startBtn.getStyleClass().add(Styles.SUCCESS);
        stopBtn.getStyleClass().add(Styles.DANGER);
        startBtn.setOnAction(e -> start());
        stopBtn.setOnAction(e -> process.stop());
        backupBtn.setOnAction(e -> backup());
        folderBtn.setOnAction(e -> host.showDocument(instance.dir().toUri().toString()));
        HBox buttons = new HBox(8, startBtn, stopBtn, backupBtn, folderBtn);

        VBox header = new VBox(8, titleRow, meta, buttons, connectionInfo(c));
        header.setPadding(new Insets(0, 0, 12, 0));
        return header;
    }

    /** Consola y jugadores; en servidores con mods, además una pestaña para gestionarlos. */
    private javafx.scene.Node buildCenter() {
        BorderPane consoleTab = new BorderPane(buildConsole());
        consoleTab.setRight(buildPlayers());
        if (modsPane == null) return consoleTab;

        consoleTab.setPadding(new Insets(12, 0, 0, 0));
        modsPane.setPadding(new Insets(12, 0, 0, 0));
        Tab console = new Tab("Consola", consoleTab);
        Tab mods = new Tab("Mods", modsPane);
        TabPane tabs = new TabPane(console, mods);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return tabs;
    }

    private Label connectionInfo(ServerConfig c) {
        List<String> ips = SystemInfo.localIPv4();
        String lan = ips.isEmpty() ? "—" : String.join(", ", ips.stream().map(ip -> ip + ":" + c.port()).toList());
        String extra = c.type().supportsMods()
                ? "\nMods: gestiónalos en la pestaña «Mods» (los jugadores necesitan los mismos)."
                : c.type().supportsPlugins() ? "\nPlugins: copia los .jar en la carpeta «plugins»." : "";
        Label l = new Label("Desde este PC: localhost:" + c.port()
                + "    ·    Desde tu red (Wi-Fi/cable): " + lan
                + "\nAmigos fuera de tu casa: abre el puerto " + c.port()
                + " (TCP) en el router o usa un túnel como playit.gg." + extra);
        l.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        l.setWrapText(true);
        return l;
    }

    private VBox buildConsole() {
        console.setEditable(false);
        console.setWrapText(false);
        console.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace; -fx-font-size: 12px;");
        VBox.setVgrow(console, Priority.ALWAYS);

        input.setPromptText("Comando (p. ej. op TuNombre, whitelist add Amigo, say hola)");
        input.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) sendCommand(); });
        sendBtn.setOnAction(e -> sendCommand());
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox inputRow = new HBox(8, input, sendBtn);

        return new VBox(8, console, inputRow);
    }

    private VBox buildPlayers() {
        playersList.setPlaceholder(new Label("Nadie conectado"));
        VBox.setVgrow(playersList, Priority.ALWAYS);
        VBox box = new VBox(8, playersTitle, playersList);
        box.setPadding(new Insets(0, 0, 0, 12));
        box.setPrefWidth(190);
        return box;
    }

    // ---------------- acciones ----------------

    private void start() {
        startBtn.setDisable(true);
        runInBackground(() -> {
            try {
                int port = instance.config().port();
                if (!portFree(port)) {
                    appendLine("[BlockHost] El puerto " + port + " está ocupado. ¿Hay otro servidor en marcha?");
                    Platform.runLater(() -> updateStatus(process.status()));
                    return;
                }
                ensureJava();
                warnAboutMods();
                process.start();
            } catch (Exception ex) {
                appendLine("[BlockHost] No se pudo iniciar: " + ex.getMessage());
                Platform.runLater(() -> updateStatus(process.status()));
            }
        });
    }

    /** No impide arrancar (el usuario puede saber más que la comprobación), pero avisa en la consola. */
    private void warnAboutMods() {
        if (modsPane == null) return;
        try {
            long errors = new ModManager(instance).check().stream()
                    .filter(i -> i.severity() == CompatibilityChecker.Severity.ERROR).count();
            if (errors > 0) {
                appendLine("[BlockHost] Aviso: hay " + errors + " problema(s) de compatibilidad entre mods. "
                        + "Si el servidor no arranca, revisa la pestaña «Mods».");
            }
        } catch (IOException ignored) {
            // si no se pueden leer los mods, que lo diga Fabric al arrancar
        }
    }

    /** Si el Java del servidor se borró, se vuelve a descargar antes de arrancar. */
    private void ensureJava() throws Exception {
        if (Files.isRegularFile(instance.javaPath())) return;
        appendLine("[BlockHost] Falta Java para este servidor, descargándolo...");
        int major = MojangMeta.requiredJava(instance.config().mcVersion());
        Path java = new JavaRuntimeManager().ensureJava(major, (msg, f) -> {
            if (!msg.endsWith("MB)")) appendLine("[BlockHost] " + msg);
        });
        instance.setJavaPath(java);
        instance.save();
    }

    private void backup() {
        backupBtn.setDisable(true);
        appendLine("[BlockHost] Creando copia de seguridad...");
        runInBackground(() -> {
            try {
                if (process.isAlive()) {
                    process.send("save-all flush");
                    Thread.sleep(3000); // margen para que el servidor escriba el mundo a disco
                }
                Path zip = Backups.create(instance);
                appendLine("[BlockHost] Copia guardada en backups/" + zip.getFileName());
            } catch (Exception ex) {
                appendLine("[BlockHost] Error en la copia: " + ex.getMessage());
            } finally {
                Platform.runLater(() -> backupBtn.setDisable(false));
            }
        });
    }

    private void sendCommand() {
        String cmd = input.getText().trim();
        if (cmd.isEmpty() || !process.isAlive()) return;
        process.send(cmd);
        input.clear();
    }

    private static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            s.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------- actualización de la UI ----------------

    private void updateStatus(Status s) {
        statusLabel.getStyleClass().removeAll(Styles.SUCCESS, Styles.WARNING, Styles.TEXT_MUTED);
        switch (s) {
            case STOPPED -> { statusLabel.setText("● Detenido"); statusLabel.getStyleClass().add(Styles.TEXT_MUTED); }
            case STARTING -> { statusLabel.setText("● Iniciando..."); statusLabel.getStyleClass().add(Styles.WARNING); }
            case RUNNING -> { statusLabel.setText("● En marcha"); statusLabel.getStyleClass().add(Styles.SUCCESS); }
            case STOPPING -> { statusLabel.setText("● Deteniendo..."); statusLabel.getStyleClass().add(Styles.WARNING); }
        }
        boolean alive = s != Status.STOPPED;
        startBtn.setDisable(alive);
        stopBtn.setDisable(!alive || s == Status.STOPPING);
        input.setDisable(!alive);
        sendBtn.setDisable(!alive);
        if (modsPane != null) modsPane.setServerRunning(alive);
    }

    private void updatePlayers(Set<String> players) {
        playersList.getItems().setAll(players.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        playersTitle.setText("Jugadores (" + players.size() + ")");
    }

    private void appendLine(String line) {
        pending.add(line);
        if (flushScheduled.compareAndSet(false, true)) Platform.runLater(this::flushConsole);
    }

    private void flushConsole() {
        flushScheduled.set(false);
        StringBuilder b = new StringBuilder();
        String l;
        while ((l = pending.poll()) != null) b.append(l).append('\n');
        if (b.isEmpty()) return;
        console.appendText(b.toString());
        if (console.getLength() > CONSOLE_MAX_CHARS) {
            console.deleteText(0, console.getLength() - CONSOLE_MAX_CHARS * 3 / 4);
            console.positionCaret(console.getLength());
            console.setScrollTop(Double.MAX_VALUE);
        }
    }

    private static void runInBackground(Runnable r) {
        Thread t = new Thread(r, "server-action");
        t.setDaemon(true);
        t.start();
    }
}
