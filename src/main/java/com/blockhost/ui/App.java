package com.blockhost.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.Styles;
import com.blockhost.core.DesktopShortcut;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.Settings;
import com.blockhost.core.ServerRegistry;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class App extends Application {

    private final ListView<ServerInstance> serverList = new ListView<>();
    private final StackPane content = new StackPane();
    /** Un panel por servidor: conserva consola y proceso aunque cambies de servidor en la lista. */
    private final Map<Path, ServerPane> panes = new HashMap<>();
    private Stage stage;
    private BorderPane root;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        Label title = new Label("BlockHost");
        title.getStyleClass().add(Styles.TITLE_3);
        Label subtitle = new Label("Tus servidores");
        subtitle.getStyleClass().add(Styles.TEXT_MUTED);

        Button add = new Button("+ Nuevo servidor");
        add.getStyleClass().add(Styles.ACCENT);
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(e -> openWizard());
        Button fromPack = new Button("+ Desde modpack");
        fromPack.setMaxWidth(Double.MAX_VALUE);
        fromPack.setOnAction(e -> new ModpackServerDialog(stage, getHostServices(), inst -> refresh(inst.dir())).show());

        serverList.setPlaceholder(new Label("Aún no hay servidores"));
        serverList.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> show(sel));
        VBox.setVgrow(serverList, Priority.ALWAYS);

        VBox sidebar = new VBox(10, title, subtitle, serverList, add, fromPack);
        if (DesktopShortcut.supported()) {
            Hyperlink shortcut = new Hyperlink("Crear acceso directo en el escritorio");
            shortcut.getStyleClass().add(Styles.TEXT_SMALL);
            shortcut.setOnAction(e -> createShortcut(true));
            sidebar.getChildren().add(shortcut);
        }
        sidebar.setPadding(new Insets(16));
        sidebar.setPrefWidth(240);

        root = new BorderPane(content);
        root.setLeft(sidebar);

        refresh(null);

        stage.setTitle("BlockHost");
        var icon = App.class.getResourceAsStream("/com/blockhost/icon.png");
        if (icon != null) stage.getIcons().add(new javafx.scene.image.Image(icon));
        stage.setScene(new Scene(root, 1150, 720));
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.setOnCloseRequest(this::onClose);
        if (DesktopShortcut.supported() && Settings.get(DesktopShortcut.SETTING) == null) createShortcut(false);
        stage.show();
    }

    private void refresh(Path select) {
        List<ServerInstance> servers = ServerRegistry.list();
        serverList.getItems().setAll(servers);
        ServerInstance target = servers.stream()
                .filter(s -> s.dir().equals(select))
                .findFirst()
                .orElse(servers.isEmpty() ? null : servers.get(0));
        if (target != null) serverList.getSelectionModel().select(target);
        else show(null);
    }

    private void show(ServerInstance inst) {
        if (inst == null) {
            content.getChildren().setAll(welcome());
            return;
        }
        ServerPane pane = panes.computeIfAbsent(inst.dir(), d -> new ServerPane(inst, getHostServices(),
                this::edited, this::deleted));
        content.getChildren().setAll(pane);
    }

    private VBox welcome() {
        Label h = new Label("Crea tu servidor de Minecraft");
        h.getStyleClass().add(Styles.TITLE_2);
        Label p = new Label("Gratis, en tu propio PC y sin configurar nada a mano.\n"
                + "El asistente descarga el servidor y el Java correcto por ti.");
        p.getStyleClass().add(Styles.TEXT_MUTED);
        p.setWrapText(true);
        Button b = new Button("Crear mi primer servidor");
        b.getStyleClass().addAll(Styles.ACCENT, Styles.LARGE);
        b.setOnAction(e -> openWizard());
        VBox box = new VBox(14, h, p, b);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(460);
        return box;
    }

    /**
     * Al primer arranque de la app instalada se crea solo; luego, solo si el usuario lo pide
     * (si lo borró, no se le vuelve a poner).
     */
    private void createShortcut(boolean askedByUser) {
        Thread t = new Thread(() -> {
            try {
                Path lnk = DesktopShortcut.create();
                Settings.set(DesktopShortcut.SETTING, "true");
                if (askedByUser) Platform.runLater(() -> UiTasks.info(stage, "Acceso directo creado", lnk.toString()));
            } catch (Exception ex) {
                try {
                    Settings.set(DesktopShortcut.SETTING, "false");
                } catch (Exception ignored) {
                    // se reintentará en el próximo arranque
                }
                if (askedByUser) {
                    Platform.runLater(() -> UiTasks.error(stage, "No se pudo crear el acceso directo", ex.getMessage()));
                }
            }
        }, "desktop-shortcut");
        t.setDaemon(true);
        t.start();
    }

    /** Tras cambiar ajustes se rehace el panel (con el servidor detenido, no se pierde nada importante). */
    private void edited(ServerInstance inst) {
        ServerPane old = panes.remove(inst.dir());
        if (old != null) old.dispose();
        refresh(inst.dir());
    }

    private void deleted(ServerInstance inst) {
        ServerPane old = panes.remove(inst.dir());
        if (old != null) old.dispose();
        refresh(null);
    }

    private void openWizard() {
        new WizardDialog(stage, getHostServices(), inst -> refresh(inst.dir())).show();
    }

    private void onClose(WindowEvent e) {
        List<ServerPane> running = panes.values().stream().filter(ServerPane::isRunning).toList();
        if (running.isEmpty()) return;
        e.consume();

        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Hay " + running.size() + " servidor(es) en marcha. Se detendrán guardando el mundo antes de salir.",
                ButtonType.OK, ButtonType.CANCEL);
        a.initOwner(stage);
        a.setHeaderText("¿Cerrar BlockHost?");
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        root.setDisable(true);
        stage.setTitle("BlockHost — deteniendo servidores...");
        Thread t = new Thread(() -> {
            for (ServerPane p : running) p.stopAndWait();
            Platform.runLater(Platform::exit);
        }, "shutdown");
        t.setDaemon(true);
        t.start();
    }
}
