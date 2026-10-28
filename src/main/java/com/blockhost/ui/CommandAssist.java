package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.CommandCatalog;
import com.blockhost.core.CommandCatalog.Suggestion;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerProcess;
import com.blockhost.core.ServerProcess.Status;
import com.blockhost.core.providers.ServerProvider;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;

/**
 * Autocompletado de la línea de comandos de la consola: lista de sugerencias encima del campo (Tab o clic para
 * completar, flechas para elegir) e historial con las flechas cuando no hay sugerencias.
 */
final class CommandAssist {

    private static final int MAX_SHOWN = 8;
    private static final int HISTORY = 100;

    private final TextField input;
    private final ServerProcess process;
    private final CommandCatalog catalog;
    private final ListView<Suggestion> list = new ListView<>();
    private final Label hint = new Label();
    private final List<String> history = new ArrayList<>();
    private int historyPos = -1;
    private volatile long learnUntil;
    private boolean applying;

    CommandAssist(ServerInstance instance, ServerProcess process, TextField input) {
        this.input = input;
        this.process = process;
        this.catalog = new CommandCatalog(instance.config().type(), instance.config().mcVersion());

        list.setCellFactory(l -> new SuggestionCell());
        list.setFocusTraversable(false);
        list.setMaxWidth(560);
        list.setFixedCellSize(26);
        list.setVisible(false);
        list.getStyleClass().add("command-suggestions");
        list.setOnMouseClicked(e -> { if (e.getClickCount() >= 1) accept(); });
        StackPane.setAlignment(list, Pos.BOTTOM_LEFT);

        hint.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        hint.setMinHeight(Region.USE_PREF_SIZE);

        input.textProperty().addListener((o, a, b) -> { if (!applying) refresh(); });
        input.focusedProperty().addListener((o, a, b) -> { if (!b) hide(); });
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        // al arrancar se pide "help" sin enseñarlo, para aprender los comandos de mods, plugins y datapacks
        process.onStatus(s -> { if (s == Status.RUNNING) learnCommands(instance); });
        process.onMessage(msg -> { if (System.currentTimeMillis() < learnUntil) catalog.learn(msg); });
    }

    /** La lista de sugerencias, para ponerla encima de la consola. */
    ListView<Suggestion> suggestions() { return list; }

    /** La pista con la sintaxis del comando que se está escribiendo. */
    Label hint() { return hint; }

    /** Guarda el comando enviado en el historial. */
    void sent(String command) {
        if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) history.add(command);
        if (history.size() > HISTORY) history.remove(0);
        historyPos = -1;
        hide();
        hint.setText("");
    }

    private void learnCommands(ServerInstance instance) {
        if (ServerProvider.VERSION_ORDER.compare(instance.config().mcVersion(), "1.13") < 0) return;
        learnUntil = System.currentTimeMillis() + 5000;
        process.sendQuiet("minecraft:help", m -> m.startsWith("/"), 5000);
    }

    private void onKey(KeyEvent e) {
        boolean open = list.isVisible() && !list.getItems().isEmpty();
        KeyCode k = e.getCode();
        if (k == KeyCode.TAB) {
            if (open) accept();
            e.consume();
        } else if (k == KeyCode.ESCAPE && open) {
            hide();
            e.consume();
        } else if ((k == KeyCode.UP || k == KeyCode.DOWN) && open) {
            int n = list.getItems().size();
            int i = list.getSelectionModel().getSelectedIndex();
            i = k == KeyCode.DOWN ? (i + 1) % n : (i - 1 + n) % n;
            list.getSelectionModel().select(i);
            list.scrollTo(i);
            e.consume();
        } else if (k == KeyCode.UP || k == KeyCode.DOWN) {
            browseHistory(k == KeyCode.UP);
            e.consume();
        }
    }

    private void browseHistory(boolean older) {
        if (history.isEmpty()) return;
        if (older) historyPos = historyPos < 0 ? history.size() - 1 : Math.max(0, historyPos - 1);
        else if (historyPos >= 0) historyPos = historyPos + 1 >= history.size() ? -1 : historyPos + 1;
        setText(historyPos < 0 ? "" : history.get(historyPos));
        hide();
    }

    private void accept() {
        Suggestion s = list.getSelectionModel().getSelectedItem();
        if (s == null && !list.getItems().isEmpty()) s = list.getItems().get(0);
        if (s == null || s.text().isEmpty()) return;
        setText(CommandCatalog.apply(input.getText(), s));
        refresh();
        input.requestFocus();
    }

    private void setText(String text) {
        applying = true;
        input.setText(text);
        input.positionCaret(text.length());
        applying = false;
    }

    private void refresh() {
        String text = input.getText();
        List<Suggestion> all = catalog.suggest(text, process.players());
        String cmd = text.startsWith("/") ? text.substring(1) : text;
        int sp = cmd.indexOf(' ');
        CommandCatalog.Command c = sp > 0 ? catalog.get(cmd.substring(0, sp).toLowerCase()) : null;
        hint.setText(c == null ? "" : "/" + c.name() + " " + c.syntax()
                + "   ·   Tab completa, ↑↓ eligen, Esc cierra");

        List<Suggestion> real = all.stream().filter(s -> !s.text().isEmpty()).limit(50).toList();
        if (real.isEmpty() || !input.isFocused()) {
            hide();
            return;
        }
        list.getItems().setAll(real);
        list.getSelectionModel().select(0);
        list.setPrefHeight(Math.min(real.size(), MAX_SHOWN) * list.getFixedCellSize() + 4);
        list.setMaxHeight(list.getPrefHeight());
        list.setVisible(true);
    }

    private void hide() {
        list.setVisible(false);
        list.getItems().clear();
    }

    private static final class SuggestionCell extends ListCell<Suggestion> {
        private final Label text = new Label();
        private final Label hint = new Label();
        private final HBox box = new HBox(10, text, hint);

        SuggestionCell() {
            text.getStyleClass().add(Styles.TEXT_BOLD);
            hint.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
            HBox.setHgrow(hint, Priority.ALWAYS);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Suggestion s, boolean empty) {
            super.updateItem(s, empty);
            if (empty || s == null) {
                setGraphic(null);
                return;
            }
            text.setText(s.text());
            hint.setText(s.hint() == null ? "" : s.hint());
            setGraphic(box);
        }
    }
}
