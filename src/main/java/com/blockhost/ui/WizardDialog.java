package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.ServerConfig;
import com.blockhost.core.ServerInstaller;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerRegistry;
import com.blockhost.core.ServerType;
import com.blockhost.core.SystemInfo;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Asistente de creación de servidor en 5 pasos. */
public final class WizardDialog extends Stage {

    private static final String EULA_URL = "https://aka.ms/MinecraftEULA";

    private final HostServices host;
    private final Consumer<ServerInstance> onCreated;

    private final String[] titles = {
            "Nombre y tipo de servidor", "Versión de Minecraft", "Recursos del servidor",
            "Opciones de juego", "Resumen e instalación"
    };
    private final Node[] steps = new Node[titles.length];
    private int current = 0;
    private boolean installing = false;

    private final Label stepLabel = new Label();
    private final Label titleLabel = new Label();
    private final Label errorLabel = new Label();
    private final StackPane stepHolder = new StackPane();
    private final Button backBtn = new Button("Atrás");
    private final Button nextBtn = new Button("Siguiente");
    private final Button cancelBtn = new Button("Cancelar");

    // Paso 1
    private final TextField nameField = new TextField("Mi servidor");
    private final ToggleGroup typeGroup = new ToggleGroup();
    // Paso 2
    private final ListView<String> versionList = new ListView<>();
    private final Label versionStatus = new Label();
    private ServerType versionsLoadedFor;
    // Paso 3
    private final Slider ramSlider = new Slider();
    private final Label ramLabel = new Label();
    private final Spinner<Integer> portSpinner = new Spinner<>(1024, 65535, 25565);
    // Paso 4
    private final TextField motdField = new TextField("Un servidor de Minecraft");
    private final Spinner<Integer> maxPlayersSpinner = new Spinner<>(1, 200, 10);
    private final ComboBox<String> difficultyBox = choice(labels(
            "peaceful", "Pacífico", "easy", "Fácil", "normal", "Normal", "hard", "Difícil"), "normal");
    private final ComboBox<String> gamemodeBox = choice(labels(
            "survival", "Supervivencia", "creative", "Creativo", "adventure", "Aventura", "spectator", "Espectador"),
            "survival");
    private final CheckBox onlineModeBox = new CheckBox("Solo cuentas oficiales de Minecraft (recomendado)");
    private final CheckBox pvpBox = new CheckBox("Permitir PvP entre jugadores");
    // Paso 5
    private final Label summaryLabel = new Label();
    private final CheckBox eulaBox = new CheckBox("He leído y acepto la EULA de Minecraft");
    private final VBox progressBox = new VBox(8);
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label progressLabel = new Label();
    private final TextArea logArea = new TextArea();

    public WizardDialog(Window owner, HostServices host, Consumer<ServerInstance> onCreated) {
        this.host = host;
        this.onCreated = onCreated;
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setTitle("Nuevo servidor");

        steps[0] = buildNameStep();
        steps[1] = buildVersionStep();
        steps[2] = buildResourcesStep();
        steps[3] = buildGameStep();
        steps[4] = buildSummaryStep();

        stepLabel.getStyleClass().add(Styles.TEXT_MUTED);
        titleLabel.getStyleClass().add(Styles.TITLE_3);
        errorLabel.getStyleClass().add(Styles.DANGER);
        errorLabel.setWrapText(true);
        errorLabel.setMinHeight(Region.USE_PREF_SIZE);

        nextBtn.getStyleClass().add(Styles.ACCENT);
        nextBtn.setDefaultButton(true);
        nextBtn.setOnAction(e -> next());
        backBtn.setOnAction(e -> go(current - 1));
        cancelBtn.setOnAction(e -> close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, cancelBtn, spacer, backBtn, nextBtn);
        buttons.setPadding(new Insets(12, 20, 16, 20));

        VBox header = new VBox(4, stepLabel, titleLabel);
        header.setPadding(new Insets(20, 20, 8, 20));

        VBox center = new VBox(10, stepHolder, errorLabel);
        center.setPadding(new Insets(8, 20, 8, 20));
        VBox.setVgrow(stepHolder, Priority.ALWAYS);

        BorderPane root = new BorderPane(center, header, null, buttons, null);
        setScene(new Scene(root, 620, 600));
        setOnCloseRequest(e -> { if (installing) e.consume(); });

        go(0);
    }

    // ---------------- pasos ----------------

    private Node buildNameStep() {
        Label nameLbl = new Label("Nombre del servidor");
        VBox types = new VBox(10);
        for (ServerType t : ServerType.values()) {
            RadioButton rb = new RadioButton(t.displayName);
            rb.setUserData(t);
            rb.setToggleGroup(typeGroup);
            if (t == ServerType.PAPER) rb.setSelected(true);
            Label desc = new Label(t.description);
            desc.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
            desc.setWrapText(true);
            desc.setMinHeight(Region.USE_PREF_SIZE);
            desc.setPadding(new Insets(0, 0, 0, 26));
            types.getChildren().add(new VBox(2, rb, desc));
        }
        return new VBox(10, nameLbl, nameField, new Label(" "), new Label("¿Qué tipo de servidor quieres?"), types);
    }

    private Node buildVersionStep() {
        versionStatus.getStyleClass().add(Styles.TEXT_MUTED);
        VBox.setVgrow(versionList, Priority.ALWAYS);
        return new VBox(10, new Label("Elige la versión. Tus jugadores deben usar la misma."), versionList, versionStatus);
    }

    private Node buildResourcesStep() {
        long totalMb = SystemInfo.totalMemoryMb();
        int rec = SystemInfo.recommendedRamMb();
        int max = SystemInfo.maxRamMb();
        ramSlider.setMin(1024);
        ramSlider.setMax(Math.max(1024, max));
        ramSlider.setValue(Math.min(rec, max));
        ramSlider.setMajorTickUnit(1024);
        ramSlider.setMinorTickCount(1);
        ramSlider.setBlockIncrement(512);
        ramSlider.setSnapToTicks(true);
        ramSlider.setShowTickMarks(true);
        ramSlider.valueProperty().addListener((o, a, b) -> updateRamLabel());
        updateRamLabel();
        ramLabel.getStyleClass().add(Styles.TITLE_4);

        Label info = new Label(String.format("Tu equipo tiene %.1f GB de RAM. Recomendado: %.1f GB.%n"
                + "Con mods suele hacer falta más (4–8 GB).", totalMb / 1024.0, rec / 1024.0));
        info.getStyleClass().add(Styles.TEXT_MUTED);
        info.setWrapText(true);

        portSpinner.setEditable(true);
        Label portInfo = new Label("Déjalo en 25565 salvo que tengas otro servidor en marcha.");
        portInfo.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);

        return new VBox(10, new Label("Memoria RAM para el servidor"), ramLabel, ramSlider, info,
                new Label(" "), new Label("Puerto"), portSpinner, portInfo);
    }

    private void updateRamLabel() {
        ramLabel.setText(String.format("%.1f GB", ramSlider.getValue() / 1024.0));
    }

    private Node buildGameStep() {
        maxPlayersSpinner.setEditable(true);
        onlineModeBox.setSelected(true);
        pvpBox.setSelected(true);
        Label onlineInfo = new Label("Desactívalo solo si algún amigo no tiene Minecraft original. "
                + "Sin esta opción cualquiera puede entrar con cualquier nombre: usa whitelist.");
        onlineInfo.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        onlineInfo.setWrapText(true);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        g.addRow(0, new Label("Mensaje (MOTD)"), motdField);
        g.addRow(1, new Label("Jugadores máx."), maxPlayersSpinner);
        g.addRow(2, new Label("Dificultad"), difficultyBox);
        g.addRow(3, new Label("Modo de juego"), gamemodeBox);
        GridPane.setHgrow(motdField, Priority.ALWAYS);
        return new VBox(14, g, pvpBox, new VBox(4, onlineModeBox, onlineInfo));
    }

    private Node buildSummaryStep() {
        summaryLabel.setWrapText(true);
        Hyperlink eulaLink = new Hyperlink("Leer la EULA de Minecraft");
        eulaLink.setOnAction(e -> host.showDocument(EULA_URL));

        progressBar.setMaxWidth(Double.MAX_VALUE);
        logArea.setEditable(false);
        logArea.setPrefRowCount(6);
        logArea.getStyleClass().add(Styles.TEXT_SMALL);
        progressBox.getChildren().addAll(progressBar, progressLabel, logArea);
        progressBox.setVisible(false);
        progressBox.setManaged(false);

        Label eulaInfo = new Label("Para ejecutar un servidor, Mojang exige aceptar su licencia (EULA).");
        eulaInfo.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        return new VBox(12, summaryLabel, new VBox(4, eulaInfo, eulaBox, eulaLink), progressBox);
    }

    // ---------------- navegación ----------------

    private void go(int index) {
        current = index;
        errorLabel.setText("");
        stepLabel.setText("Paso " + (index + 1) + " de " + steps.length);
        titleLabel.setText(titles[index]);
        stepHolder.getChildren().setAll(steps[index]);
        backBtn.setDisable(index == 0);
        nextBtn.setText(index == steps.length - 1 ? "Instalar" : "Siguiente");

        if (index == 1) loadVersionsIfNeeded();
        if (index == 4) summaryLabel.setText(summary());
    }

    private void next() {
        if (current == 1 && versionList.getItems().isEmpty()) { // falló la carga: reintentar
            loadVersionsIfNeeded();
            return;
        }
        String error = validate(current);
        if (error != null) {
            errorLabel.setText(error);
            return;
        }
        if (current < steps.length - 1) go(current + 1);
        else install();
    }

    private String validate(int step) {
        switch (step) {
            case 0 -> {
                String n = nameField.getText().trim();
                if (n.isEmpty()) return "Ponle un nombre al servidor.";
                if (ServerRegistry.nameTaken(n, null)) return "Ya tienes un servidor con ese nombre.";
            }
            case 1 -> {
                if (versionList.getSelectionModel().getSelectedItem() == null) return "Elige una versión.";
            }
            case 4 -> {
                if (!eulaBox.isSelected()) return "Debes aceptar la EULA de Minecraft para continuar.";
            }
            default -> { }
        }
        return null;
    }

    private ServerType selectedType() {
        return (ServerType) typeGroup.getSelectedToggle().getUserData();
    }

    private void loadVersionsIfNeeded() {
        ServerType type = selectedType();
        if (type == versionsLoadedFor && !versionList.getItems().isEmpty()) return;
        versionList.getItems().clear();
        versionStatus.setText("Cargando versiones de " + type.displayName + "...");
        nextBtn.setDisable(true);
        runInBackground(() -> {
            try {
                List<String> versions = type.provider().listVersions();
                Platform.runLater(() -> {
                    versionsLoadedFor = type;
                    versionList.getItems().setAll(versions);
                    if (!versions.isEmpty()) versionList.getSelectionModel().select(0);
                    versionStatus.setText(versions.size() + " versiones disponibles. La primera es la más reciente.");
                    nextBtn.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    versionStatus.setText("Pulsa «Siguiente» para reintentar.");
                    errorLabel.setText("No se pudieron cargar las versiones: " + ex.getMessage()
                            + "\nComprueba tu conexión a Internet.");
                    nextBtn.setDisable(false);
                    versionsLoadedFor = null;
                });
            }
        });
    }

    private ServerConfig buildConfig() {
        return new ServerConfig(
                nameField.getText().trim(),
                selectedType(),
                versionList.getSelectionModel().getSelectedItem(),
                (int) ramSlider.getValue(),
                spinnerValue(portSpinner, 1024, 65535),
                motdField.getText().trim(),
                spinnerValue(maxPlayersSpinner, 1, 200),
                difficultyBox.getValue(),
                gamemodeBox.getValue(),
                onlineModeBox.isSelected(),
                pvpBox.isSelected());
    }

    private String summary() {
        ServerConfig c = buildConfig();
        return String.format("""
                Nombre: %s
                Tipo: %s · Minecraft %s
                RAM: %.1f GB · Puerto: %d
                Jugadores máx.: %d · %s · %s

                Se descargará el servidor y, si hace falta, el Java adecuado para esta versión.""",
                c.name(), c.type().displayName, c.mcVersion(), c.ramMb() / 1024.0, c.port(),
                c.maxPlayers(), difficultyBox.getConverter().toString(c.difficulty()),
                gamemodeBox.getConverter().toString(c.gamemode()));
    }

    private void install() {
        ServerConfig cfg = buildConfig();
        setInstalling(true);
        logArea.clear();
        progressBox.setVisible(true);
        progressBox.setManaged(true);

        runInBackground(() -> {
            try {
                ServerInstance inst = new ServerInstaller().install(cfg, true, (msg, f) -> Platform.runLater(() -> {
                    progressLabel.setText(msg);
                    progressBar.setProgress(f < 0 ? ProgressBar.INDETERMINATE_PROGRESS : f);
                    if (!msg.endsWith("MB)")) logArea.appendText(msg + "\n");
                }));
                Platform.runLater(() -> {
                    installing = false;
                    close();
                    onCreated.accept(inst);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setInstalling(false);
                    progressBar.setProgress(0);
                    progressLabel.setText("");
                    errorLabel.setText("Error durante la instalación: " + ex.getMessage());
                    nextBtn.setText("Reintentar");
                });
            }
        });
    }

    private void setInstalling(boolean v) {
        installing = v;
        nextBtn.setDisable(v);
        backBtn.setDisable(v);
        cancelBtn.setDisable(v);
        eulaBox.setDisable(v);
    }

    // ---------------- utilidades ----------------

    /** Lee lo escrito en un Spinner editable aunque no se haya pulsado Enter. */
    private static int spinnerValue(Spinner<Integer> s, int min, int max) {
        try {
            int v = Integer.parseInt(s.getEditor().getText().trim());
            v = Math.max(min, Math.min(max, v));
            s.getValueFactory().setValue(v);
            return v;
        } catch (NumberFormatException e) {
            s.getEditor().setText(String.valueOf(s.getValue()));
            return s.getValue();
        }
    }

    private static void runInBackground(Runnable r) {
        Thread t = new Thread(r, "wizard-worker");
        t.setDaemon(true);
        t.start();
    }

    static Map<String, String> labels(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** ComboBox cuyo valor es la clave de server.properties y muestra el texto en español. */
    static ComboBox<String> choice(Map<String, String> labels, String def) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().setAll(labels.keySet());
        box.setConverter(new StringConverter<>() {
            @Override public String toString(String key) { return key == null ? "" : labels.getOrDefault(key, key); }
            @Override public String fromString(String text) {
                return labels.entrySet().stream().filter(e -> e.getValue().equals(text))
                        .map(Map.Entry::getKey).findFirst().orElse(text);
            }
        });
        box.setValue(def);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }
}
