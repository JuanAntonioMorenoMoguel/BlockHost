package com.blockhost.ui;

import atlantafx.base.theme.Styles;
import com.blockhost.core.ServerInstance;
import com.blockhost.core.ServerProperties;
import com.blockhost.core.SystemInfo;
import com.blockhost.core.net.ConnectionGuide;
import com.blockhost.core.net.NatPmp;
import com.blockhost.core.net.PortForwarder;
import com.blockhost.core.net.PortForwarder.State;
import com.blockhost.core.net.ReachabilityCheck;
import com.blockhost.core.net.WindowsFirewall;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * Cómo conectarse al servidor: desde este PC, desde la red de casa y desde internet. Abre el puerto en el router
 * al iniciar (si está activado), enseña la dirección para compartir y, si no se puede, una guía con alternativas.
 */
final class ConnectionPane extends VBox {

    private final ServerInstance instance;
    private final Label internet = new Label();
    private final Label share = new Label();
    private final Button copyBtn = new Button("Copiar");
    private final Button retryBtn = new Button("Reintentar");
    private final Button firewallBtn = new Button("Permitir en el Firewall");
    private final Button guideBtn = new Button("Cómo invitar a amigos de fuera...");
    private volatile PortForwarder forwarder;
    private volatile boolean running;
    private volatile boolean ready; // el servidor ya acepta jugadores: se puede comprobar desde internet
    private String publicIp;

    ConnectionPane(ServerInstance instance) {
        super(4);
        this.instance = instance;
        int port = instance.config().port();
        List<String> ips = SystemInfo.localIPv4();
        String lan = ips.isEmpty() ? "—" : String.join(", ", ips.stream().map(ip -> ip + ":" + port).toList());
        Label local = small("Desde este PC: localhost:" + port + "    ·    Desde tu red (Wi-Fi/cable): " + lan);

        internet.setWrapText(true);
        internet.setMinHeight(Region.USE_PREF_SIZE);
        internet.getStyleClass().add(Styles.TEXT_SMALL);
        share.getStyleClass().addAll(Styles.TEXT_BOLD);
        for (Button b : List.of(copyBtn, retryBtn, firewallBtn, guideBtn)) {
            b.getStyleClass().addAll(Styles.SMALL);
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        guideBtn.getStyleClass().add(Styles.FLAT);
        copyBtn.setOnAction(e -> copy(share.getText()));
        retryBtn.setOnAction(e -> {
            if (instance.openRouterPort()) openPort();
            else verify();
        });
        firewallBtn.setOnAction(e -> addFirewallRule());
        guideBtn.setOnAction(e -> showGuide());

        HBox shareRow = new HBox(8, share, copyBtn);
        shareRow.setAlignment(Pos.CENTER_LEFT);
        FlowPane actions = new FlowPane(6, 4, shareRow, retryBtn, firewallBtn, guideBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        String extra = instance.config().type().supportsMods()
                ? "Mods: gestiónalos en la pestaña «Mods» (los jugadores necesitan los mismos)."
                : instance.config().type().supportsPlugins() ? "Plugins: búscalos e instálalos en la pestaña «Plugins»." : null;
        getChildren().addAll(local, internet, actions);
        if (extra != null) getChildren().add(small(extra));

        showIdle();
        firewallBtn.setVisible(false);
        firewallBtn.setManaged(false);
        UiTasks.run(() -> {
            boolean need = WindowsFirewall.supported() && !WindowsFirewall.hasRule(port);
            Platform.runLater(() -> {
                firewallBtn.setVisible(need);
                firewallBtn.setManaged(need);
            });
        });
    }

    private static Label small(String text) {
        Label l = new Label(text);
        l.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    // ---------------- ciclo del servidor ----------------

    void serverStarting() {
        running = true;
        ready = false;
        if (instance.openRouterPort()) openPort();
        else showIdle();
    }

    /** Ya acepta jugadores: se comprueba desde internet si llegan (también si el puerto se abrió a mano). */
    void serverRunning() {
        ready = true;
        verify();
    }

    /** Al parar: cierra el puerto en segundo plano. */
    void serverStopped() {
        running = false;
        ready = false;
        PortForwarder f = forwarder;
        forwarder = null;
        if (f != null) UiTasks.run(f::close);
        else showIdle();
    }

    /** Cierra el puerto ya (al salir de la app). */
    void closeNow() {
        PortForwarder f = forwarder;
        forwarder = null;
        if (f != null) f.close();
    }

    private void openPort() {
        if (!running) return;
        PortForwarder old = forwarder;
        int port = instance.config().port();
        int udp = 0;
        try {
            ServerProperties p = ServerProperties.load(instance.dir());
            if (p.getBool("enable-query", false)) udp = p.getInt("query.port", port);
        } catch (Exception ignored) {
            // sin query
        }
        PortForwarder f = new PortForwarder(port, udp, "BlockHost " + instance.config().name(),
                s -> Platform.runLater(() -> show(s)));
        forwarder = f;
        UiTasks.run(() -> {
            if (old != null) old.close();
            f.open();
            if (!running || forwarder != f) f.close(); // se paró mientras buscaba el router
            else if (ready) verifyNow(); // reintento con el servidor ya en marcha
        });
    }

    private void verify() { UiTasks.run(this::verifyNow); }

    /**
     * Pregunta a un servicio de internet si llega al servidor. Sirve cuando el router no tiene UPnP pero el puerto
     * ya está abierto a mano, y para confirmar que lo abierto por UPnP funciona de verdad. En segundo plano.
     */
    private void verifyNow() {
        PortForwarder f = forwarder;
        if (f != null) f.awaitIdle();
        PortForwarder.Status s = f == null ? null : f.last();
        if (s != null && (s.state() == State.CGNAT || s.state() == State.DOUBLE_NAT)) return; // ya se sabe que no llega
        int port = instance.config().port();
        String pub = publicIp != null ? publicIp : PortForwarder.publicIp();
        publicIp = pub;
        if (pub == null || !ready) return;
        if (s == null || s.state() != State.OPEN) {
            Platform.runLater(() -> {
                setInternet("Amigos fuera de tu casa: comprobando desde internet si el puerto " + port
                        + " ya está abierto...", null);
                setButtons(false, false);
            });
        }
        ReachabilityCheck.Outcome o = ReachabilityCheck.check(pub, port);
        Platform.runLater(() -> { if (ready && forwarder == f) showCheck(o, s, pub, port); });
    }

    private void showCheck(ReachabilityCheck.Outcome o, PortForwarder.Status s, String pub, int port) {
        boolean opened = s != null && s.state() == State.OPEN;
        switch (o.result()) {
            case REACHABLE -> {
                setInternet("✔ " + (opened ? s.detail() + " " : "El puerto " + port + " ya está abierto en tu router. ")
                        + "Comprobado desde internet: tus amigos pueden entrar con:", Styles.SUCCESS);
                share.setText(port == 25565 ? pub : pub + ":" + port);
                setButtons(true, false);
            }
            case UNREACHABLE -> {
                if (opened) {
                    setInternet("⚠ El router dice que el puerto está abierto, pero desde internet no se llega "
                            + "(comprobado con " + o.service() + "). Suele ser el Firewall de Windows o otro router "
                            + "delante. Mira la guía.", Styles.WARNING);
                } else {
                    setInternet("✖ " + (s != null && s.detail() != null ? "No se pudo abrir el puerto: " + s.detail() + " " : "")
                            + "Desde internet tampoco se llega al puerto " + port + " (comprobado con " + o.service()
                            + "), así que no está abierto a mano. Ábrelo en el router o usa un túnel: mira la guía. "
                            + "Si acabas de abrirlo, espera un minuto y pulsa «Comprobar otra vez».", Styles.DANGER);
                }
                retryBtn.setText("Comprobar otra vez");
                setButtons(false, true);
            }
            case UNKNOWN -> {
                if (s != null) show(s); // sin respuesta de los servicios: se queda lo que dijo el router
                else showIdle();
            }
        }
    }

    // ---------------- estado ----------------

    private void showIdle() {
        int port = instance.config().port();
        setButtons(false, false);
        if (instance.openRouterPort()) {
            setInternet("Amigos fuera de tu casa: al iniciar, BlockHost intentará abrir el puerto " + port
                    + " en el router (UPnP).", null);
        } else {
            setInternet("Amigos fuera de tu casa: la apertura automática del puerto está desactivada (⚙ → Editar "
                    + "ajustes). Ábrelo a mano o usa un túnel: mira la guía.", Styles.WARNING);
        }
    }

    private void show(PortForwarder.Status s) {
        if (s.publicIp() != null) publicIp = s.publicIp();
        switch (s.state()) {
            case WORKING -> {
                setInternet("Amigos fuera de tu casa: abriendo el puerto " + s.port() + " en el router...", null);
                setButtons(false, false);
            }
            case OPEN -> {
                setInternet("✔ " + s.detail() + " Tus amigos pueden entrar con:", Styles.SUCCESS);
                share.setText(s.shareAddress());
                setButtons(s.shareAddress() != null, false);
            }
            case CGNAT, DOUBLE_NAT -> {
                setInternet("⚠ " + s.detail() + " Mira la guía para otras formas de jugar.", Styles.WARNING);
                setButtons(false, true);
            }
            case NO_SUPPORT, FAILED -> {
                setInternet("✖ No se pudo abrir el puerto: " + s.detail()
                        + " Puedes abrirlo a mano en el router o usar un túnel: mira la guía.", Styles.DANGER);
                setButtons(false, true);
            }
            case CLOSED -> showIdle();
        }
    }

    private void setInternet(String text, String style) {
        internet.getStyleClass().removeAll(Styles.SUCCESS, Styles.WARNING, Styles.DANGER, Styles.TEXT_MUTED);
        internet.getStyleClass().add(style == null ? Styles.TEXT_MUTED : style);
        internet.setText(text);
    }

    private void setButtons(boolean shareVisible, boolean retry) {
        share.setVisible(shareVisible);
        share.setManaged(shareVisible);
        copyBtn.setVisible(shareVisible);
        copyBtn.setManaged(shareVisible);
        retryBtn.setVisible(retry && running);
        retryBtn.setManaged(retry && running);
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

    private void addFirewallRule() {
        int port = instance.config().port();
        firewallBtn.setDisable(true);
        UiTasks.run(() -> {
            boolean ok;
            try {
                ok = WindowsFirewall.addRule(port);
            } catch (Exception e) {
                ok = false;
            }
            boolean done = ok;
            Platform.runLater(() -> {
                firewallBtn.setDisable(false);
                firewallBtn.setVisible(!done);
                firewallBtn.setManaged(!done);
                if (!done) UiTasks.error(window(), "No se creó la regla del Firewall",
                        "Hace falta aceptar el aviso de Windows (permisos de administrador). Puedes volver a intentarlo.");
            });
        });
    }

    private void showGuide() {
        PortForwarder.Status s = forwarder == null ? null : forwarder.last();
        boolean cgnat = s != null && (s.state() == State.CGNAT || s.state() == State.DOUBLE_NAT);
        guideBtn.setDisable(true);
        UiTasks.run(() -> {
            List<String> locals = SystemInfo.localIPv4();
            List<String> gws = NatPmp.gateways(locals);
            String pub = publicIp != null ? publicIp : PortForwarder.publicIp();
            publicIp = pub;
            ConnectionGuide.Context c = new ConnectionGuide.Context(locals.isEmpty() ? null : locals.get(0),
                    gws.isEmpty() ? null : gws.get(0), pub, instance.config().port(), cgnat);
            Platform.runLater(() -> {
                guideBtn.setDisable(false);
                new ConnectionGuideDialog(window(), c).show();
            });
        });
    }

    private Window window() { return getScene() == null ? null : getScene().getWindow(); }
}
