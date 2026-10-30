package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.net.PortForwarder;
import com.blockhost.core.net.PortProbe;
import com.blockhost.core.net.RouterPorts;
import com.blockhost.core.net.RouterPorts.Port;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * Pantalla para gestionar los puertos del router: ver los abiertos, abrir y cerrar a mano y comprobar desde
 * internet si llegan. Si el router no tiene UPnP ni NAT-PMP, lo dice y lleva a la guía.
 */
final class PortsDialog extends Stage {

    private static final String CUSTOM = "Otro puerto...";

    private final IntPredicate minecraftOn;
    private final Runnable showGuide;
    private final Label routerLabel = new Label("Buscando el router...");
    private final Label message = new Label();
    private final TableView<Port> table = new TableView<>();
    private final Map<String, String> checks = new ConcurrentHashMap<>();
    private final Button checkBtn = new Button("Comprobar desde internet");
    private final Button closeBtn = new Button("Cerrar puerto");
    private final Button closeAllBtn = new Button("Cerrar todos los de BlockHost");
    private final Button refreshBtn = new Button("Actualizar");
    private final Button guideBtn = new Button("Ver la guía para abrirlos a mano");
    private final ComboBox<String> preset = new ComboBox<>();
    private final Spinner<Integer> portField = new Spinner<>(1, 65535, 25565);
    private final ChoiceBox<String> protocol = new ChoiceBox<>();
    private final TextField description = new TextField();
    private final Button openBtn = new Button("Abrir");
    private final VBox openBox;
    private volatile RouterPorts router;
    private String publicIp;

    /**
     * @param minecraftOn si en ese puerto hay un servidor de BlockHost en marcha
     * @param suggestedPort puerto que se propone para abrir
     */
    PortsDialog(Window owner, int suggestedPort, IntPredicate minecraftOn, Runnable showGuide) {
        this.minecraftOn = minecraftOn;
        this.showGuide = showGuide;
        initOwner(owner);
        initModality(Modality.NONE);
        setTitle("Puertos del router");

        Label title = new Label("Puertos del router");
        title.getStyleClass().add(Styles.TITLE_3);
        routerLabel.setWrapText(true);
        routerLabel.setMinHeight(Region.USE_PREF_SIZE);
        routerLabel.getStyleClass().add(Styles.TEXT_MUTED);

        buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        for (Button b : List.of(checkBtn, closeBtn, closeAllBtn, refreshBtn)) b.setMinWidth(Region.USE_PREF_SIZE);
        closeBtn.getStyleClass().add(Styles.DANGER);
        checkBtn.setOnAction(e -> checkSelected());
        closeBtn.setOnAction(e -> closeSelected());
        closeAllBtn.setOnAction(e -> closeAll());
        refreshBtn.setOnAction(e -> {
            if (router != null && router.method() == RouterPorts.Method.NONE) router = null; // volver a buscarlo
            load();
        });
        guideBtn.setOnAction(e -> showGuide.run());
        guideBtn.setVisible(false);
        guideBtn.setManaged(false);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, checkBtn, closeBtn, spacer, closeAllBtn, refreshBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        // abrir uno nuevo
        preset.getItems().add(CUSTOM);
        for (RouterPorts.Preset p : RouterPorts.PRESETS) preset.getItems().add(p.label());
        preset.setValue(CUSTOM);
        preset.setOnAction(e -> applyPreset());
        protocol.getItems().addAll("TCP", "UDP", "TCP y UDP");
        protocol.setValue("TCP");
        portField.setEditable(true);
        portField.getValueFactory().setValue(suggestedPort);
        portField.setPrefWidth(110);
        description.setPromptText("Para qué es (opcional)");
        HBox.setHgrow(description, Priority.ALWAYS);
        openBtn.getStyleClass().add(Styles.ACCENT);
        openBtn.setMinWidth(Region.USE_PREF_SIZE);
        openBtn.setOnAction(e -> openPort());
        Label openTitle = new Label("Abrir un puerto en este PC");
        openTitle.getStyleClass().add(Styles.TEXT_BOLD);
        HBox openRow = new HBox(8, portField, protocol, description, openBtn);
        openRow.setAlignment(Pos.CENTER_LEFT);
        Label presetHint = new Label("Atajos para mods y plugins que usan su propio puerto:");
        presetHint.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        openBox = new VBox(6, openTitle, new HBox(8, presetHint, preset), openRow);
        ((HBox) openBox.getChildren().get(1)).setAlignment(Pos.CENTER_LEFT);

        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);
        Label note = new Label("Los puertos del servidor de Minecraft se abren y cierran solos al iniciarlo y "
                + "detenerlo. Los que abras aquí se quedan abiertos hasta que los cierres.");
        note.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);

        VBox root = new VBox(10, title, routerLabel, guideBtn, table, actions, openBox, message, note);
        root.setPadding(new Insets(20));
        setScene(new Scene(root, 820, 640));
        setControlsDisabled(true);
        load();
    }

    private void buildTable() {
        TableColumn<Port, String> port = new TableColumn<>("Puerto");
        port.setCellValueFactory(d -> new ReadOnlyStringWrapper(String.valueOf(d.getValue().port())));
        TableColumn<Port, String> proto = new TableColumn<>("Protocolo");
        proto.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().protocol()));
        TableColumn<Port, String> target = new TableColumn<>("Apunta a");
        target.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().thisPc()
                ? d.getValue().internalClient() + " (este PC)" : d.getValue().internalClient()));
        TableColumn<Port, String> desc = new TableColumn<>("Descripción");
        desc.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().description()
                + (d.getValue().enabled() ? "" : " (desactivado)")));
        TableColumn<Port, String> check = new TableColumn<>("Desde internet");
        check.setCellValueFactory(d -> new ReadOnlyStringWrapper(checks.getOrDefault(key(d.getValue()), "—")));
        table.getColumns().add(port);
        table.getColumns().add(proto);
        table.getColumns().add(target);
        table.getColumns().add(desc);
        table.getColumns().add(check);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        port.setMinWidth(75);
        port.setMaxWidth(80);
        proto.setMinWidth(100);
        proto.setMaxWidth(110);
        target.setPrefWidth(180);
        desc.setPrefWidth(220);
        check.setMinWidth(135);
        table.setPlaceholder(new Label("El router no tiene ningún puerto abierto."));
    }

    private static String key(Port p) { return p.port() + "/" + p.protocol(); }

    // ---------------- acciones ----------------

    private void load() {
        setControlsDisabled(true);
        message.setText("");
        UiTasks.run(() -> {
            RouterPorts r = router != null ? router : RouterPorts.discover();
            router = r;
            List<Port> ports;
            String error = null;
            try {
                ports = r.list();
            } catch (Exception e) {
                ports = List.of();
                error = "No se pudo leer la lista del router: " + e.getMessage();
            }
            List<Port> shown = ports;
            String err = error;
            Platform.runLater(() -> {
                showRouter(r);
                table.getItems().setAll(shown);
                if (err != null) setMessage(err, Styles.DANGER);
                setControlsDisabled(r.method() == RouterPorts.Method.NONE);
            });
        });
    }

    private void showRouter(RouterPorts r) {
        boolean none = r.method() == RouterPorts.Method.NONE;
        guideBtn.setVisible(none);
        guideBtn.setManaged(none);
        table.setDisable(none);
        routerLabel.getStyleClass().removeAll(Styles.TEXT_MUTED, Styles.WARNING);
        routerLabel.getStyleClass().add(none ? Styles.WARNING : Styles.TEXT_MUTED);
        switch (r.method()) {
            case UPNP -> routerLabel.setText("Router: " + (r.routerName() == null ? "encontrado" : r.routerName())
                    + " (UPnP). Este PC es " + r.localIp() + ". Se ven todos los puertos que tiene abiertos el router.");
            case NATPMP -> routerLabel.setText("Router: " + r.routerName() + " (NAT-PMP). Este PC es " + r.localIp()
                    + ". NAT-PMP no deja ver lo que abrieron otros programas: aquí salen solo los que abras desde BlockHost.");
            case NONE -> {
                routerLabel.setText("Tu router no tiene UPnP ni NAT-PMP (o están desactivados), así que BlockHost no "
                        + "puede ver ni cambiar sus puertos. Puedes abrirlos a mano en la página del router, o activar "
                        + "UPnP en ella.");
                table.setPlaceholder(new Label("Sin acceso a los puertos del router."));
            }
        }
    }

    private void openPort() {
        int port = spinner();
        String err = RouterPorts.validate(port);
        if (err != null) { setMessage(err, Styles.DANGER); return; }
        List<String> protos = "TCP y UDP".equals(protocol.getValue()) ? List.of("TCP", "UDP") : List.of(protocol.getValue());
        String desc = description.getText();
        RouterPorts r = router;
        setControlsDisabled(true);
        UiTasks.run(() -> {
            String result;
            String style;
            try {
                for (String p : protos) r.open(port, p, desc);
                result = "Puerto " + port + " " + String.join(" y ", protos) + " abierto hacia este PC.";
                style = Styles.SUCCESS;
            } catch (Exception e) {
                result = "No se pudo abrir: " + e.getMessage();
                style = Styles.DANGER;
            }
            String text = result;
            String st = style;
            Platform.runLater(() -> {
                load();
                setMessage(text, st);
            });
        });
    }

    private void closeSelected() {
        Port p = table.getSelectionModel().getSelectedItem();
        if (p == null) return;
        if (!p.thisPc() && !UiTasks.confirm(this, "¿Cerrar un puerto de otro equipo?",
                "El puerto " + p.port() + " " + p.protocol() + " apunta a " + p.internalClient() + ", no a este PC. "
                        + "Si lo cierras, ese equipo dejará de recibir conexiones de internet.", "Cerrar", true)) {
            return;
        }
        RouterPorts r = router;
        setControlsDisabled(true);
        UiTasks.run(() -> {
            String err = null;
            try {
                r.close(p);
            } catch (Exception e) {
                err = e.getMessage();
            }
            String e2 = err;
            Platform.runLater(() -> {
                checks.remove(key(p));
                load();
                setMessage(e2 == null ? "Puerto " + p.port() + " " + p.protocol() + " cerrado." : "No se pudo cerrar: " + e2,
                        e2 == null ? Styles.SUCCESS : Styles.DANGER);
            });
        });
    }

    private void closeAll() {
        if (!UiTasks.confirm(this, "¿Cerrar todos los puertos de BlockHost?",
                "Se cierran todos los que abrió BlockHost, también los de servidores en marcha (tus amigos de fuera "
                        + "no podrán entrar hasta que los vuelvas a abrir o reinicies el servidor).", "Cerrar todos", false)) {
            return;
        }
        RouterPorts r = router;
        setControlsDisabled(true);
        UiTasks.run(() -> {
            String text;
            String style;
            try {
                int n = r.closeAllOurs();
                text = n == 0 ? "No había puertos de BlockHost abiertos." : "Cerrados " + n + " puertos de BlockHost.";
                style = Styles.SUCCESS;
            } catch (Exception e) {
                text = "No se pudieron cerrar: " + e.getMessage();
                style = Styles.DANGER;
            }
            String t = text;
            String s = style;
            Platform.runLater(() -> {
                checks.clear();
                load();
                setMessage(t, s);
            });
        });
    }

    private void checkSelected() {
        Port p = table.getSelectionModel().getSelectedItem();
        if (p == null) return;
        if (!p.thisPc()) {
            setMessage("Ese puerto apunta a otro equipo: solo se pueden comprobar los de este PC.", Styles.WARNING);
            return;
        }
        checks.put(key(p), "Comprobando...");
        table.refresh();
        checkBtn.setDisable(true);
        UiTasks.run(() -> {
            if (publicIp == null) publicIp = PortForwarder.publicIp();
            PortProbe.Result res = PortProbe.check(publicIp, p.port(), p.protocol(), minecraftOn.test(p.port()));
            Platform.runLater(() -> {
                checks.put(key(p), switch (res.verdict()) {
                    case REACHABLE -> "✔ Llega";
                    case UNREACHABLE -> "✖ No llega";
                    case UNKNOWN -> "? No se puede";
                });
                table.refresh();
                setMessage("Puerto " + p.port() + " " + p.protocol() + ": " + res.why(), switch (res.verdict()) {
                    case REACHABLE -> Styles.SUCCESS;
                    case UNREACHABLE -> Styles.DANGER;
                    case UNKNOWN -> Styles.WARNING;
                });
                updateButtons();
            });
        });
    }

    // ---------------- utilidades ----------------

    private void applyPreset() {
        for (RouterPorts.Preset p : RouterPorts.PRESETS) {
            if (p.label().equals(preset.getValue())) {
                portField.getValueFactory().setValue(p.port());
                protocol.setValue(p.protocol());
                description.setText(p.label().replaceAll(" \\(.*\\)$", ""));
            }
        }
    }

    private int spinner() {
        try {
            return Integer.parseInt(portField.getEditor().getText().trim());
        } catch (NumberFormatException e) {
            return portField.getValue();
        }
    }

    private void setControlsDisabled(boolean disabled) {
        openBox.setDisable(disabled);
        closeAllBtn.setDisable(disabled);
        refreshBtn.setDisable(router == null);
        updateButtons();
        if (disabled) {
            checkBtn.setDisable(true);
            closeBtn.setDisable(true);
        }
    }

    private void updateButtons() {
        boolean none = table.getSelectionModel().getSelectedItem() == null || openBox.isDisabled();
        checkBtn.setDisable(none);
        closeBtn.setDisable(none);
    }

    private void setMessage(String text, String style) {
        message.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER, Styles.WARNING);
        if (style != null) message.getStyleClass().add(style);
        message.setText(text);
    }
}
