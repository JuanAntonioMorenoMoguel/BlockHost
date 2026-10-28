package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.ProgressListener;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerType;
import com.blockhost.core.mods.CompatibilityChecker;
import com.blockhost.core.mods.CompatibilityChecker.Issue;
import com.blockhost.core.mods.InstallPlan;
import com.blockhost.core.mods.InstalledMod;
import com.blockhost.core.mods.ModManager;
import com.blockhost.core.mods.ModMetadata;
import com.blockhost.core.mods.ModRepository;
import com.blockhost.core.mods.Modrinth;
import com.blockhost.core.mods.Mrpack;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Pestaña "Mods" (Fabric, Forge, NeoForge) o "Plugins" (Paper) de un servidor: lista, compatibilidad,
 * actualizaciones e instalación desde Modrinth, de .jar propios y de modpacks.
 */
final class ModsPane extends VBox {

    private final ServerInstance instance;
    private final ModManager mods;
    private final HostServices host;
    private final ServerType type;
    private final String noun;    // "mod" o "plugin"
    private final String plural;  // "mods" o "plugins"

    private final Label title = new Label();
    private final Label packLabel = new Label();
    private final Label lockedLabel = new Label();
    private final MenuButton addMenu = new MenuButton("+ Añadir");
    private final Button checkBtn = new Button("Comprobar");
    private final Button updatesBtn = new Button("Buscar actualizaciones");
    private final TableView<InstalledMod> table = new TableView<>();
    private final Button toggleBtn = new Button("Desactivar");
    private final Button removeBtn = new Button("Quitar");
    private final Button pageBtn = new Button("Ver página");
    private final Label issuesTitle = new Label("Compatibilidad");
    private final ListView<Issue> issuesList = new ListView<>();
    private final Button fixBtn = new Button("Instalar lo que falta desde Modrinth");
    private final ProgressBar progress = new ProgressBar();
    private final Label progressLabel = new Label();
    private final HBox progressRow = new HBox(8, progress, progressLabel);

    private boolean serverRunning = false;
    private boolean busy = false;
    private List<String> missing = List.of();
    private Map<String, byte[]> iconBytes = Map.of();

    ModsPane(ServerInstance instance, HostServices host) {
        super(10);
        this.instance = instance;
        this.mods = new ModManager(instance);
        this.host = host;
        this.type = instance.config().type();
        this.noun = type.addonNoun();
        this.plural = noun + "s";
        title.setText(cap(plural));
        lockedLabel.setText("Detén el servidor para añadir, quitar, activar o actualizar " + plural + ".");

        title.getStyleClass().add(Styles.TITLE_4);
        packLabel.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        lockedLabel.getStyleClass().addAll(Styles.WARNING, Styles.TEXT_SMALL);

        String mc = instance.config().mcVersion();
        MenuItem searchMod = new MenuItem("Buscar " + plural + " en Modrinth...");
        searchMod.setOnAction(e -> new ModSearchDialog(window(), host, type.addonProjectType(), mods.loaders(),
                type.displayName, mc, p -> install(Modrinth.INSTANCE, List.of(p.id()))).show());
        MenuItem localJar = new MenuItem("Añadir .jar desde tu PC...");
        localJar.setOnAction(e -> addLocalJars());
        addMenu.getItems().addAll(searchMod, localJar);
        if (type.supportsMods()) {
            MenuItem packModrinth = new MenuItem("Buscar modpacks en Modrinth...");
            packModrinth.setOnAction(e -> new ModSearchDialog(window(), host, "modpack", mods.loaders(), type.displayName,
                    mc, this::modpackFromRepository).show());
            MenuItem packFile = new MenuItem("Instalar modpack desde archivo .mrpack...");
            packFile.setOnAction(e -> modpackFromFile());
            addMenu.getItems().addAll(new SeparatorMenuItem(), packModrinth, packFile);
        }
        addMenu.getStyleClass().add(Styles.ACCENT);
        checkBtn.setOnAction(e -> refresh());
        updatesBtn.setOnAction(e -> checkUpdates());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, new VBox(2, title, packLabel), spacer, checkBtn, updatesBtn, addMenu);
        header.setAlignment(Pos.CENTER_LEFT);

        buildTable();
        toggleBtn.setOnAction(e -> toggleSelected());
        removeBtn.setOnAction(e -> removeSelected());
        pageBtn.setOnAction(e -> {
            InstalledMod m = table.getSelectionModel().getSelectedItem();
            if (m != null && m.source() != null && m.source().pageUrl() != null) {
                host.showDocument(m.source().pageUrl());
            }
        });
        HBox rowButtons = new HBox(8, toggleBtn, removeBtn, pageBtn);

        issuesTitle.getStyleClass().add(Styles.TEXT_BOLD);
        issuesList.setCellFactory(l -> new IssueCell());
        issuesList.setPrefHeight(160);
        issuesList.setMinHeight(130);
        issuesList.setPlaceholder(new Label("✔ Todo compatible"));
        fixBtn.setOnAction(e -> install(Modrinth.INSTANCE, missing));

        progress.setPrefWidth(160);
        progressLabel.getStyleClass().add(Styles.TEXT_SMALL);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        progressRow.setVisible(false);
        progressRow.setManaged(false);

        VBox.setVgrow(table, Priority.ALWAYS);
        table.setMinHeight(150);
        getChildren().addAll(header, lockedLabel, table, rowButtons, issuesTitle, issuesList, fixBtn, progressRow);
        updateControls();
        refresh();
    }

    void setServerRunning(boolean running) {
        serverRunning = running;
        updateControls();
    }

    // ---------------- construcción ----------------

    private void buildTable() {
        TableColumn<InstalledMod, InstalledMod> iconCol = new TableColumn<>("");
        iconCol.setCellValueFactory(d -> new javafx.beans.property.ReadOnlyObjectWrapper<>(d.getValue()));
        iconCol.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(InstalledMod m, boolean empty) {
                super.updateItem(m, empty);
                setGraphic(empty || m == null ? null : icon(m));
            }
        });
        iconCol.setPrefWidth(44);
        iconCol.setSortable(false);
        table.getColumns().add(iconCol);
        table.getColumns().add(column("Activo", 60, m -> m.enabled() ? "✔" : "—"));
        table.getColumns().add(column(cap(noun), 210, InstalledMod::displayName));
        table.getColumns().add(column("Versión", 140, InstalledMod::version));
        table.getColumns().add(column("Origen", 140, m -> m.source() == null ? "Añadido a mano" : m.source().label()));
        table.getColumns().add(column("Archivo", 200, InstalledMod::fileName));
        table.setPlaceholder(new Label("Sin " + plural + ". Pulsa «+ Añadir» para buscarlos en Modrinth"
                + (type.supportsMods() ? " o instalar un modpack." : " o añadir un .jar.")));
        table.setFixedCellSize(40);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateControls());
    }

    private static TableColumn<InstalledMod, String> column(String name, double width, Function<InstalledMod, String> f) {
        TableColumn<InstalledMod, String> c = new TableColumn<>(name);
        c.setCellValueFactory(d -> new ReadOnlyStringWrapper(f.apply(d.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    // ---------------- acciones ----------------

    private void refresh() {
        UiTasks.run(() -> {
            try {
                List<InstalledMod> list = mods.list();
                List<Issue> issues = CompatibilityChecker.check(list, mods.environment());
                Map<String, Object> pack = mods.modpackInfo();
                Map<String, byte[]> icons = new HashMap<>();
                for (InstalledMod m : list) {
                    if (m.meta() != null) icons.put(m.jarName(), ModMetadata.readEntry(m.file(), m.meta().icon()));
                }
                Platform.runLater(() -> {
                    iconBytes = icons;
                    show(list, issues, pack);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> progressLabel.setText("No se pudieron leer los " + plural + ": " + ex.getMessage()));
            }
        });
    }

    private void show(List<InstalledMod> list, List<Issue> issues, Map<String, Object> pack) {
        InstalledMod selected = table.getSelectionModel().getSelectedItem();
        table.getItems().setAll(list);
        if (selected != null) {
            list.stream().filter(m -> m.jarName().equals(selected.jarName())).findFirst()
                    .ifPresent(m -> table.getSelectionModel().select(m));
        }
        long active = list.stream().filter(InstalledMod::enabled).count();
        title.setText(cap(plural) + " (" + active + " activos" + (active < list.size() ? ", " + (list.size() - active) + " desactivados" : "") + ")");
        var env = mods.environment();
        String base = "Minecraft " + env.mcVersion() + " · " + switch (type) {
            case FABRIC -> "Fabric Loader " + (env.loaderVersion() != null ? env.loaderVersion() : "(se sabrá tras el primer arranque)");
            case PAPER -> "Paper";
            default -> type.displayName + (env.loaderVersion() != null ? " " + env.loaderVersion() : "");
        } + " · carpeta «" + type.addonFolder() + "»";
        packLabel.setText(pack != null ? "Modpack: " + pack.get("name") + " " + pack.getOrDefault("version", "") + " · " + base : base);

        issuesList.getItems().setAll(issues);
        long errors = issues.stream().filter(i -> i.severity() == CompatibilityChecker.Severity.ERROR).count();
        issuesTitle.setText(issues.isEmpty() ? "Compatibilidad"
                : "Compatibilidad: " + errors + " error(es), " + (issues.size() - errors) + " aviso(s)"
                + (errors > 0 ? " — el servidor probablemente no arrancará" : ""));
        missing = ModManager.missingDependencies(issues);
        fixBtn.setText(missing.size() == 1 ? "Instalar «" + missing.get(0) + "» desde Modrinth"
                : "Instalar lo que falta desde Modrinth (" + missing.size() + ")");
        updateControls();
    }

    private Node icon(InstalledMod m) {
        return Icons.fromBytes(iconBytes.get(m.jarName()), m.displayName(), 28);
    }

    private void install(ModRepository repo, List<String> projectIds) {
        if (projectIds.isEmpty()) return;
        startTask("Preparando la instalación...");
        UiTasks.run(() -> {
            try {
                InstallPlan plan = mods.plan(repo, projectIds, progressListener());
                if (plan.items().isEmpty()) {
                    finish(() -> UiTasks.error(window(), "No se puede instalar",
                            String.join("\n", plan.problems().isEmpty() ? List.of("No hay nada que instalar.") : plan.problems())));
                    return;
                }
                ModManager.Staged staged = mods.stage(plan, progressListener());
                finish(() -> confirmAndCommit(staged, plan));
            } catch (Exception ex) {
                finish(() -> UiTasks.error(window(), "Error al instalar", ex.getMessage()));
            }
        });
    }

    private void addLocalJars() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Elige uno o varios " + plural + " (.jar)");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(cap(plural) + " de Minecraft (*.jar)", "*.jar"));
        List<File> files = fc.showOpenMultipleDialog(window());
        if (files == null || files.isEmpty()) return;
        startTask("Comprobando compatibilidad...");
        UiTasks.run(() -> {
            try {
                ModManager.Staged staged = mods.stageLocal(files.stream().map(File::toPath).toList());
                finish(() -> {
                    if (staged.introduced().isEmpty()) commit(staged);
                    else confirmAndCommit(staged, null);
                });
            } catch (Exception ex) {
                finish(() -> UiTasks.error(window(), "No se pudo añadir", ex.getMessage()));
            }
        });
    }

    private void confirmAndCommit(ModManager.Staged staged, InstallPlan plan) {
        StringBuilder b = new StringBuilder();
        if (plan != null) {
            b.append("Se instalará:\n");
            for (InstallPlan.Item it : plan.items()) b.append("  • ").append(it).append('\n');
            if (!plan.problems().isEmpty()) {
                b.append("\nNo se ha podido incluir:\n");
                for (String p : plan.problems()) b.append("  • ").append(p).append('\n');
            }
        } else {
            b.append("Se añadirá:\n");
            for (ModManager.StagedFile f : staged.files()) b.append("  • ").append(f.file().getFileName()).append('\n');
        }
        for (String n : staged.notes()) b.append("\nℹ ").append(n);
        if (!staged.introduced().isEmpty()) {
            b.append("\n\nProblemas de compatibilidad que aparecerían:\n");
            for (Issue i : staged.introduced()) b.append("  ").append(i).append('\n');
        }
        int n = staged.files().size();
        String header = staged.breaksSomething()
                ? "Con esto el servidor probablemente no arrancará"
                : "¿Instalar " + n + " " + (n == 1 ? noun : plural) + "?";
        if (UiTasks.confirm(window(), header, b.toString().strip(), "Instalar", staged.breaksSomething())) commit(staged);
        else mods.discard(staged);
    }

    private void commit(ModManager.Staged staged) {
        try {
            mods.commit(staged);
            progressLabel.setText(type.supportsMods()
                    ? "Instalado. Recuerda: los jugadores necesitan los mods que no sean solo de servidor."
                    : "Instalado. Los plugins se cargan al iniciar el servidor.");
            progressRow.setVisible(true);
            progressRow.setManaged(true);
            progress.setProgress(1);
        } catch (Exception ex) {
            mods.discard(staged);
            UiTasks.error(window(), "No se pudo instalar", ex.getMessage());
        }
        refresh();
    }

    private void toggleSelected() {
        InstalledMod m = table.getSelectionModel().getSelectedItem();
        if (m == null) return;
        try {
            mods.setEnabled(m, !m.enabled());
        } catch (Exception ex) {
            UiTasks.error(window(), "No se pudo cambiar", ex.getMessage());
        }
        refresh();
    }

    private void removeSelected() {
        InstalledMod m = table.getSelectionModel().getSelectedItem();
        if (m == null) return;
        if (!UiTasks.confirm(window(), "¿Quitar " + m.displayName() + "?",
                "Se borrará " + m.fileName() + " de la carpeta " + type.addonFolder() + ". Su configuración se conserva.",
                "Quitar", false)) return;
        try {
            mods.remove(m);
        } catch (Exception ex) {
            UiTasks.error(window(), "No se pudo quitar", ex.getMessage());
        }
        refresh();
    }

    // ---------------- actualizaciones ----------------

    private void checkUpdates() {
        startTask("Buscando actualizaciones en Modrinth...");
        UiTasks.run(() -> {
            try {
                List<ModManager.Update> updates = mods.checkUpdates(progressListener());
                finish(() -> {
                    if (updates.isEmpty()) {
                        UiTasks.info(window(), "Todo al día", "No hay versiones más nuevas de tus " + plural
                                + " para Minecraft " + instance.config().mcVersion() + ".\n"
                                + "(Solo se pueden actualizar los que están en Modrinth.)");
                    } else {
                        chooseUpdates(updates);
                    }
                });
            } catch (Exception ex) {
                finish(() -> UiTasks.error(window(), "No se pudo buscar actualizaciones", ex.getMessage()));
            }
        });
    }

    /** Lista con casillas (todas marcadas) para elegir qué actualizar. */
    private void chooseUpdates(List<ModManager.Update> updates) {
        VBox boxes = new VBox(6);
        List<CheckBox> checks = new ArrayList<>();
        for (ModManager.Update u : updates) {
            CheckBox c = new CheckBox(u.toString());
            c.setSelected(true);
            c.setUserData(u);
            checks.add(c);
            boxes.getChildren().add(c);
        }
        ScrollPane scroll = new ScrollPane(boxes);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(320, 28 * updates.size()));
        Label note = new Label("Se descargan, se comprueba la compatibilidad y te lo enseño antes de cambiar nada.");
        note.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        note.setWrapText(true);

        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.initOwner(window());
        a.setHeaderText(updates.size() == 1 ? "Hay 1 actualización" : "Hay " + updates.size() + " actualizaciones");
        a.getDialogPane().setContent(new VBox(10, scroll, note));
        a.getDialogPane().setPrefWidth(560);
        ButtonType ok = new ButtonType("Actualizar", ButtonBar.ButtonData.OK_DONE);
        a.getButtonTypes().setAll(new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE), ok);
        if (a.showAndWait().orElse(null) != ok) return;
        List<ModManager.Update> chosen = checks.stream().filter(CheckBox::isSelected)
                .map(c -> (ModManager.Update) c.getUserData()).toList();
        if (chosen.isEmpty()) return;

        startTask("Descargando actualizaciones...");
        UiTasks.run(() -> {
            try {
                ModManager.Staged staged = mods.stageUpdates(chosen, progressListener());
                finish(() -> {
                    if (staged.introduced().isEmpty()) {
                        commit(staged);
                        return;
                    }
                    StringBuilder b = new StringBuilder("Se actualizará:\n");
                    chosen.forEach(u -> b.append("  • ").append(u).append('\n'));
                    b.append("\nProblemas de compatibilidad que aparecerían:\n");
                    staged.introduced().forEach(i -> b.append("  ").append(i).append('\n'));
                    if (UiTasks.confirm(window(), staged.breaksSomething() ? "Con esto el servidor probablemente no arrancará"
                            : "La actualización trae avisos", b.toString().strip(), "Actualizar", staged.breaksSomething())) {
                        commit(staged);
                    } else {
                        mods.discard(staged);
                    }
                });
            } catch (Exception ex) {
                finish(() -> UiTasks.error(window(), "No se pudo actualizar", ex.getMessage()));
            }
        });
    }

    // ---------------- modpacks ----------------

    private void modpackFromRepository(ModRepository.Project project) {
        startTask("Buscando versión de " + project.title() + " para Minecraft " + instance.config().mcVersion() + "...");
        UiTasks.run(() -> {
            try {
                ModRepository repo = Modrinth.INSTANCE;
                ModRepository.Version v = ModRepository.best(
                        repo.versions(project.id(), mods.loaders(), instance.config().mcVersion()));
                if (v == null) {
                    finish(() -> UiTasks.error(window(), "Modpack no compatible", project.title()
                            + " no tiene versión de " + type.displayName + " para Minecraft " + instance.config().mcVersion()
                            + ". Puedes crear un servidor nuevo desde el modpack (botón «+ Desde modpack»)."));
                    return;
                }
                Path file = Mrpack.download(v, progressListener());
                Mrpack.Pack pack = Mrpack.read(file);
                finish(() -> confirmModpack(pack, file));
            } catch (Exception ex) {
                finish(() -> UiTasks.error(window(), "Error con el modpack", ex.getMessage()));
            }
        });
    }

    private void modpackFromFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Elige un modpack");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Modpack de Modrinth (*.mrpack)", "*.mrpack"));
        File f = fc.showOpenDialog(window());
        if (f == null) return;
        startTask("Leyendo el modpack...");
        UiTasks.run(() -> {
            try {
                Mrpack.Pack pack = Mrpack.read(f.toPath());
                finish(() -> confirmModpack(pack, f.toPath()));
            } catch (Exception ex) {
                finish(() -> UiTasks.error(window(), "No se pudo leer el modpack", ex.getMessage()));
            }
        });
    }

    private void confirmModpack(Mrpack.Pack pack, Path file) {
        List<String> blockers = mods.modpackBlockers(pack);
        if (!blockers.isEmpty()) {
            UiTasks.error(window(), "No se puede instalar " + pack.title(), String.join("\n", blockers));
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Minecraft " + pack.mcVersion() + " · " + (type == ServerType.FABRIC ? "Fabric Loader" : type.displayName)
                + " " + pack.loaderVersion());
        lines.add(pack.serverFiles().size() + " archivos para el servidor ("
                + (pack.files().size() - pack.serverFiles().size()) + " son solo para los jugadores y se omiten).");
        if (!table.getItems().isEmpty()) {
            lines.add("");
            lines.add("Tus " + table.getItems().size() + " mods actuales se moverán a una carpeta «mods-antes-de-...» "
                    + "para que no choquen con los del modpack.");
        }
        lines.add("");
        lines.add("Los jugadores necesitan instalar el mismo modpack en su Minecraft.");
        if (!UiTasks.confirm(window(), "¿Instalar " + pack.title() + "?", String.join("\n", lines), "Instalar", false)) return;

        startTask("Instalando " + pack.title() + "...");
        UiTasks.run(() -> {
            try {
                List<Issue> issues = mods.installModpack(pack, file, progressListener());
                finish(() -> {
                    if (CompatibilityChecker.hasErrors(issues)) {
                        StringBuilder b = new StringBuilder("Hay problemas que pueden impedir el arranque:\n\n");
                        issues.stream().filter(i -> i.severity() == CompatibilityChecker.Severity.ERROR)
                                .forEach(i -> b.append(i).append('\n'));
                        UiTasks.info(window(), "Modpack instalado con avisos", b.toString().strip());
                    }
                    refresh();
                });
            } catch (Exception ex) {
                finish(() -> {
                    UiTasks.error(window(), "Error al instalar el modpack", ex.getMessage());
                    refresh();
                });
            }
        });
    }

    // ---------------- estado de la UI ----------------

    private void startTask(String msg) {
        busy = true;
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressLabel.setText(msg);
        progressRow.setVisible(true);
        progressRow.setManaged(true);
        updateControls();
    }

    /** Termina la tarea en el hilo de JavaFX y ejecuta then. */
    private void finish(Runnable then) {
        Platform.runLater(() -> {
            busy = false;
            progressRow.setVisible(false);
            progressRow.setManaged(false);
            updateControls();
            then.run();
        });
    }

    private ProgressListener progressListener() {
        return (msg, f) -> Platform.runLater(() -> {
            progressLabel.setText(msg);
            progress.setProgress(f < 0 ? ProgressBar.INDETERMINATE_PROGRESS : f);
        });
    }

    private void updateControls() {
        boolean locked = serverRunning || busy;
        lockedLabel.setVisible(serverRunning);
        lockedLabel.setManaged(serverRunning);
        addMenu.setDisable(locked);
        updatesBtn.setDisable(locked || table.getItems().isEmpty());
        InstalledMod sel = table.getSelectionModel().getSelectedItem();
        toggleBtn.setDisable(locked || sel == null);
        toggleBtn.setText(sel != null && !sel.enabled() ? "Activar" : "Desactivar");
        removeBtn.setDisable(locked || sel == null);
        pageBtn.setDisable(sel == null || sel.source() == null || sel.source().pageUrl() == null);
        fixBtn.setVisible(!missing.isEmpty());
        fixBtn.setManaged(!missing.isEmpty());
        fixBtn.setDisable(locked);
        checkBtn.setDisable(busy);
    }

    private Window window() { return getScene() == null ? null : getScene().getWindow(); }

    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static final class IssueCell extends ListCell<Issue> {
        @Override
        protected void updateItem(Issue i, boolean empty) {
            super.updateItem(i, empty);
            getStyleClass().removeAll(Styles.DANGER, Styles.WARNING);
            if (empty || i == null) {
                setText(null);
                return;
            }
            setText(i.toString());
            setWrapText(true);
            setPrefWidth(0);
            getStyleClass().add(i.severity() == CompatibilityChecker.Severity.ERROR ? Styles.DANGER : Styles.WARNING);
        }
    }
}
