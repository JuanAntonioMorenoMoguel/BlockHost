package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.ProgressListener;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerProcess;
import com.blockhost.core.WorldShare;
import com.blockhost.core.WorldShare.CloudFolder;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pestaña "Compartir": enviar el mundo a un amigo (a Google Drive / un archivo, o subiéndolo para tener un enlace)
 * y recibir uno (desde un enlace, un .zip o la carpeta de un mundo de un jugador).
 */
final class WorldSharePane extends VBox {

    private final ServerInstance instance;
    private final ServerProcess process;
    private final HostServices host;
    private final Consumer<String> log;
    private final Runnable onImported;

    private final Button exportBtn = new Button("Exportar a un archivo...");
    private final Button uploadBtn = new Button("Subir y obtener enlace");
    private final TextField linkOut = new TextField();
    private final Button copyBtn = new Button("Copiar");
    private final TextField linkIn = new TextField();
    private final Button downloadBtn = new Button("Descargar e importar");
    private final Button fileBtn = new Button("Importar un .zip...");
    private final Button folderBtn = new Button("Importar una carpeta...");
    private final Label importHelp = new Label();
    private final ProgressBar progress = new ProgressBar();
    private final Label status = new Label();

    private List<CloudFolder> clouds = List.of();
    private boolean working = false;
    private boolean running = false;

    /**
     * @param log        escribe en la consola del servidor
     * @param onImported tras cambiar el mundo (para refrescar la lista de copias)
     */
    WorldSharePane(ServerInstance instance, ServerProcess process, HostServices host, Consumer<String> log,
                   Runnable onImported) {
        super(10);
        this.instance = instance;
        this.process = process;
        this.host = host;
        this.log = log;
        this.onImported = onImported;
        setPadding(new Insets(12, 0, 0, 0));

        // ---- enviar ----
        Label sendTitle = title("Enviar este mundo a un amigo");
        exportBtn.getStyleClass().add(Styles.ACCENT);
        exportBtn.setOnAction(e -> exportToFile());
        uploadBtn.setOnAction(e -> uploadAndLink());
        Label exportHelp = help("«Exportar» guarda el mundo en un .zip. Si tienes Google Drive para escritorio, se "
                + "abre directamente en tu Drive: luego, en drive.google.com, clic derecho en el archivo → Compartir → "
                + "«Cualquier persona con el enlace» y pásale el enlace.\n"
                + "«Subir y obtener enlace» lo sube sin cuenta (hasta 1 GB) y te da un enlace que dura "
                + WorldShare.LINK_HOURS / 24 + " días. Cualquiera con el enlace puede descargarlo.");
        linkOut.setEditable(false);
        linkOut.setPromptText("Aquí aparecerá el enlace");
        HBox.setHgrow(linkOut, Priority.ALWAYS);
        copyBtn.setOnAction(e -> copy(linkOut.getText()));
        HBox linkRow = new HBox(8, linkOut, copyBtn);
        linkRow.setAlignment(Pos.CENTER_LEFT);
        showLink(null);

        // ---- recibir ----
        Label receiveTitle = title("Recibir un mundo");
        linkIn.setPromptText("Pega el enlace (de BlockHost, Google Drive, Dropbox u OneDrive)");
        HBox.setHgrow(linkIn, Priority.ALWAYS);
        linkIn.textProperty().addListener((o, a, b) -> updateButtons());
        linkIn.setOnAction(e -> downloadAndImport());
        downloadBtn.setOnAction(e -> downloadAndImport());
        fileBtn.setOnAction(e -> importFromFile());
        folderBtn.setOnAction(e -> importFromFolder());
        HBox linkInRow = new HBox(8, linkIn, downloadBtn);
        linkInRow.setAlignment(Pos.CENTER_LEFT);
        importHelp.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        importHelp.setWrapText(true);
        Label worldHelp = help("Vale un mundo exportado desde BlockHost o uno tuyo de un jugador: elige su carpeta "
                + "en %APPDATA%\\.minecraft\\saves con «Importar una carpeta». El mundo actual del servidor se guarda "
                + "antes en la pestaña Copias, por si quieres volver a él.");

        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);
        status.getStyleClass().add(Styles.TEXT_SMALL);
        status.setWrapText(true);

        getChildren().addAll(sendTitle, new HBox(8, exportBtn, uploadBtn), linkRow, exportHelp, new Separator(),
                receiveTitle, linkInRow, new HBox(8, fileBtn, folderBtn), importHelp, worldHelp, progress, status);

        UiTasks.run(() -> {
            List<CloudFolder> found = WorldShare.cloudFolders();
            Platform.runLater(() -> {
                clouds = found;
                if (!found.isEmpty()) exportBtn.setText("Guardar en " + found.get(0).name() + "...");
            });
        });
        updateButtons();
    }

    void setServerRunning(boolean running) {
        this.running = running;
        updateButtons();
    }

    private static Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add(Styles.TITLE_4);
        return l;
    }

    private static Label help(String text) {
        Label l = new Label(text);
        l.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        l.setWrapText(true);
        return l;
    }

    // ---------------- enviar ----------------

    private void exportToFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar el mundo");
        fc.setInitialFileName(WorldShare.exportFileName(instance));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Mundo de Minecraft (*.zip)", "*.zip"));
        Path start = clouds.isEmpty() ? Path.of(System.getProperty("user.home"), "Desktop") : clouds.get(0).path();
        if (!Files.isDirectory(start)) start = Path.of(System.getProperty("user.home"));
        fc.setInitialDirectory(start.toFile());
        File f = fc.showSaveDialog(window());
        if (f == null) return;
        Path target = f.toPath().getFileName().toString().toLowerCase().endsWith(".zip")
                ? f.toPath() : f.toPath().resolveSibling(f.getName() + ".zip");
        CloudFolder cloud = clouds.stream().filter(c -> target.toAbsolutePath().startsWith(c.path().toAbsolutePath()))
                .findFirst().orElse(null);

        begin("Exportando el mundo" + (running ? " (se pausa el guardado un momento)" : "") + "...");
        UiTasks.run(() -> {
            try {
                WorldShare.export(instance, process, target);
                String size = String.format("%.1f MB", Files.size(target) / 1048576.0);
                String msg = cloud != null
                        ? "Guardado en tu " + cloud.name() + " (" + size + "). Cuando termine de sincronizarse, "
                        + "compártelo desde la web de " + cloud.name() + " con «Cualquier persona con el enlace»."
                        : "Mundo guardado en " + target + " (" + size + ").";
                log.accept("[BlockHost] Mundo exportado a " + target);
                end(msg);
                Platform.runLater(() -> host.showDocument(target.getParent().toUri().toString()));
            } catch (Exception ex) {
                end("No se pudo exportar: " + ex.getMessage());
            }
        });
    }

    private void uploadAndLink() {
        if (!UiTasks.confirm(window(), "¿Subir el mundo a internet?",
                "Se subirá a litterbox.catbox.moe, un servicio gratuito sin cuenta. Cualquiera con el enlace podrá "
                        + "descargarlo durante " + WorldShare.LINK_HOURS / 24 + " días; después se borra solo.",
                "Subir", false)) return;
        begin("Preparando el mundo" + (running ? " (se pausa el guardado un momento)" : "") + "...");
        showLink(null);
        UiTasks.run(() -> {
            Path zip = null;
            try {
                Path tmpDir = Files.createDirectories(instance.dir().resolve("backups"));
                zip = tmpDir.resolve(".subir-" + WorldShare.exportFileName(instance));
                WorldShare.export(instance, process, zip);
                String url = WorldShare.upload(zip, progressListener());
                log.accept("[BlockHost] Mundo subido: " + url);
                Platform.runLater(() -> {
                    showLink(url);
                    copy(url);
                });
                end("Enlace copiado. Pásaselo a tu amigo: en su BlockHost lo pega en «Recibir un mundo». Caduca en "
                        + WorldShare.LINK_HOURS / 24 + " días.");
            } catch (Exception ex) {
                end("No se pudo subir: " + ex.getMessage());
            } finally {
                if (zip != null) {
                    try {
                        Files.deleteIfExists(zip);
                    } catch (Exception ignored) {
                        // se queda en backups/, no molesta
                    }
                }
            }
        });
    }

    private void showLink(String url) {
        boolean has = url != null;
        linkOut.setText(has ? url : "");
        linkOut.setVisible(has);
        linkOut.setManaged(has);
        copyBtn.setVisible(has);
        copyBtn.setManaged(has);
    }

    private void copy(String text) {
        ClipboardContent c = new ClipboardContent();
        c.putString(text);
        Clipboard.getSystemClipboard().setContent(c);
        copyBtn.setText("¡Copiado!");
        UiTasks.run(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                return;
            }
            Platform.runLater(() -> copyBtn.setText("Copiar"));
        });
    }

    // ---------------- recibir ----------------

    private void downloadAndImport() {
        String link = linkIn.getText().trim();
        if (link.isEmpty() || running || working || !confirmReplace()) return;
        begin("Descargando el mundo...");
        UiTasks.run(() -> {
            Path zip = instance.dir().resolve("backups").resolve(".descarga.zip");
            try {
                WorldShare.download(link, zip, progressListener());
                importNow(zip, "del enlace");
                Platform.runLater(() -> linkIn.clear());
            } catch (Exception ex) {
                end("No se pudo descargar: " + ex.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(zip);
                } catch (Exception ignored) {
                    // se queda en backups/, no molesta
                }
            }
        });
    }

    private void importFromFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Elige el mundo (.zip)");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Mundo de Minecraft (*.zip)", "*.zip"));
        if (!clouds.isEmpty()) fc.setInitialDirectory(clouds.get(0).path().toFile());
        File f = fc.showOpenDialog(window());
        if (f == null || !confirmReplace()) return;
        begin("Importando el mundo...");
        UiTasks.run(() -> importNow(f.toPath(), f.getName()));
    }

    private void importFromFolder() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Elige la carpeta del mundo (la que tiene level.dat)");
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path saves = Path.of(appData, ".minecraft", "saves");
            if (Files.isDirectory(saves)) dc.setInitialDirectory(saves.toFile());
        }
        File f = dc.showDialog(window());
        if (f == null || !confirmReplace()) return;
        begin("Importando el mundo...");
        UiTasks.run(() -> importNow(f.toPath(), "«" + f.getName() + "»"));
    }

    private boolean confirmReplace() {
        return UiTasks.confirm(window(), "¿Cambiar el mundo del servidor?",
                "El mundo actual se sustituirá por el nuevo. Antes se guardará en una copia «Antes de restaurar» "
                        + "(pestaña Copias), por si quieres volver a él.", "Importar", false);
    }

    /** En segundo plano. */
    private void importNow(Path source, String from) {
        try {
            Platform.runLater(() -> status.setText("Importando el mundo..."));
            Path safety = WorldShare.importWorld(instance, source);
            String msg = "Mundo " + from + " importado. Inicia el servidor para jugarlo."
                    + (safety != null ? " El que había está en Copias (" + safety.getFileName() + ")." : "");
            log.accept("[BlockHost] " + msg);
            end(msg);
            Platform.runLater(onImported);
        } catch (Exception ex) {
            end("No se pudo importar: " + ex.getMessage());
        }
    }

    // ---------------- estado ----------------

    private ProgressListener progressListener() {
        return (m, f) -> Platform.runLater(() -> {
            status.setText(m);
            progress.setProgress(f < 0 ? ProgressBar.INDETERMINATE_PROGRESS : f);
        });
    }

    private void begin(String msg) {
        working = true;
        status.setText(msg);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progress.setVisible(true);
        progress.setManaged(true);
        updateButtons();
    }

    /** Desde cualquier hilo. */
    private void end(String msg) {
        Platform.runLater(() -> {
            working = false;
            status.setText(msg);
            progress.setVisible(false);
            progress.setManaged(false);
            updateButtons();
        });
    }

    private void updateButtons() {
        exportBtn.setDisable(working);
        uploadBtn.setDisable(working);
        boolean noImport = working || running;
        downloadBtn.setDisable(noImport || linkIn.getText().isBlank());
        fileBtn.setDisable(noImport);
        folderBtn.setDisable(noImport);
        importHelp.setText(running ? "Detén el servidor para importar un mundo." : "");
        importHelp.setVisible(running);
        importHelp.setManaged(running);
    }

    private Window window() { return getScene() == null ? null : getScene().getWindow(); }
}
