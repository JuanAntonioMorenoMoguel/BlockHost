package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.ServerConfig;
import com.blockhost.core.ServerInstaller;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerRegistry;
import com.blockhost.core.SystemInfo;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * Cambia los ajustes de un servidor ya creado (nombre, RAM, puerto y opciones de juego). La versión y el tipo
 * no se cambian aquí: eso es otro servidor.
 */
final class EditServerDialog extends Stage {

    private final ServerInstance instance;
    private final Consumer<ServerInstance> onSaved;

    private final TextField nameField = new TextField();
    private final Slider ramSlider = new Slider();
    private final Label ramLabel = new Label();
    private final Spinner<Integer> portSpinner = new Spinner<>(1024, 65535, 25565);
    private final TextField motdField = new TextField();
    private final Spinner<Integer> maxPlayersSpinner = new Spinner<>(1, 200, 10);
    private final ComboBox<String> difficultyBox = WizardDialog.choice(WizardDialog.labels(
            "peaceful", "Pacífico", "easy", "Fácil", "normal", "Normal", "hard", "Difícil"), "normal");
    private final ComboBox<String> gamemodeBox = WizardDialog.choice(WizardDialog.labels(
            "survival", "Supervivencia", "creative", "Creativo", "adventure", "Aventura", "spectator", "Espectador"),
            "survival");
    private final CheckBox onlineModeBox = new CheckBox("Solo cuentas oficiales de Minecraft (recomendado)");
    private final CheckBox pvpBox = new CheckBox("Permitir PvP entre jugadores");
    private final Label errorLabel = new Label();

    EditServerDialog(Window owner, ServerInstance instance, Consumer<ServerInstance> onSaved) {
        this.instance = instance;
        this.onSaved = onSaved;
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        ServerConfig c = instance.config();
        setTitle("Ajustes de " + c.name());

        Label title = new Label("Ajustes del servidor");
        title.getStyleClass().add(Styles.TITLE_3);
        Label fixed = new Label(c.type().displayName + " " + c.mcVersion()
                + (instance.loaderVersion() != null ? " · " + instance.loaderVersion() : "")
                + "  (para otra versión, crea un servidor nuevo)");
        fixed.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);

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
        portSpinner.setEditable(true);
        portSpinner.getValueFactory().setValue(c.port());
        motdField.setText(c.motd());
        maxPlayersSpinner.setEditable(true);
        maxPlayersSpinner.getValueFactory().setValue(c.maxPlayers());
        difficultyBox.setValue(c.difficulty());
        gamemodeBox.setValue(c.gamemode());
        onlineModeBox.setSelected(c.onlineMode());
        pvpBox.setSelected(c.pvp());

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        g.addRow(0, new Label("Nombre"), nameField);
        g.addRow(1, new Label("RAM"), new HBox(8, ramSlider, ramLabel));
        g.addRow(2, new Label("Puerto"), portSpinner);
        g.addRow(3, new Label("Mensaje (MOTD)"), motdField);
        g.addRow(4, new Label("Jugadores máx."), maxPlayersSpinner);
        g.addRow(5, new Label("Dificultad"), difficultyBox);
        g.addRow(6, new Label("Modo de juego"), gamemodeBox);
        GridPane.setHgrow(nameField, Priority.ALWAYS);

        Label note = new Label("La carpeta del servidor no cambia aunque le cambies el nombre.");
        note.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        errorLabel.getStyleClass().add(Styles.DANGER);
        errorLabel.setWrapText(true);

        Button cancel = new Button("Cancelar");
        cancel.setOnAction(e -> close());
        Button save = new Button("Guardar");
        save.getStyleClass().add(Styles.ACCENT);
        save.setDefaultButton(true);
        save.setOnAction(e -> save());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, cancel, spacer, save);

        VBox center = new VBox(12, title, fixed, g, pvpBox, onlineModeBox, note, errorLabel);
        BorderPane root = new BorderPane(center, null, null, buttons, null);
        root.setPadding(new Insets(20));
        BorderPane.setMargin(buttons, new Insets(12, 0, 0, 0));
        setScene(new Scene(root, 560, 600));
    }

    private void save() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) { errorLabel.setText("Ponle un nombre al servidor."); return; }
        if (ServerRegistry.nameTaken(name, instance)) { errorLabel.setText("Ya tienes otro servidor con ese nombre."); return; }
        ServerConfig old = instance.config();
        ServerConfig cfg = new ServerConfig(name, old.type(), old.mcVersion(), (int) ramSlider.getValue(),
                spinner(portSpinner, 1024, 65535), motdField.getText().trim(), spinner(maxPlayersSpinner, 1, 200),
                difficultyBox.getValue(), gamemodeBox.getValue(), onlineModeBox.isSelected(), pvpBox.isSelected());
        try {
            ServerInstaller.writeProperties(instance.dir(), cfg);
            instance.setConfig(cfg);
            instance.save();
        } catch (Exception ex) {
            errorLabel.setText("No se pudo guardar: " + ex.getMessage());
            return;
        }
        close();
        onSaved.accept(instance);
    }

    private static int spinner(Spinner<Integer> s, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(s.getEditor().getText().trim())));
        } catch (NumberFormatException e) {
            return s.getValue();
        }
    }
}
