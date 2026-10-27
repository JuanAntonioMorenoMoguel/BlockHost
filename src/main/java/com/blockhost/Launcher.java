package com.blockhost;

import com.blockhost.ui.App;
import javafx.application.Application;

/** Punto de entrada. No extiende Application para que JavaFX arranque desde el classpath y en el instalador. */
public final class Launcher {

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
