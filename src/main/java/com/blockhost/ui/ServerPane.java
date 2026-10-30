package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.Backups;
import com.blockhost.core.JavaRuntimeManager;
import com.blockhost.core.ServerConfig;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerProcess;
import com.blockhost.core.ServerProcess.Status;
import com.blockhost.core.ServerProperties;
import com.blockhost.core.ServerRegistry;
import com.blockhost.core.ServerType;
import com.blockhost.core.SystemInfo;
import com.blockhost.core.mods.CompatibilityChecker;
import com.blockhost.core.mods.ModManager;
import com.blockhost.core.providers.MojangMeta;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Panel de un servidor: estado y controles arriba; pestañas de consola, mods o plugins, jugadores,
 * copias de seguridad y rendimiento.
 */
public final class ServerPane extends BorderPane {

    private static final int CONSOLE_MAX_CHARS = 400_000;

    private final ServerInstance instance;
    private final ServerProcess process;
    private final HostServices host;
    private final Consumer<ServerInstance> onEdited;
    private final Consumer<ServerInstance> onDeleted;

    private final Label statusLabel = new Label();
    private final Label statsLabel = new Label();
    private final MenuButton settingsMenu = new MenuButton("⚙");
    private final MenuItem editItem = new MenuItem("Editar ajustes...");
    private final MenuItem deleteItem = new MenuItem("Borrar servidor...");
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
    private final PlayersPane playersPane;
    private final BackupsPane backupsPane;
    private final WorldSharePane sharePane;
    private final StatsPane statsPane;
    private final CommandAssist assist;
    private final ConnectionPane connectionPane;

    // La consola recibe líneas desde un hilo de fondo: se agrupan para no saturar el hilo de JavaFX.
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();

    /**
     * @param onEdited  tras guardar ajustes nuevos (la app rehace el panel)
     * @param onDeleted tras borrar el servidor
     */
    public ServerPane(ServerInstance instance, HostServices host, Consumer<ServerInstance> onEdited,
                      Consumer<ServerInstance> onDeleted) {
        this.instance = instance;
        this.host = host;
        this.onEdited = onEdited;
        this.onDeleted = onDeleted;
        this.process = new ServerProcess(instance);
        this.modsPane = instance.config().type().supportsAddons() ? new ModsPane(instance, host) : null;
        this.playersPane = new PlayersPane(instance, process);
        this.backupsPane = new BackupsPane(instance, process, host, this::appendLine);
        this.sharePane = new WorldSharePane(instance, process, host, this::appendLine, backupsPane::refresh);
        this.statsPane = new StatsPane(instance, process, s -> statsLabel.setText(s));
        this.assist = new CommandAssist(instance, process, input);
        this.connectionPane = new ConnectionPane(instance);

        setTop(buildHeader());
        setCenter(buildCenter());
        setPadding(new Insets(16));

        process.onOutput(this::appendLine);
        process.onStatus(s -> Platform.runLater(() -> updateStatus(s)));
        process.onPlayers(p -> Platform.runLater(() -> {
            updatePlayers(p);
            playersPane.updateOnline(p);
        }));
        updateStatus(Status.STOPPED);
    }

    public boolean isRunning() { return process.isAlive(); }

    /** Para las tareas de fondo del panel (medición y copias automáticas) cuando se descarta. */
    public void dispose() {
        statsPane.dispose();
        backupsPane.dispose();
    }

    public void stopAndWait() {
        try {
            process.stopAndWait(60);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        connectionPane.closeNow(); // la app se cierra: no esperar al aviso de parada para cerrar el puerto
    }

    // ---------------- construcción ----------------

    private VBox buildHeader() {
        ServerConfig c = instance.config();

        Label name = new Label(c.name());
        name.getStyleClass().add(Styles.TITLE_2);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statusLabel.getStyleClass().add(Styles.TITLE_4);
        statsLabel.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        HBox titleRow = new HBox(12, name, spacer, statsLabel, statusLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label meta = new Label(String.format("%s %s%s · %.1f GB RAM · puerto %d",
                c.type().displayName, c.mcVersion(),
                instance.loaderVersion() != null && c.type() != ServerType.FABRIC ? " (" + instance.loaderVersion() + ")" : "",
                c.ramMb() / 1024.0, c.port()));
        meta.getStyleClass().add(Styles.TEXT_MUTED);

        startBtn.getStyleClass().add(Styles.SUCCESS);
        stopBtn.getStyleClass().add(Styles.DANGER);
        startBtn.setOnAction(e -> start());
        stopBtn.setOnAction(e -> process.stop());
        backupBtn.setOnAction(e -> backupsPane.backupNow(Backups.MANUAL));
        folderBtn.setOnAction(e -> host.showDocument(instance.dir().toUri().toString()));
        editItem.setOnAction(e -> openSettings());
        deleteItem.setOnAction(e -> deleteServer());
        settingsMenu.getItems().setAll(editItem, new SeparatorMenuItem(), deleteItem);
        settingsMenu.setTooltip(new Tooltip("Ajustes y borrar"));
        Region btnSpacer = new Region();
        HBox.setHgrow(btnSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, startBtn, stopBtn, backupBtn, folderBtn, btnSpacer, settingsMenu);

        VBox header = new VBox(8, titleRow, meta, buttons, connectionPane);
        header.setPadding(new Insets(0, 0, 12, 0));
        return header;
    }

    private void openSettings() {
        try {
            new EditServerDialog(getScene().getWindow(), instance, ServerProperties.load(instance.dir()), onEdited).show();
        } catch (java.io.IOException ex) {
            appendLine("[BlockHost] No se pudo leer server.properties: " + ex.getMessage());
        }
    }

    private javafx.scene.Node buildCenter() {
        BorderPane consoleTab = new BorderPane(buildConsole());
        consoleTab.setRight(buildPlayers());
        consoleTab.setPadding(new Insets(12, 0, 0, 0));
        TabPane tabs = new TabPane(new Tab("Consola", consoleTab));
        if (modsPane != null) {
            modsPane.setPadding(new Insets(12, 0, 0, 0));
            tabs.getTabs().add(new Tab(instance.config().type().supportsPlugins() ? "Plugins" : "Mods", modsPane));
        }
        tabs.getTabs().addAll(new Tab("Jugadores", playersPane), new Tab("Copias", backupsPane),
                new Tab("Compartir", sharePane),
                new Tab("Rendimiento", statsPane));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return tabs;
    }

    private VBox buildConsole() {
        console.setEditable(false);
        console.setWrapText(false);
        console.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'DejaVu Sans Mono', monospace; -fx-font-size: 12px;");
        VBox.setVgrow(console, Priority.ALWAYS);

        input.setPromptText("Comando (p. ej. op TuNombre, say hola). Tab autocompleta, ↑ repite el anterior");
        input.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) sendCommand(); });
        sendBtn.setOnAction(e -> sendCommand());
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox inputRow = new HBox(8, input, sendBtn);

        // las sugerencias salen encima de la consola, pegadas al campo, como en el chat del juego
        StackPane consoleStack = new StackPane(console, assist.suggestions());
        VBox.setVgrow(consoleStack, Priority.ALWAYS);
        return new VBox(8, consoleStack, inputRow, assist.hint());
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
                    int free = nextFreePort(port);
                    appendLine("[BlockHost] El puerto " + port + " está ocupado. ¿Hay otro servidor o programa usándolo?"
                            + (free > 0 ? " Ciérralo o cambia el puerto en ⚙ → Editar ajustes (el " + free
                            + " está libre; si abriste el puerto a mano en el router, cámbialo también allí)." : ""));
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
                String what = instance.config().type().supportsPlugins() ? "Plugins" : "Mods";
                appendLine("[BlockHost] Aviso: hay " + errors + " problema(s) de compatibilidad entre "
                        + what.toLowerCase() + ". Si el servidor no arranca, revisa la pestaña «" + what + "».");
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

    /** Borrar no se puede deshacer: hay que escribir el nombre del servidor para confirmarlo. */
    private void deleteServer() {
        if (process.isAlive()) return;
        String name = instance.config().name();
        var type = instance.config().type();
        String extra = type.supportsPlugins() ? ", los plugins" : type.supportsMods() ? ", los mods" : "";
        Label what = new Label("Se borrará su carpeta entera: el mundo" + extra
                + " y las copias de seguridad. No se puede deshacer.");
        what.setWrapText(true);
        what.setMinHeight(Region.USE_PREF_SIZE);
        Label ask = new Label("Para confirmar, escribe el nombre del servidor:");
        Label expected = new Label(name);
        expected.getStyleClass().addAll(Styles.TEXT_BOLD, Styles.TITLE_4);
        TextField typed = new TextField();
        typed.setPromptText(name);
        typed.setPrefColumnCount(30);
        typed.setMaxWidth(Double.MAX_VALUE);
        Label hint = new Label("No importan mayúsculas ni espacios al principio o al final.");
        hint.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        VBox content = new VBox(10, what, ask, expected, typed, hint);
        content.setPrefWidth(480);

        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(getScene().getWindow());
        d.setTitle("Borrar servidor");
        d.setHeaderText("¿Borrar «" + name + "» para siempre?");
        d.getDialogPane().setContent(content);
        ButtonType delete = new ButtonType("Borrar", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, delete);
        Button ok = (Button) d.getDialogPane().lookupButton(delete);
        ok.getStyleClass().add(Styles.DANGER);
        ok.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> !typed.getText().trim().equalsIgnoreCase(name.trim()), typed.textProperty()));
        Platform.runLater(typed::requestFocus);
        if (d.showAndWait().orElse(ButtonType.CANCEL) != delete) return;
        setDisable(true);
        runInBackground(() -> {
            try {
                ServerRegistry.delete(instance);
                Platform.runLater(() -> onDeleted.accept(instance));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setDisable(false);
                    UiTasks.error(getScene().getWindow(), "No se pudo borrar del todo", ex.getMessage()
                            + "\n\nPuede que algún archivo esté abierto en otro programa. Lo que quede está en "
                            + instance.dir());
                    onDeleted.accept(instance);
                });
            }
        });
    }

    private void sendCommand() {
        String cmd = input.getText().trim();
        if (cmd.isEmpty() || !process.isAlive()) return;
        process.send(cmd);
        assist.sent(cmd);
        input.clear();
    }

    /** Primer puerto libre después de port, o -1. */
    private static int nextFreePort(int port) {
        for (int p = port + 1; p <= Math.min(65535, port + 50); p++) if (portFree(p)) return p;
        return -1;
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
            case STOPPED -> {
                statusLabel.setText("● Detenido");
                statusLabel.getStyleClass().add(Styles.TEXT_MUTED);
                connectionPane.serverStopped();
            }
            case STARTING -> {
                statusLabel.setText("● Iniciando...");
                statusLabel.getStyleClass().add(Styles.WARNING);
                connectionPane.serverStarting();
            }
            case RUNNING -> {
                statusLabel.setText("● En marcha");
                statusLabel.getStyleClass().add(Styles.SUCCESS);
                connectionPane.serverRunning();
            }
            case STOPPING -> { statusLabel.setText("● Deteniendo..."); statusLabel.getStyleClass().add(Styles.WARNING); }
        }
        boolean alive = s != Status.STOPPED;
        startBtn.setDisable(alive);
        stopBtn.setDisable(!alive || s == Status.STOPPING);
        input.setDisable(!alive);
        sendBtn.setDisable(!alive);
        if (modsPane != null) modsPane.setServerRunning(alive);
        playersPane.setServerRunning(alive);
        backupsPane.setServerRunning(alive);
        sharePane.setServerRunning(alive);
        editItem.setDisable(alive);
        deleteItem.setDisable(alive);
        settingsMenu.setTooltip(new Tooltip(alive ? "Detén el servidor para cambiar ajustes o borrarlo" : "Ajustes y borrar"));
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
