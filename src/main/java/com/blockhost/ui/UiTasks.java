package com.blockhost.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

/** Utilidades de UI compartidas: tareas en segundo plano y diálogos con texto largo. */
final class UiTasks {

    private UiTasks() { }

    static void run(Runnable r) {
        Thread t = new Thread(r, "ui-task");
        t.setDaemon(true);
        t.start();
    }

    static void error(Window owner, String header, String text) {
        Alert a = alert(Alert.AlertType.ERROR, owner, header, text);
        a.showAndWait();
    }

    static void info(Window owner, String header, String text) {
        alert(Alert.AlertType.INFORMATION, owner, header, text).showAndWait();
    }

    /**
     * Pide confirmación. Si risky, el botón por defecto es Cancelar y el de aceptar dice "Instalar igualmente".
     */
    static boolean confirm(Window owner, String header, String text, String okText, boolean risky) {
        Alert a = alert(risky ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION, owner, header, text);
        ButtonType ok = new ButtonType(risky ? okText + " igualmente" : okText, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(cancel, ok);
        ((javafx.scene.control.Button) a.getDialogPane().lookupButton(ok)).setDefaultButton(!risky);
        ((javafx.scene.control.Button) a.getDialogPane().lookupButton(cancel)).setDefaultButton(risky);
        return a.showAndWait().orElse(cancel) == ok;
    }

    private static Alert alert(Alert.AlertType type, Window owner, String header, String text) {
        Alert a = new Alert(type);
        a.initOwner(owner);
        a.setHeaderText(header);
        if (text.lines().count() > 6) {
            TextArea area = new TextArea(text);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(14);
            a.getDialogPane().setContent(area);
        } else {
            a.setContentText(text);
        }
        a.getDialogPane().setPrefWidth(560);
        return a;
    }
}
