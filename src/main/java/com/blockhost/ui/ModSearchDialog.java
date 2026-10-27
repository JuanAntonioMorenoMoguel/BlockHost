package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.Settings;
import com.blockhost.core.mods.CurseForge;
import com.blockhost.core.mods.ModManager;
import com.blockhost.core.mods.ModRepository;
import com.blockhost.core.mods.ModRepository.Project;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/** Buscador de mods o modpacks de Fabric en Modrinth o CurseForge, con sus iconos. Devuelve el proyecto elegido. */
public final class ModSearchDialog extends Stage {

    private static final String LAST_REPO = "lastModRepository";

    private final String projectType;
    private final String mcVersion;
    private final HostServices host;
    private final Consumer<Project> onPick;

    private final ToggleGroup repoGroup = new ToggleGroup();
    private final TextField query = new TextField();
    private final Button searchBtn = new Button("Buscar");
    private final ListView<Project> results = new ListView<>();
    private final Label status = new Label();
    private final Button pickBtn = new Button("Instalar");
    private final VBox keyBox = new VBox(8);
    private final VBox searchBox = new VBox(10);
    private int searchSeq = 0;

    /**
     * @param projectType "mod" o "modpack"
     * @param mcVersion   solo resultados para esa versión de Minecraft; null = cualquiera
     */
    public ModSearchDialog(Window owner, HostServices host, String projectType, String mcVersion, Consumer<Project> onPick) {
        this.projectType = projectType;
        this.mcVersion = mcVersion;
        this.host = host;
        this.onPick = onPick;
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        boolean mods = projectType.equals("mod");
        setTitle(mods ? "Buscar mods" : "Buscar modpacks");

        HBox repos = new HBox();
        List<ModRepository> all = ModRepository.all();
        String last = Settings.get(LAST_REPO);
        for (int i = 0; i < all.size(); i++) {
            ModRepository r = all.get(i);
            ToggleButton b = new ToggleButton(r.displayName());
            b.setUserData(r);
            b.setToggleGroup(repoGroup);
            b.getStyleClass().add(i == 0 ? Styles.LEFT_PILL : i == all.size() - 1 ? Styles.RIGHT_PILL : Styles.CENTER_PILL);
            if (r.id().equals(last) || (last == null && i == 0)) b.setSelected(true);
            repos.getChildren().add(b);
        }
        if (repoGroup.getSelectedToggle() == null) repoGroup.selectToggle(repoGroup.getToggles().get(0));
        repoGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { repoGroup.selectToggle(a); return; } // siempre uno elegido
            try {
                Settings.set(LAST_REPO, repo().id());
            } catch (Exception ignored) {
                // recordar la elección es un detalle
            }
            showRepo();
        });

        Label info = new Label((mods ? "Mods de Fabric para servidor" : "Modpacks de Fabric")
                + (mcVersion != null ? " · Minecraft " + mcVersion : "")
                + ". Las dependencias se instalan solas y se comprueba la compatibilidad antes de tocar nada.");
        info.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        info.setWrapText(true);
        info.setMinHeight(Region.USE_PREF_SIZE);

        query.setPromptText(mods ? "p. ej. lithium, voice chat, waystones..." : "p. ej. Fabulously Optimized, Cobblemon...");
        query.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) search(); });
        searchBtn.setOnAction(e -> search());
        HBox.setHgrow(query, Priority.ALWAYS);
        HBox searchRow = new HBox(8, query, searchBtn);

        results.setCellFactory(l -> new ProjectCell());
        results.setPlaceholder(new Label("Sin resultados"));
        results.setOnMouseClicked(e -> { if (e.getClickCount() == 2) pick(); });
        results.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> pickBtn.setDisable(b == null));
        VBox.setVgrow(results, Priority.ALWAYS);
        status.getStyleClass().add(Styles.TEXT_MUTED);
        searchBox.getChildren().addAll(searchRow, results, status);
        VBox.setVgrow(searchBox, Priority.ALWAYS);

        buildKeyBox();

        Hyperlink open = new Hyperlink("Ver página del proyecto");
        open.setOnAction(e -> {
            Project p = results.getSelectionModel().getSelectedItem();
            if (p != null && p.pageUrl() != null) host.showDocument(p.pageUrl());
        });
        Button cancel = new Button("Cancelar");
        cancel.setOnAction(e -> close());
        pickBtn.getStyleClass().add(Styles.ACCENT);
        pickBtn.setDisable(true);
        pickBtn.setOnAction(e -> pick());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, open, spacer, cancel, pickBtn);

        VBox center = new VBox(10, repos, info, keyBox, searchBox);
        BorderPane root = new BorderPane(center, null, null, buttons, null);
        root.setPadding(new Insets(16));
        BorderPane.setMargin(buttons, new Insets(12, 0, 0, 0));
        setScene(new Scene(root, 680, 620));
        showRepo();
    }

    private ModRepository repo() { return (ModRepository) repoGroup.getSelectedToggle().getUserData(); }

    /** CurseForge sin clave: en vez de resultados, se explica cómo conseguirla. */
    private void buildKeyBox() {
        Label title = new Label("CurseForge necesita una clave de API");
        title.getStyleClass().add(Styles.TEXT_BOLD);
        Label text = new Label("CurseForge solo deja buscar y descargar a las apps con clave. Es gratis: entra en la "
                + "consola de CurseForge con tu cuenta, crea una clave (API keys) y pégala aquí. Se guarda en este PC.");
        text.setWrapText(true);
        text.getStyleClass().add(Styles.TEXT_SMALL);
        Hyperlink link = new Hyperlink("Abrir la consola de CurseForge");
        link.setOnAction(e -> host.showDocument(CurseForge.CONSOLE_URL));
        PasswordField key = new PasswordField();
        key.setPromptText("Pega aquí la clave");
        HBox.setHgrow(key, Priority.ALWAYS);
        Button save = new Button("Guardar");
        save.getStyleClass().add(Styles.ACCENT);
        save.setOnAction(e -> {
            try {
                Settings.set(CurseForge.SETTINGS_KEY, key.getText());
                showRepo();
            } catch (Exception ex) {
                status.setText("No se pudo guardar la clave: " + ex.getMessage());
            }
        });
        HBox row = new HBox(8, key, save);
        row.setAlignment(Pos.CENTER_LEFT);
        keyBox.getChildren().addAll(title, text, link, row);
        keyBox.setPadding(new Insets(12));
        keyBox.getStyleClass().add(Styles.BG_SUBTLE);
    }

    private void showRepo() {
        boolean ready = repo().available();
        keyBox.setVisible(!ready);
        keyBox.setManaged(!ready);
        searchBox.setVisible(ready);
        searchBox.setManaged(ready);
        results.getItems().clear();
        if (ready) search(); // sin texto: los más populares
    }

    private void search() {
        int seq = ++searchSeq;
        ModRepository repo = repo();
        String q = query.getText();
        status.setText("Buscando en " + repo.displayName() + "...");
        searchBtn.setDisable(true);
        UiTasks.run(() -> {
            try {
                List<Project> found = repo.search(q, projectType, ModManager.LOADER, mcVersion, 30);
                Platform.runLater(() -> {
                    if (seq != searchSeq) return;
                    results.getItems().setAll(found);
                    status.setText(found.isEmpty() ? "No hay resultados compatibles." : found.size() + " resultados.");
                    searchBtn.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (seq != searchSeq) return;
                    status.setText("No se pudo buscar: " + ex.getMessage());
                    searchBtn.setDisable(false);
                });
            }
        });
    }

    private void pick() {
        Project p = results.getSelectionModel().getSelectedItem();
        if (p == null) return;
        close();
        onPick.accept(p);
    }

    private static final class ProjectCell extends ListCell<Project> {
        private final StackPane icon = new StackPane();
        private final Label title = new Label();
        private final Label meta = new Label();
        private final Label desc = new Label();
        private final HBox box;

        ProjectCell() {
            title.getStyleClass().add(Styles.TEXT_BOLD);
            meta.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
            desc.getStyleClass().add(Styles.TEXT_SMALL);
            desc.setWrapText(true);
            desc.setMaxWidth(540);
            VBox text = new VBox(2, title, meta, desc);
            HBox.setHgrow(text, Priority.ALWAYS);
            box = new HBox(12, icon, text);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Project p, boolean empty) {
            super.updateItem(p, empty);
            if (empty || p == null) {
                setGraphic(null);
                return;
            }
            icon.getChildren().setAll(Icons.remote(p.iconUrl(), p.title(), 48));
            title.setText(p.title());
            meta.setText((p.author() != null ? "por " + p.author() + " · " : "") + downloads(p.downloads()) + " descargas"
                    + (p.clientToo() && "mod".equals(p.projectType()) ? " · los jugadores también lo necesitan" : ""));
            desc.setText(p.description());
            setGraphic(box);
        }

        private static String downloads(long n) {
            if (n >= 1_000_000) return String.format("%.1f M", n / 1e6);
            if (n >= 1_000) return String.format("%.0f k", n / 1e3);
            return String.valueOf(n);
        }
    }
}
