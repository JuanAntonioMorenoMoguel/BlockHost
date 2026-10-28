package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.Backups;
import com.blockhost.core.Backups.Backup;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerProcess;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Pestaña "Copias": lista de copias del mundo, crear, restaurar y borrar, y copias automáticas cada X horas
 * mientras el servidor está en marcha (se guardan solo las últimas).
 */
final class BackupsPane extends VBox {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ServerInstance instance;
    private final ServerProcess process;
    private final Consumer<String> log;

    private final TableView<Backup> table = new TableView<>();
    private final Button createBtn = new Button("Hacer copia ahora");
    private final Button restoreBtn = new Button("Restaurar");
    private final Button deleteBtn = new Button("Borrar");
    private final Button folderBtn = new Button("Abrir carpeta");
    private final CheckBox autoBox = new CheckBox("Copias automáticas mientras el servidor está en marcha");
    private final Spinner<Integer> hours = new Spinner<>(1, 48, 6);
    private final Spinner<Integer> keep = new Spinner<>(1, 50, 8);
    private final Label nextLabel = new Label();
    private final Label status = new Label();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "auto-backup");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> autoTask;
    private LocalDateTime nextAuto;
    private volatile boolean working = false;
    private boolean running = false;

    /** @param log escribe en la consola del servidor */
    BackupsPane(ServerInstance instance, ServerProcess process, HostServices host, Consumer<String> log) {
        super(10);
        this.instance = instance;
        this.process = process;
        this.log = log;
        setPadding(new Insets(12, 0, 0, 0));

        autoBox.setSelected(instance.autoBackupHours() > 0);
        hours.getValueFactory().setValue(instance.autoBackupHours() > 0 ? instance.autoBackupHours() : ServerInstance.DEFAULT_BACKUP_HOURS);
        keep.getValueFactory().setValue(instance.autoBackupKeep());
        hours.setPrefWidth(80);
        keep.setPrefWidth(80);
        hours.setEditable(true);
        keep.setEditable(true);
        autoBox.setOnAction(e -> saveSettings());
        hours.valueProperty().addListener((o, a, b) -> saveSettings());
        keep.valueProperty().addListener((o, a, b) -> saveSettings());
        HBox autoRow = new HBox(8, new Label("Cada"), hours, new Label("horas · guardar las últimas"), keep);
        autoRow.setAlignment(Pos.CENTER_LEFT);
        nextLabel.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        VBox autoBoxRow = new VBox(6, autoBox, autoRow, nextLabel);
        autoBoxRow.setPadding(new Insets(0, 0, 6, 0));

        table.getColumns().add(column("Fecha", 160, b -> b.time().format(WHEN)));
        table.getColumns().add(column("Tipo", 150, Backup::kindLabel));
        table.getColumns().add(column("Tamaño", 110, b -> String.format("%.1f MB", b.size() / 1048576.0)));
        table.getColumns().add(column("Archivo", 280, b -> b.file().getFileName().toString()));
        table.setPlaceholder(new Label("Aún no hay copias. La primera se puede hacer cuando exista el mundo (tras iniciar una vez)."));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
        VBox.setVgrow(table, Priority.ALWAYS);

        createBtn.getStyleClass().add(Styles.ACCENT);
        createBtn.setOnAction(e -> backupNow(Backups.MANUAL));
        restoreBtn.setOnAction(e -> restoreSelected());
        deleteBtn.getStyleClass().add(Styles.DANGER);
        deleteBtn.setOnAction(e -> deleteSelected());
        folderBtn.setOnAction(e -> {
            try {
                host.showDocument(Files.createDirectories(instance.dir().resolve("backups")).toUri().toString());
            } catch (Exception ex) {
                status.setText(ex.getMessage());
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, createBtn, restoreBtn, deleteBtn, spacer, folderBtn);
        status.getStyleClass().add(Styles.TEXT_SMALL);
        status.setWrapText(true);

        Label restoreHelp = new Label("Restaurar cambia el mundo por el de la copia (con el servidor detenido). "
                + "Antes se guarda el mundo actual en otra copia, por si acaso.");
        restoreHelp.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        restoreHelp.setWrapText(true);

        getChildren().addAll(autoBoxRow, table, buttons, restoreHelp, status);
        refresh();
        updateButtons();
    }

    void dispose() { scheduler.shutdownNow(); }

    private static TableColumn<Backup, String> column(String name, double width, Function<Backup, String> f) {
        TableColumn<Backup, String> c = new TableColumn<>(name);
        c.setCellValueFactory(d -> new ReadOnlyStringWrapper(f.apply(d.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    // ---------------- copias automáticas ----------------

    void setServerRunning(boolean running) {
        if (this.running == running) {
            updateButtons();
            return;
        }
        this.running = running;
        reschedule();
        updateButtons();
    }

    private void saveSettings() {
        int h = autoBox.isSelected() ? hours.getValue() : 0;
        if (h == instance.autoBackupHours() && keep.getValue() == instance.autoBackupKeep()) return;
        instance.setAutoBackup(h, keep.getValue());
        try {
            instance.save();
        } catch (Exception ex) {
            status.setText("No se pudo guardar: " + ex.getMessage());
        }
        hours.setDisable(!autoBox.isSelected());
        keep.setDisable(!autoBox.isSelected());
        reschedule();
    }

    private void reschedule() {
        if (autoTask != null) autoTask.cancel(false);
        autoTask = null;
        nextAuto = null;
        int h = instance.autoBackupHours();
        if (running && h > 0) {
            nextAuto = LocalDateTime.now().plusHours(h);
            autoTask = scheduler.scheduleAtFixedRate(this::autoBackup, h, h, TimeUnit.HOURS);
        }
        updateNextLabel();
    }

    private void autoBackup() {
        Platform.runLater(() -> {
            nextAuto = LocalDateTime.now().plusHours(Math.max(1, instance.autoBackupHours()));
            updateNextLabel();
            backupNow(Backups.AUTO);
        });
    }

    private void updateNextLabel() {
        hours.setDisable(!autoBox.isSelected());
        keep.setDisable(!autoBox.isSelected());
        if (!autoBox.isSelected()) nextLabel.setText("Desactivadas.");
        else if (nextAuto == null) nextLabel.setText("La primera se hará " + instance.autoBackupHours()
                + " h después de iniciar el servidor.");
        else nextLabel.setText("Próxima copia automática: " + nextAuto.format(WHEN));
    }

    // ---------------- acciones ----------------

    void backupNow(String kind) {
        if (working) return;
        working = true;
        updateButtons();
        boolean auto = Backups.AUTO.equals(kind);
        status.setText("Haciendo copia...");
        log.accept("[BlockHost] " + (auto ? "Copia automática" : "Creando copia de seguridad") + "...");
        UiTasks.run(() -> {
            String msg;
            try {
                Path zip = Backups.createWhileRunning(instance, process, kind);
                int pruned = auto ? Backups.prune(instance, instance.autoBackupKeep()) : 0;
                msg = "Copia guardada en backups/" + zip.getFileName()
                        + (pruned > 0 ? " (se borraron " + pruned + " automáticas antiguas)" : "");
            } catch (Exception ex) {
                msg = "Error en la copia: " + ex.getMessage();
            }
            String m = msg;
            log.accept("[BlockHost] " + m);
            Platform.runLater(() -> {
                working = false;
                status.setText(m);
                refresh();
            });
        });
    }

    private void restoreSelected() {
        Backup b = table.getSelectionModel().getSelectedItem();
        if (b == null || process.isAlive()) return;
        if (!UiTasks.confirm(window(), "¿Restaurar la copia del " + b.time().format(WHEN) + "?",
                "El mundo actual se sustituirá por el de la copia. Antes se guardará en una copia «Antes de restaurar».",
                "Restaurar", false)) return;
        working = true;
        updateButtons();
        status.setText("Restaurando...");
        UiTasks.run(() -> {
            String msg;
            try {
                Path safety = Backups.restore(instance, b.file());
                msg = "Mundo restaurado." + (safety != null ? " El que había está en backups/" + safety.getFileName() + "." : "");
            } catch (Exception ex) {
                msg = "No se pudo restaurar: " + ex.getMessage();
            }
            String m = msg;
            log.accept("[BlockHost] " + m);
            Platform.runLater(() -> {
                working = false;
                status.setText(m);
                refresh();
            });
        });
    }

    private void deleteSelected() {
        Backup b = table.getSelectionModel().getSelectedItem();
        if (b == null) return;
        if (!UiTasks.confirm(window(), "¿Borrar esta copia?", b.file().getFileName() + " se borrará para siempre.",
                "Borrar", false)) return;
        try {
            Files.deleteIfExists(b.file());
        } catch (Exception ex) {
            status.setText("No se pudo borrar: " + ex.getMessage());
        }
        refresh();
    }

    void refresh() {
        UiTasks.run(() -> {
            try {
                List<Backup> list = Backups.list(instance);
                Platform.runLater(() -> {
                    table.getItems().setAll(list);
                    updateButtons();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> status.setText("No se pudieron leer las copias: " + ex.getMessage()));
            }
        });
    }

    private void updateButtons() {
        Backup sel = table.getSelectionModel().getSelectedItem();
        createBtn.setDisable(working);
        restoreBtn.setDisable(working || sel == null || process.isAlive());
        restoreBtn.setText(process.isAlive() ? "Restaurar (detén el servidor)" : "Restaurar");
        deleteBtn.setDisable(working || sel == null);
        updateNextLabel();
    }

    private Window window() { return getScene() == null ? null : getScene().getWindow(); }
}
