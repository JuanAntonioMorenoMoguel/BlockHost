package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.ServerConfig;
import com.blockhost.core.ServerInstaller;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerProperties;
import com.blockhost.core.ServerRegistry;
import com.blockhost.core.SystemInfo;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.util.converter.DefaultStringConverter;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Ajustes de un servidor ya creado. «General»: lo habitual (nombre, RAM, puerto, opciones de juego, distancias,
 * protección del spawn, lista blanca). «Avanzado»: cualquier otra opción de server.properties, explicada.
 * La versión y el tipo no se cambian aquí: eso es otro servidor.
 */
final class EditServerDialog extends Stage {

    /** Claves que se editan en «General» y por eso no salen en «Avanzado». */
    private static final Set<String> GENERAL_KEYS = Set.of("server-port", "motd", "max-players", "difficulty", "gamemode",
            "online-mode", "pvp", "white-list", "enforce-whitelist", "spawn-protection", "view-distance",
            "simulation-distance", "entity-broadcast-range-percentage");

    private final ServerInstance instance;
    private final Consumer<ServerInstance> onSaved;
    private final ServerProperties props;

    private final TextField nameField = new TextField();
    private final Slider ramSlider = new Slider();
    private final Label ramLabel = new Label();
    private final Spinner<Integer> portSpinner = new Spinner<>(1024, 65535, 25565);
    private final TextField motdField = new TextField();
    private final Spinner<Integer> maxPlayersSpinner = new Spinner<>(1, 1000, 10);
    private final ComboBox<String> difficultyBox = WizardDialog.choice(WizardDialog.labels(
            "peaceful", "Pacífico", "easy", "Fácil", "normal", "Normal", "hard", "Difícil"), "normal");
    private final ComboBox<String> gamemodeBox = WizardDialog.choice(WizardDialog.labels(
            "survival", "Supervivencia", "creative", "Creativo", "adventure", "Aventura", "spectator", "Espectador"),
            "survival");
    private final Spinner<Integer> viewDistance = new Spinner<>(3, 32, 10);
    private final Spinner<Integer> simulationDistance = new Spinner<>(3, 32, 10);
    private final Spinner<Integer> entityDistance = new Spinner<>(10, 1000, 100, 10);
    private final Spinner<Integer> spawnProtection = new Spinner<>(0, 1000, 16);
    private final CheckBox onlineModeBox = new CheckBox("Solo cuentas oficiales de Minecraft (recomendado)");
    private final CheckBox pvpBox = new CheckBox("Permitir PvP entre jugadores");
    private final CheckBox whitelistBox = new CheckBox("Lista blanca: solo pueden entrar los jugadores de la lista");
    private final CheckBox routerPortBox = new CheckBox("Abrir el puerto en el router al iniciar (UPnP / NAT-PMP)");
    private final Label errorLabel = new Label();

    /** Fila de «Avanzado»: clave y valor (editable). */
    private static final class Row {
        final String key;
        final SimpleStringProperty value;
        final String original;

        Row(String key, String value) {
            this.key = key;
            this.value = new SimpleStringProperty(value);
            this.original = value;
        }
    }

    private final ObservableList<Row> rows = FXCollections.observableArrayList();

    EditServerDialog(Window owner, ServerInstance instance, ServerProperties props, Consumer<ServerInstance> onSaved) {
        this.instance = instance;
        this.onSaved = onSaved;
        this.props = props;
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setTitle("Ajustes de " + instance.config().name());

        Label title = new Label("Ajustes del servidor");
        title.getStyleClass().add(Styles.TITLE_3);
        ServerConfig c = instance.config();
        Label fixed = new Label(c.type().displayName + " " + c.mcVersion()
                + (instance.loaderVersion() != null ? " · " + instance.loaderVersion() : "")
                + "  (para otra versión, crea un servidor nuevo)");
        fixed.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);

        TabPane tabs = new TabPane(new Tab("General", general()), new Tab("Avanzado", advanced()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        errorLabel.getStyleClass().add(Styles.DANGER);
        errorLabel.setWrapText(true);
        errorLabel.setMinHeight(Region.USE_PREF_SIZE);

        Button cancel = new Button("Cancelar");
        cancel.setOnAction(e -> close());
        Button save = new Button("Guardar");
        save.getStyleClass().add(Styles.ACCENT);
        save.setDefaultButton(true);
        save.setOnAction(e -> save());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, cancel, spacer, save);

        VBox center = new VBox(10, new VBox(2, title, fixed), tabs, errorLabel);
        BorderPane root = new BorderPane(center, null, null, buttons, null);
        root.setPadding(new Insets(20));
        BorderPane.setMargin(buttons, new Insets(12, 0, 0, 0));
        setScene(new Scene(root, 640, 720));
    }

    // ---------------- General ----------------

    private ScrollPane general() {
        ServerConfig c = instance.config();
        nameField.setText(c.name());
        int max = SystemInfo.maxRamMb();
        ramSlider.setMin(1024);
        ramSlider.setMax(Math.max(Math.max(1024, max), c.ramMb()));
        ramSlider.setMajorTickUnit(1024);
        ramSlider.setMinorTickCount(1);
        ramSlider.setBlockIncrement(512);
        ramSlider.setSnapToTicks(true);
        ramSlider.setValue(c.ramMb());
        ramSlider.valueProperty().addListener((o, a, b) -> ramLabel.setText(String.format("%.1f GB", b.doubleValue() / 1024)));
        ramLabel.setText(String.format("%.1f GB", c.ramMb() / 1024.0));
        HBox.setHgrow(ramSlider, Priority.ALWAYS);
        motdField.setText(c.motd());
        difficultyBox.setValue(c.difficulty());
        gamemodeBox.setValue(c.gamemode());
        onlineModeBox.setSelected(c.onlineMode());
        pvpBox.setSelected(c.pvp());
        whitelistBox.setSelected(props.getBool("white-list", false));
        routerPortBox.setSelected(instance.openRouterPort());
        routerPortBox.setTooltip(new javafx.scene.control.Tooltip("Para que tus amigos entren desde fuera de tu casa. "
                + "Se cierra al detener el servidor."));
        set(portSpinner, c.port());
        set(maxPlayersSpinner, c.maxPlayers());
        set(viewDistance, props.getInt("view-distance", 10));
        set(simulationDistance, props.getInt("simulation-distance", 10));
        set(entityDistance, props.getInt("entity-broadcast-range-percentage", 100));
        set(spawnProtection, props.getInt("spawn-protection", 16));

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        int r = 0;
        g.addRow(r++, new Label("Nombre"), nameField);
        g.addRow(r++, new Label("RAM"), new HBox(8, ramSlider, ramLabel));
        g.addRow(r++, new Label("Puerto"), portSpinner);
        g.addRow(r++, new Label("Mensaje (MOTD)"), motdField);
        g.addRow(r++, new Label("Jugadores máx."), maxPlayersSpinner);
        g.addRow(r++, new Label("Dificultad"), difficultyBox);
        g.addRow(r++, new Label("Modo de juego"), gamemodeBox);
        g.addRow(r++, labelWithHelp("Distancia de renderizado", "view-distance"), withUnit(viewDistance, "chunks"));
        g.addRow(r++, labelWithHelp("Distancia de simulación", "simulation-distance"), withUnit(simulationDistance, "chunks"));
        g.addRow(r++, labelWithHelp("Distancia de entidades", "entity-broadcast-range-percentage"), withUnit(entityDistance, "% de la normal"));
        g.addRow(r++, labelWithHelp("Protección del spawn", "spawn-protection"), withUnit(spawnProtection, "bloques (0 = sin protección)"));
        GridPane.setHgrow(nameField, Priority.ALWAYS);

        Label perf = new Label("Bajar las distancias es lo que más alivia un PC justo de recursos.");
        perf.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        Label note = new Label("La carpeta del servidor no cambia aunque le cambies el nombre.");
        note.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);

        VBox box = new VBox(12, g, perf, pvpBox, onlineModeBox, whitelistBox, routerPortBox, note);
        box.setPadding(new Insets(12, 12, 12, 0));
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        return sp;
    }

    private static Label labelWithHelp(String text, String key) {
        Label l = new Label(text);
        ServerProperties.Info i = ServerProperties.info(key);
        if (i != null) l.setTooltip(new Tooltip(i.help()));
        return l;
    }

    private static HBox withUnit(Spinner<Integer> s, String unit) {
        s.setEditable(true);
        s.setPrefWidth(110);
        Label u = new Label(unit);
        u.getStyleClass().add(Styles.TEXT_MUTED);
        HBox h = new HBox(8, s, u);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return h;
    }

    private static void set(Spinner<Integer> s, int v) {
        s.setEditable(true);
        var f = (javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory) s.getValueFactory();
        s.getValueFactory().setValue(Math.max(f.getMin(), Math.min(f.getMax(), v)));
    }

    // ---------------- Avanzado ----------------

    private VBox advanced() {
        for (String k : props.allKeys()) {
            if (!GENERAL_KEYS.contains(k)) rows.add(new Row(k, props.get(k, "")));
        }
        FilteredList<Row> shown = new FilteredList<>(rows);
        TextField search = new TextField();
        search.setPromptText("Buscar opción (p. ej. nether, seed, command)");
        search.textProperty().addListener((o, a, b) -> {
            String q = b.trim().toLowerCase();
            shown.setPredicate(q.isEmpty() ? null : row -> row.key.toLowerCase().contains(q)
                    || (ServerProperties.info(row.key) != null && (ServerProperties.info(row.key).label() + " "
                    + ServerProperties.info(row.key).help()).toLowerCase().contains(q)));
        });

        TableView<Row> table = new TableView<>(shown);
        table.setEditable(true);
        TableColumn<Row, String> name = new TableColumn<>("Opción");
        name.setCellValueFactory(d -> new ReadOnlyStringWrapper(label(d.getValue().key)));
        name.setEditable(false);
        TableColumn<Row, String> key = new TableColumn<>("Clave");
        key.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue().key));
        key.setEditable(false);
        key.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String k, boolean empty) {
                super.updateItem(k, empty);
                setText(empty ? null : k);
                getStyleClass().remove(Styles.TEXT_MUTED);
                if (!empty) getStyleClass().add(Styles.TEXT_MUTED);
            }
        });
        TableColumn<Row, String> value = new TableColumn<>("Valor");
        value.setCellValueFactory(d -> d.getValue().value);
        value.setCellFactory(c -> new TextFieldTableCell<>(new DefaultStringConverter()) {
            @Override
            public void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                getStyleClass().remove(Styles.TEXT_MUTED);
                if (!empty && !isEditing() && (v == null || v.isEmpty())) {
                    setText("(por defecto)"); // no está en el fichero: el servidor usa su valor de fábrica
                    getStyleClass().add(Styles.TEXT_MUTED);
                }
            }
        });
        value.setOnEditCommit(e -> e.getRowValue().value.set(e.getNewValue() == null ? "" : e.getNewValue().trim()));
        table.getColumns().add(name);
        table.getColumns().add(key);
        table.getColumns().add(value);
        table.setPlaceholder(new Label("Ninguna opción coincide"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        name.prefWidthProperty().bind(table.widthProperty().multiply(0.38));
        key.prefWidthProperty().bind(table.widthProperty().multiply(0.34));
        VBox.setVgrow(table, Priority.ALWAYS);

        Label help = new Label("Doble clic en un valor para cambiarlo. Selecciona una opción para ver qué hace.");
        help.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        help.setWrapText(true);
        help.setMinHeight(Region.USE_PREF_SIZE);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, row) -> {
            ServerProperties.Info i = row == null ? null : ServerProperties.info(row.key);
            help.setText(row == null ? "" : i == null ? row.key + ": opción de esta versión o de un mod/plugin."
                    : i.label() + ": " + i.help());
        });
        Label warn = new Label("Cambia solo lo que entiendas: un valor raro puede impedir que el servidor arranque.");
        warn.getStyleClass().addAll(Styles.WARNING, Styles.TEXT_SMALL);

        VBox box = new VBox(8, search, table, help, warn);
        box.setPadding(new Insets(12, 0, 0, 0));
        return box;
    }

    private static String label(String key) {
        ServerProperties.Info i = ServerProperties.info(key);
        return i == null ? key : i.label();
    }

    // ---------------- guardar ----------------

    private void save() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) { errorLabel.setText("Ponle un nombre al servidor."); return; }
        if (ServerRegistry.nameTaken(name, instance)) { errorLabel.setText("Ya tienes otro servidor con ese nombre."); return; }

        Map<String, String> changed = new LinkedHashMap<>();
        for (Row r : rows) {
            String v = r.value.get() == null ? "" : r.value.get().trim();
            if (v.equals(r.original == null ? "" : r.original)) continue;
            String problem = ServerProperties.validate(r.key, v);
            if (problem != null) { errorLabel.setText("«" + label(r.key) + "» (" + r.key + "): " + problem); return; }
            changed.put(r.key, v);
        }

        ServerConfig old = instance.config();
        ServerConfig cfg = new ServerConfig(name, old.type(), old.mcVersion(), (int) ramSlider.getValue(),
                spinner(portSpinner), motdField.getText().trim(), spinner(maxPlayersSpinner),
                difficultyBox.getValue(), gamemodeBox.getValue(), onlineModeBox.isSelected(), pvpBox.isSelected());
        try {
            ServerInstaller.writeProperties(instance.dir(), cfg);
            ServerProperties p = ServerProperties.load(instance.dir());
            changed.forEach(p::set);
            p.set("view-distance", String.valueOf(spinner(viewDistance)));
            p.set("simulation-distance", String.valueOf(spinner(simulationDistance)));
            p.set("entity-broadcast-range-percentage", String.valueOf(spinner(entityDistance)));
            p.set("spawn-protection", String.valueOf(spinner(spawnProtection)));
            p.set("white-list", String.valueOf(whitelistBox.isSelected()));
            p.set("enforce-whitelist", String.valueOf(whitelistBox.isSelected()));
            p.save();
            instance.setConfig(cfg);
            instance.setOpenRouterPort(routerPortBox.isSelected());
            instance.save();
        } catch (Exception ex) {
            errorLabel.setText("No se pudo guardar: " + ex.getMessage());
            return;
        }
        close();
        onSaved.accept(instance);
    }

    /** Lee lo escrito aunque no se haya pulsado Enter, dentro de los límites del Spinner. */
    private static int spinner(Spinner<Integer> s) {
        var f = (javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory) s.getValueFactory();
        try {
            return Math.max(f.getMin(), Math.min(f.getMax(), Integer.parseInt(s.getEditor().getText().trim())));
        } catch (NumberFormatException e) {
            return s.getValue();
        }
    }
}
