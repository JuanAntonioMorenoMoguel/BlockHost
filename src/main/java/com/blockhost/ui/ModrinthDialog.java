package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.mods.ModManager;
import com.blockhost.core.mods.Modrinth;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/** Buscador de mods o modpacks de Fabric en Modrinth. Devuelve el proyecto elegido. */
public final class ModrinthDialog extends Stage {

    private final String projectType;
    private final String mcVersion;
    private final Consumer<Modrinth.Project> onPick;

    private final TextField query = new TextField();
    private final Button searchBtn = new Button("Buscar");
    private final ListView<Modrinth.Project> results = new ListView<>();
    private final Label status = new Label();
    private final Button pickBtn = new Button("Instalar");
    private int searchSeq = 0;

    /**
     * @param projectType "mod" o "modpack"
     * @param mcVersion   solo resultados para esa versión de Minecraft; null = cualquiera
     */
    public ModrinthDialog(Window owner, HostServices host, String projectType, String mcVersion,
                          Consumer<Modrinth.Project> onPick) {
        this.projectType = projectType;
        this.mcVersion = mcVersion;
        this.onPick = onPick;
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        boolean mods = projectType.equals("mod");
        setTitle(mods ? "Buscar mods en Modrinth" : "Buscar modpacks en Modrinth");

        Label info = new Label((mods ? "Mods de Fabric que funcionan en servidor" : "Modpacks de Fabric")
                + (mcVersion != null ? " para Minecraft " + mcVersion : "")
                + ". Las dependencias se instalan solas y se comprueba la compatibilidad antes de tocar nada.");
        info.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        info.setWrapText(true);

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

        Hyperlink open = new Hyperlink("Ver en Modrinth");
        open.setOnAction(e -> {
            Modrinth.Project p = results.getSelectionModel().getSelectedItem();
            if (p != null) host.showDocument(p.url());
        });
        Button cancel = new Button("Cancelar");
        cancel.setOnAction(e -> close());
        pickBtn.getStyleClass().add(Styles.ACCENT);
        pickBtn.setDisable(true);
        pickBtn.setOnAction(e -> pick());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, open, spacer, cancel, pickBtn);

        VBox center = new VBox(10, info, searchRow, results, status);
        BorderPane root = new BorderPane(center, null, null, buttons, null);
        root.setPadding(new Insets(16));
        BorderPane.setMargin(buttons, new Insets(12, 0, 0, 0));
        setScene(new Scene(root, 640, 560));
        search(); // sin texto: los más populares
    }

    private void search() {
        int seq = ++searchSeq;
        String q = query.getText();
        status.setText("Buscando...");
        searchBtn.setDisable(true);
        UiTasks.run(() -> {
            try {
                List<Modrinth.Project> found = Modrinth.search(q, projectType, ModManager.LOADER, mcVersion, 30);
                Platform.runLater(() -> {
                    if (seq != searchSeq) return;
                    results.getItems().setAll(found);
                    status.setText(found.isEmpty() ? "No hay resultados compatibles." : found.size() + " resultados.");
                    searchBtn.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    status.setText("No se pudo buscar: " + ex.getMessage() + ". Comprueba tu conexión a Internet.");
                    searchBtn.setDisable(false);
                });
            }
        });
    }

    private void pick() {
        Modrinth.Project p = results.getSelectionModel().getSelectedItem();
        if (p == null) return;
        close();
        onPick.accept(p);
    }

    private static final class ProjectCell extends ListCell<Modrinth.Project> {
        private final Label title = new Label();
        private final Label meta = new Label();
        private final Label desc = new Label();
        private final VBox box = new VBox(2, title, meta, desc);

        ProjectCell() {
            title.getStyleClass().add(Styles.TEXT_BOLD);
            meta.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
            desc.getStyleClass().add(Styles.TEXT_SMALL);
            desc.setWrapText(true);
            desc.setMaxWidth(560);
        }

        @Override
        protected void updateItem(Modrinth.Project p, boolean empty) {
            super.updateItem(p, empty);
            if (empty || p == null) {
                setGraphic(null);
                return;
            }
            title.setText(p.title());
            meta.setText((p.author() != null ? "por " + p.author() + " · " : "") + downloads(p.downloads()) + " descargas"
                    + ("required".equals(p.clientSide()) && "mod".equals(p.projectType())
                    ? " · los jugadores también lo necesitan" : ""));
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
