package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.PlayerLists;
import com.blockhost.core.PlayerLists.Entry;
import com.blockhost.core.PlayerLists.Kind;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerProcess;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pestaña "Jugadores": quién está conectado, operadores, lista blanca y baneados, sin escribir comandos.
 * Con el servidor en marcha se envían los comandos; detenido, se editan los ficheros.
 */
final class PlayersPane extends VBox {

    private final ServerInstance instance;
    private final ServerProcess process;
    private final PlayerLists lists;

    private final ListView<String> online = new ListView<>();
    private final Label onlineTitle = new Label("Conectados (0)");
    private final Button opBtn = new Button("Hacer operador");
    private final Button kickBtn = new Button("Echar");
    private final Button banOnlineBtn = new Button("Banear");
    private final Map<Kind, ListView<Entry>> views = new EnumMap<>(Kind.class);
    private final CheckBox whitelistOn = new CheckBox("Activada: solo entran estos");
    private final Label status = new Label();

    PlayersPane(ServerInstance instance, ServerProcess process) {
        super(12);
        this.instance = instance;
        this.process = process;
        this.lists = new PlayerLists(instance);
        setPadding(new Insets(12, 0, 0, 0));

        Label help = new Label("Con el servidor en marcha los cambios se aplican al momento. Detenido, se guardan y "
                + "se aplican al arrancar." + (instance.config().onlineMode() ? ""
                : " Ojo: el servidor está en modo sin cuenta oficial (online-mode=false)."));
        help.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        help.setWrapText(true);
        help.setMinHeight(Region.USE_PREF_SIZE);
        status.getStyleClass().add(Styles.TEXT_SMALL);
        status.setWrapText(true);

        HBox columns = new HBox(12, onlineColumn(), listColumn(Kind.OPS, "Los operadores pueden usar todos los comandos."),
                listColumn(Kind.WHITELIST, null), listColumn(Kind.BANNED, "No pueden entrar."));
        columns.getChildren().forEach(c -> HBox.setHgrow(c, Priority.ALWAYS));
        VBox.setVgrow(columns, Priority.ALWAYS);
        getChildren().addAll(help, columns, status);

        whitelistOn.setOnAction(e -> setWhitelist(whitelistOn.isSelected()));
        reload();
    }

    // ---------------- construcción ----------------

    private VBox onlineColumn() {
        onlineTitle.getStyleClass().add(Styles.TEXT_BOLD);
        online.setPlaceholder(new Label("Nadie conectado"));
        VBox.setVgrow(online, Priority.ALWAYS);
        opBtn.setOnAction(e -> withSelectedOnline(n -> change(Kind.OPS, true, n, null)));
        kickBtn.setOnAction(e -> withSelectedOnline(n -> process.send("kick " + n)));
        banOnlineBtn.setOnAction(e -> withSelectedOnline(n -> askReasonAndBan(n)));
        banOnlineBtn.getStyleClass().add(Styles.DANGER);
        online.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
        VBox box = new VBox(8, onlineTitle, online, new HBox(6, opBtn, kickBtn), banOnlineBtn);
        box.setPrefWidth(200);
        box.setMinWidth(170);
        updateButtons();
        return box;
    }

    private VBox listColumn(Kind kind, String hint) {
        Label title = new Label(kind.label);
        title.getStyleClass().add(Styles.TEXT_BOLD);
        ListView<Entry> view = new ListView<>();
        view.setCellFactory(l -> new EntryCell());
        view.setPlaceholder(new Label("Vacía"));
        VBox.setVgrow(view, Priority.ALWAYS);
        views.put(kind, view);

        TextField name = new TextField();
        name.setPromptText("Nombre del jugador");
        Button add = new Button("Añadir");
        add.setMinWidth(Region.USE_PREF_SIZE);
        Runnable doAdd = () -> {
            String n = name.getText().trim();
            if (n.isEmpty()) return;
            if (kind == Kind.BANNED) askReasonAndBan(n);
            else change(kind, true, n, null);
            name.clear();
        };
        add.setOnAction(e -> doAdd.run());
        name.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) doAdd.run(); });
        HBox.setHgrow(name, Priority.ALWAYS);
        Button remove = new Button(kind == Kind.BANNED ? "Perdonar" : "Quitar");
        remove.setOnAction(e -> {
            Entry sel = view.getSelectionModel().getSelectedItem();
            if (sel != null) change(kind, false, sel.name(), null);
        });
        remove.disableProperty().bind(view.getSelectionModel().selectedItemProperty().isNull());

        VBox box = new VBox(8, title);
        if (kind == Kind.WHITELIST) box.getChildren().add(whitelistOn);
        else if (hint != null) {
            Label h = new Label(hint);
            h.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
            h.setWrapText(true);
            h.setMinHeight(Region.USE_PREF_SIZE);
            box.getChildren().add(h);
        }
        HBox addRow = new HBox(6, name, add);
        addRow.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(view, addRow, remove);
        box.setPrefWidth(210);
        box.setMinWidth(170);
        return box;
    }

    // ---------------- acciones ----------------

    void updateOnline(Set<String> players) {
        String sel = online.getSelectionModel().getSelectedItem();
        online.getItems().setAll(players.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        if (sel != null && players.contains(sel)) online.getSelectionModel().select(sel);
        onlineTitle.setText("Conectados (" + players.size() + ")");
        updateButtons();
    }

    void setServerRunning(boolean running) {
        updateButtons();
        reload();
    }

    private void updateButtons() {
        boolean none = online.getSelectionModel().getSelectedItem() == null || !process.isAlive();
        opBtn.setDisable(none);
        kickBtn.setDisable(none);
        banOnlineBtn.setDisable(none);
    }

    private void withSelectedOnline(java.util.function.Consumer<String> action) {
        String n = online.getSelectionModel().getSelectedItem();
        if (n != null) action.accept(n);
    }

    private void askReasonAndBan(String name) {
        TextInputDialog d = new TextInputDialog();
        d.initOwner(window());
        d.setHeaderText("¿Banear a " + name + "?");
        d.setContentText("Motivo (opcional):");
        d.showAndWait().ifPresent(reason -> change(Kind.BANNED, true, name, reason));
    }

    /** Añade o quita de una lista: comando si está en marcha, fichero si no. */
    private void change(Kind kind, boolean add, String name, String reason) {
        try {
            PlayerLists.checkName(name);
        } catch (IllegalArgumentException ex) {
            status.setText(ex.getMessage());
            return;
        }
        if (process.isAlive()) {
            process.send(PlayerLists.command(kind, add, name, reason));
            status.setText("Enviado: " + PlayerLists.command(kind, add, name, reason));
            reloadLater();
            return;
        }
        status.setText(add ? "Buscando a " + name + "..." : "");
        UiTasks.run(() -> {
            try {
                if (add) lists.add(kind, name, reason);
                else lists.remove(kind, name);
                Platform.runLater(() -> {
                    status.setText((add ? name + " añadido a " : name + " quitado de ") + kind.label.toLowerCase()
                            + ". Se aplicará al iniciar el servidor.");
                    reload();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status.setText("No se pudo: " + ex.getMessage()));
            }
        });
    }

    private void setWhitelist(boolean on) {
        if (process.isAlive()) {
            process.send("whitelist " + (on ? "on" : "off"));
            status.setText(on ? "Lista blanca activada: solo entran los de la lista." : "Lista blanca desactivada.");
            reloadLater();
            return;
        }
        try {
            lists.setWhitelistEnabled(on);
            status.setText(on ? "Lista blanca activada. Añade a tus amigos antes de iniciar." : "Lista blanca desactivada.");
        } catch (Exception ex) {
            status.setText("No se pudo cambiar: " + ex.getMessage());
        }
    }

    /** El servidor tarda un poco en escribir los ficheros tras un comando. */
    private void reloadLater() {
        UiTasks.run(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return;
            }
            Platform.runLater(this::reload);
        });
    }

    void reload() {
        UiTasks.run(() -> {
            Map<Kind, List<Entry>> data = new EnumMap<>(Kind.class);
            boolean wl;
            String error = null;
            try {
                for (Kind k : Kind.values()) data.put(k, lists.read(k));
                wl = lists.whitelistEnabled();
            } catch (Exception ex) {
                wl = false;
                error = ex.getMessage();
            }
            boolean whitelist = wl;
            String err = error;
            Platform.runLater(() -> {
                data.forEach((k, v) -> views.get(k).getItems().setAll(v));
                whitelistOn.setSelected(whitelist);
                if (err != null) status.setText(err);
            });
        });
    }

    private Window window() { return getScene() == null ? null : getScene().getWindow(); }

    private static final class EntryCell extends ListCell<Entry> {
        @Override
        protected void updateItem(Entry e, boolean empty) {
            super.updateItem(e, empty);
            if (empty || e == null) {
                setText(null);
                return;
            }
            setText(e.detail() == null || e.detail().isBlank() ? e.name() : e.name() + "  ·  " + e.detail());
        }
    }
}
