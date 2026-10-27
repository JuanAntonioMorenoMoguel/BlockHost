package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.ServerConfig;
import com.blockhost.core.ServerInstaller;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerRegistry;
import com.blockhost.core.ServerType;
import com.blockhost.core.SystemInfo;
import com.blockhost.core.mods.ModManager;
import com.blockhost.core.mods.ModRepository;
import com.blockhost.core.mods.Modrinth;
import com.blockhost.core.mods.Mrpack;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Crea un servidor nuevo a partir de un modpack: la versión de Minecraft y de Fabric Loader las fija el pack,
 * así que no hay que adivinarlas.
 */
public final class ModpackServerDialog extends Stage {

    private static final String EULA_URL = "https://aka.ms/MinecraftEULA";
    private static final int MODPACK_RAM_MB = 6144;

    private final HostServices host;
    private final Consumer<ServerInstance> onCreated;

    private final Label packLabel = new Label("Elige un modpack para empezar.");
    private final Button fromModrinth = new Button("Buscar modpack en Modrinth...");
    private final Button fromFile = new Button("Elegir archivo...");
    private final TextField nameField = new TextField();
    private final Slider ramSlider = new Slider();
    private final Label ramLabel = new Label();
    private final Spinner<Integer> portSpinner = new Spinner<>(1024, 65535, 25565);
    private final CheckBox eulaBox = new CheckBox("He leído y acepto la EULA de Minecraft");
    private final Label errorLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label progressLabel = new Label();
    private final TextArea logArea = new TextArea();
    private final VBox progressBox = new VBox(8, progressBar, progressLabel, logArea);
    private final Button installBtn = new Button("Crear servidor");
    private final Button cancelBtn = new Button("Cancelar");

    private Mrpack.Pack pack;
    private Path packFile;
    private boolean working = false;

    public ModpackServerDialog(Window owner, HostServices host, Consumer<ServerInstance> onCreated) {
        this.host = host;
        this.onCreated = onCreated;
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setTitle("Nuevo servidor desde modpack");

        Label title = new Label("Servidor desde modpack");
        title.getStyleClass().add(Styles.TITLE_3);
        packLabel.setWrapText(true);
        packLabel.getStyleClass().add(Styles.TEXT_BOLD);
        fromModrinth.setOnAction(e -> new ModSearchDialog(this, host, "modpack", null, this::loadFromRepository).show());
        fromFile.setOnAction(e -> loadFromFile());
        HBox sources = new HBox(8, fromModrinth, fromFile);

        int max = SystemInfo.maxRamMb();
        ramSlider.setMin(1024);
        ramSlider.setMax(Math.max(1024, max));
        ramSlider.setValue(Math.min(Math.max(SystemInfo.recommendedRamMb(), MODPACK_RAM_MB), max));
        ramSlider.setMajorTickUnit(1024);
        ramSlider.setMinorTickCount(1);
        ramSlider.setSnapToTicks(true);
        ramSlider.valueProperty().addListener((o, a, b) -> ramLabel.setText(String.format("%.1f GB", b.doubleValue() / 1024)));
        ramLabel.setText(String.format("%.1f GB", ramSlider.getValue() / 1024));
        portSpinner.setEditable(true);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        g.addRow(0, new Label("Nombre"), nameField);
        g.addRow(1, new Label("RAM"), new HBox(8, ramSlider, ramLabel));
        g.addRow(2, new Label("Puerto"), portSpinner);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        HBox.setHgrow(ramSlider, Priority.ALWAYS);
        Label ramInfo = new Label("Los modpacks suelen necesitar 6–8 GB.");
        ramInfo.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);

        Hyperlink eulaLink = new Hyperlink("Leer la EULA de Minecraft");
        eulaLink.setOnAction(e -> host.showDocument(EULA_URL));
        errorLabel.getStyleClass().add(Styles.DANGER);
        errorLabel.setWrapText(true);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        logArea.setEditable(false);
        logArea.setPrefRowCount(5);
        logArea.getStyleClass().add(Styles.TEXT_SMALL);
        progressBox.setVisible(false);
        progressBox.setManaged(false);

        installBtn.getStyleClass().add(Styles.ACCENT);
        installBtn.setDefaultButton(true);
        installBtn.setDisable(true);
        installBtn.setOnAction(e -> install());
        cancelBtn.setOnAction(e -> close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, cancelBtn, spacer, installBtn);

        VBox center = new VBox(12, title, sources, packLabel, g, ramInfo, new VBox(4, eulaBox, eulaLink),
                errorLabel, progressBox);
        BorderPane root = new BorderPane(center, null, null, buttons, null);
        root.setPadding(new Insets(20));
        BorderPane.setMargin(buttons, new Insets(12, 0, 0, 0));
        setScene(new Scene(root, 600, 600));
        setOnCloseRequest(e -> { if (working) e.consume(); });
    }

    private void loadFromRepository(ModRepository.Project project) {
        setWorking(true, "Descargando " + project.title() + "...");
        UiTasks.run(() -> {
            try {
                ModRepository.Version v = ModRepository.best(
                        Modrinth.INSTANCE.versions(project.id(), ModManager.LOADER, null));
                if (v == null) throw new IllegalStateException(project.title() + " no tiene versiones para Fabric.");
                Path file = Mrpack.download(v, (msg, f) -> Platform.runLater(() -> {
                    progressLabel.setText(msg);
                    progressBar.setProgress(f < 0 ? ProgressBar.INDETERMINATE_PROGRESS : f);
                }));
                Mrpack.Pack p = Mrpack.read(file);
                Platform.runLater(() -> { setWorking(false, null); setPack(p, file); });
            } catch (Exception ex) {
                Platform.runLater(() -> { setWorking(false, null); errorLabel.setText(ex.getMessage()); });
            }
        });
    }

    private void loadFromFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Elige un modpack");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Modpack de Modrinth (*.mrpack)", "*.mrpack"));
        File f = fc.showOpenDialog(this);
        if (f == null) return;
        setWorking(true, "Leyendo el modpack...");
        UiTasks.run(() -> {
            try {
                Mrpack.Pack p = Mrpack.read(f.toPath());
                Platform.runLater(() -> { setWorking(false, null); setPack(p, f.toPath()); });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setWorking(false, null);
                    errorLabel.setText("No se pudo leer el modpack: " + ex.getMessage());
                });
            }
        });
    }

    private void setPack(Mrpack.Pack p, Path file) {
        errorLabel.setText("");
        if (!ModManager.LOADER.equals(p.loader())) {
            pack = null;
            installBtn.setDisable(true);
            errorLabel.setText(p.title() + " usa " + (p.loader() == null ? "Minecraft sin mods" : p.loader())
                    + ". BlockHost de momento solo instala modpacks de Fabric.");
            return;
        }
        pack = p;
        packFile = file;
        packLabel.setText(p.title() + "\nMinecraft " + p.mcVersion() + " · Fabric Loader " + p.loaderVersion()
                + " · " + p.serverFiles().size() + " archivos para el servidor");
        if (nameField.getText().isBlank()) nameField.setText(p.name());
        installBtn.setDisable(false);
    }

    private void install() {
        String name = nameField.getText().trim();
        if (pack == null) { errorLabel.setText("Elige un modpack."); return; }
        if (name.isEmpty()) { errorLabel.setText("Ponle un nombre al servidor."); return; }
        if (ServerRegistry.exists(name)) { errorLabel.setText("Ya tienes un servidor con ese nombre."); return; }
        if (!eulaBox.isSelected()) { errorLabel.setText("Debes aceptar la EULA de Minecraft para continuar."); return; }

        ServerConfig cfg = new ServerConfig(name, ServerType.FABRIC, pack.mcVersion(), (int) ramSlider.getValue(),
                port(), pack.name(), 10, "normal", "survival", true, true);
        Mrpack.Pack p = pack;
        Path file = packFile;
        setWorking(true, "Creando servidor...");
        logArea.clear();
        UiTasks.run(() -> {
            try {
                ServerInstance inst = new ServerInstaller().installFromModpack(cfg, file, true, (msg, f) -> Platform.runLater(() -> {
                    progressLabel.setText(msg);
                    progressBar.setProgress(f < 0 ? ProgressBar.INDETERMINATE_PROGRESS : f);
                    if (!msg.endsWith("MB)")) logArea.appendText(msg + "\n");
                }));
                Platform.runLater(() -> {
                    working = false;
                    close();
                    onCreated.accept(inst);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setWorking(false, null);
                    errorLabel.setText("Error al crear el servidor con " + p.title() + ": " + ex.getMessage());
                });
            }
        });
    }

    /** Lee lo escrito en el Spinner aunque no se haya pulsado Enter. */
    private int port() {
        try {
            return Math.max(1024, Math.min(65535, Integer.parseInt(portSpinner.getEditor().getText().trim())));
        } catch (NumberFormatException e) {
            return portSpinner.getValue();
        }
    }

    private void setWorking(boolean v, String msg) {
        working = v;
        installBtn.setDisable(v || pack == null);
        cancelBtn.setDisable(v);
        fromModrinth.setDisable(v);
        fromFile.setDisable(v);
        if (v) {
            errorLabel.setText("");
            progressBox.setVisible(true);
            progressBox.setManaged(true);
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            progressLabel.setText(msg);
        }
    }
}
