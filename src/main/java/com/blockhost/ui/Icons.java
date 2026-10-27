package com.blockhost.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Iconos de mods: de internet (búsquedas) o de dentro del .jar. Si no hay o no carga, una inicial. */
final class Icons {

    private static final int CACHE = 300;
    private static final Map<String, Image> REMOTE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> e) { return size() > CACHE; }
    };

    private Icons() { }

    /** Se descarga en segundo plano; mientras tanto (o si falla, p. ej. formato WebP) se ve la inicial. */
    static Node remote(String url, String title, double size) {
        if (url == null || url.isBlank()) return placeholder(title, size);
        Image img = REMOTE.computeIfAbsent(url + "@" + size, k -> new Image(url, size, size, true, true, true));
        return withPlaceholder(img, title, size);
    }

    static Node fromBytes(byte[] bytes, String title, double size) {
        if (bytes == null) return placeholder(title, size);
        return withPlaceholder(new Image(new ByteArrayInputStream(bytes), size, size, true, true), title, size);
    }

    private static Node withPlaceholder(Image img, String title, double size) {
        if (img.isError()) return placeholder(title, size);
        ImageView view = new ImageView(img);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        Node ph = placeholder(title, size);
        StackPane p = new StackPane(ph, view);
        p.setMinSize(size, size);
        p.setMaxSize(size, size);
        // La inicial solo se ve hasta que el icono carga (si tiene transparencia, no debe asomar por detrás).
        Runnable sync = () -> {
            boolean loaded = img.getProgress() >= 1 && !img.isError();
            ph.setVisible(!loaded);
            view.setVisible(!img.isError());
        };
        img.progressProperty().addListener((o, a, b) -> sync.run());
        img.errorProperty().addListener((o, a, b) -> sync.run());
        sync.run();
        return p;
    }

    private static Node placeholder(String title, double size) {
        String letter = title == null || title.isBlank() ? "?" : title.substring(0, 1).toUpperCase();
        Label l = new Label(letter);
        l.setMinSize(size, size);
        l.setMaxSize(size, size);
        l.setAlignment(Pos.CENTER);
        int hue = title == null ? 0 : Math.floorMod(title.hashCode(), 360);
        l.setStyle(String.format("-fx-background-color: hsb(%d, 45%%, 55%%); -fx-background-radius: %.0f;"
                + " -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: %.0fpx;", hue, size / 5, size * 0.45));
        return l;
    }
}
