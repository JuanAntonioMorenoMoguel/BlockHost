package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.net.ConnectionGuide;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

/** Guía paso a paso para jugar con amigos de fuera de casa cuando el router no abre el puerto solo. */
final class ConnectionGuideDialog extends Stage {

    ConnectionGuideDialog(Window owner, ConnectionGuide.Context c) {
        initOwner(owner);
        initModality(Modality.NONE);
        setTitle("Jugar con amigos de fuera de casa");

        Label title = new Label("Jugar con amigos de fuera de casa");
        title.getStyleClass().add(Styles.TITLE_3);
        Label intro = wrap(c.cgnat()
                ? "Tu conexión tiene CG-NAT (o hay otro router delante), así que abrir el puerto no basta. "
                + "Elige una de estas opciones; las tres primeras funcionan siempre."
                : "Si el router no abre el puerto solo, elige una de estas opciones. Cada una dice qué haces tú y qué "
                + "hacen tus amigos.");

        VBox data = new VBox(4);
        data.getChildren().add(bold("Tus datos"));
        if (c.localIp() != null) data.getChildren().add(copyRow("IP de este PC", c.localIp()));
        if (c.gatewayIp() != null) data.getChildren().add(copyRow("IP del router", c.gatewayIp()));
        data.getChildren().add(copyRow("Puerto", c.port() + " (TCP)"));
        if (c.publicIp() != null) data.getChildren().add(copyRow("IP pública", c.publicIp()));

        Accordion acc = new Accordion();
        List<ConnectionGuide.Method> methods = ConnectionGuide.methods(c);
        for (ConnectionGuide.Method m : methods) acc.getPanes().add(pane(m));
        if (!acc.getPanes().isEmpty()) acc.setExpandedPane(acc.getPanes().get(0));

        Button close = new Button("Cerrar");
        close.setOnAction(e -> close());
        close.setCancelButton(true);
        HBox bottom = new HBox(close);
        bottom.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(12, title, intro, data, acc);
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        VBox.setVgrow(sp, Priority.ALWAYS);
        VBox root = new VBox(sp, bottom);
        bottom.setPadding(new Insets(10, 20, 16, 20));
        setScene(new Scene(root, 720, 680));
    }

    private static TitledPane pane(ConnectionGuide.Method m) {
        VBox box = new VBox(8);
        box.getChildren().add(muted(m.summary()));
        box.getChildren().add(bold("Tú (quien tiene el servidor)"));
        box.getChildren().add(steps(m.host()));
        box.getChildren().add(bold("Tus amigos"));
        box.getChildren().add(steps(m.players()));
        if (m.note() != null) {
            Label n = wrap(m.note());
            n.getStyleClass().addAll(Styles.WARNING, Styles.TEXT_SMALL);
            box.getChildren().add(n);
        }
        TitledPane p = new TitledPane(m.title(), box);
        p.setAnimated(false);
        return p;
    }

    private static VBox steps(List<String> steps) {
        VBox v = new VBox(6);
        for (int i = 0; i < steps.size(); i++) {
            Label n = new Label((i + 1) + ".");
            n.setMinWidth(22);
            Label t = wrap(steps.get(i));
            HBox.setHgrow(t, Priority.ALWAYS);
            HBox row = new HBox(4, n, t);
            v.getChildren().add(row);
        }
        return v;
    }

    private static HBox copyRow(String label, String value) {
        Label l = new Label(label + ":");
        l.setMinWidth(110);
        l.getStyleClass().add(Styles.TEXT_MUTED);
        Label v = new Label(value);
        v.getStyleClass().add(Styles.TEXT_BOLD);
        Button copy = new Button("Copiar");
        copy.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
        String raw = value.replace(" (TCP)", "");
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(raw);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        HBox row = new HBox(8, l, v, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label wrap(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    private static Label bold(String text) {
        Label l = new Label(text);
        l.getStyleClass().add(Styles.TEXT_BOLD);
        return l;
    }

    private static Label muted(String text) {
        Label l = wrap(text);
        l.getStyleClass().add(Styles.TEXT_MUTED);
        return l;
    }
}
