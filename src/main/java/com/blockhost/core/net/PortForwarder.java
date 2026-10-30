package com.blockhost.core.net;

import com.blockhost.core.Http;
import com.blockhost.core.Json;
import com.blockhost.core.SystemInfo;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abre el puerto del servidor en el router al iniciarlo y lo cierra al pararlo: primero por UPnP y, si no hay,
 * por NAT-PMP. Además averigua la IP pública y avisa si hay CG-NAT u otro router delante, que hacen inútil
 * abrir el puerto.
 */
public final class PortForwarder {

    public enum State {
        /** Buscando el router. */
        WORKING,
        /** Abierto: los amigos pueden entrar con la dirección pública. */
        OPEN,
        /** El router no tiene UPnP ni NAT-PMP (o están desactivados). */
        NO_SUPPORT,
        /** Se abrió, pero la IP del router no es pública: el operador comparte IP (CG-NAT). */
        CGNAT,
        /** Se abrió, pero hay otro router delante (el del operador) con su propia red. */
        DOUBLE_NAT,
        /** El router contestó pero no quiso abrirlo. */
        FAILED,
        /** Cerrado al parar el servidor. */
        CLOSED
    }

    /**
     * @param method       "UPnP" o "NAT-PMP" si se abrió
     * @param routerIp     IP de internet que dice tener el router
     * @param publicIp     IP pública vista desde internet
     * @param externalPort puerto abierto en el router (normalmente el mismo)
     */
    public record Status(State state, String method, String routerIp, String publicIp, String localIp, int port,
                         int externalPort, String detail) {

        /** Lo que tienen que escribir los amigos, o null si no se sabe la IP pública. */
        public String shareAddress() {
            String ip = publicIp != null ? publicIp : routerIp;
            if (ip == null) return null;
            int p = externalPort > 0 ? externalPort : port;
            return p == 25565 ? ip : ip + ":" + p;
        }
    }

    public interface Listener { void changed(Status s); }

    private static final int NATPMP_LIFETIME = 7200;
    private static final String[] PUBLIC_IP_URLS = {"https://api.ipify.org?format=json", "https://api64.ipify.org?format=json"};

    private final int port;
    private final int udpPort; // query.port si está activado, si no 0
    private final String description;
    private final Listener listener;

    private Upnp.Gateway upnp;
    private String natPmpGateway;
    private boolean udpMapped;
    private ScheduledExecutorService renewer;
    private ScheduledFuture<?> renewal;
    private volatile Status last;

    public PortForwarder(int port, int udpPort, String description, Listener listener) {
        this.port = port;
        this.udpPort = udpPort;
        this.description = description;
        this.listener = listener;
    }

    public Status last() { return last; }

    /** Espera a que termine un open() o close() en marcha (comparten el cerrojo). */
    public synchronized void awaitIdle() { }

    /** Abre el puerto. Tarda unos segundos (busca el router): llamar desde un hilo de fondo. */
    public synchronized Status open() {
        List<String> locals = SystemInfo.localIPv4();
        String local = locals.isEmpty() ? null : locals.get(0);
        publish(new Status(State.WORKING, null, null, null, local, port, 0, "Buscando el router..."));
        if (locals.isEmpty()) {
            return publish(new Status(State.NO_SUPPORT, null, null, publicIp(), null, port, 0,
                    "Este PC no está conectado a ninguna red local."));
        }

        String upnpError = null;
        Upnp.Gateway g = Upnp.discover(locals, 2500);
        if (g != null) {
            String routerIp = null;
            try {
                routerIp = Upnp.externalIp(g);
            } catch (Exception ignored) {
                // algunos routers no lo dicen; se sigue
            }
            try {
                String current = Upnp.mappedClient(g, port, "TCP");
                if (current != null && !current.equals(g.localIp())) {
                    String why = "El router ya manda el puerto " + port + " a otro equipo de tu red (" + current
                            + "). Quítalo en el router o usa otro puerto en los ajustes del servidor.";
                    return publish(new Status(State.FAILED, "UPnP", routerIp, publicIp(), g.localIp(), port, 0, why));
                }
            } catch (Exception ignored) {
                // no todos lo admiten; se intenta abrir igual
            }
            try {
                Upnp.addPortMapping(g, port, "TCP", description, 0);
                upnp = g;
                if (udpPort > 0) udpMapped = tryUdpUpnp(g);
                return publish(classify("UPnP", routerIp, publicIp(), g.localIp(), port,
                        "Abierto por UPnP en " + (g.friendlyName() == null ? "el router" : g.friendlyName().trim()) + "."));
            } catch (Exception e) {
                upnpError = e.getMessage();
            }
        }

        for (String gw : NatPmp.gateways(locals)) {
            String routerIp = NatPmp.externalIp(gw);
            if (routerIp == null) continue;
            int ext = NatPmp.map(gw, port, true, NATPMP_LIFETIME);
            if (ext <= 0) {
                upnpError = "El router (" + gw + ") no quiso abrir el puerto por NAT-PMP.";
                continue;
            }
            natPmpGateway = gw;
            if (udpPort > 0) udpMapped = NatPmp.map(gw, udpPort, false, NATPMP_LIFETIME) > 0;
            scheduleRenewal();
            Status s = classify("NAT-PMP", routerIp, publicIp(), local, port, "Abierto por NAT-PMP en " + gw + ".");
            return publish(new Status(s.state(), s.method(), s.routerIp(), s.publicIp(), s.localIp(), port, ext, s.detail()));
        }

        if (upnpError != null) {
            return publish(new Status(State.FAILED, g != null ? "UPnP" : "NAT-PMP", null, publicIp(),
                    g != null ? g.localIp() : local, port, 0, upnpError));
        }
        return publish(new Status(State.NO_SUPPORT, null, null, publicIp(), local, port, 0,
                "Tu router no tiene UPnP ni NAT-PMP, o están desactivados."));
    }

    private boolean tryUdpUpnp(Upnp.Gateway g) {
        try {
            Upnp.addPortMapping(g, udpPort, "UDP", description + " (query)", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Cierra lo que se abrió. Se puede llamar varias veces. */
    public synchronized void close() {
        if (renewal != null) renewal.cancel(false);
        if (renewer != null) renewer.shutdownNow();
        renewal = null;
        renewer = null;
        boolean had = upnp != null || natPmpGateway != null;
        if (upnp != null) {
            try {
                Upnp.deletePortMapping(upnp, port, "TCP");
                if (udpMapped) Upnp.deletePortMapping(upnp, udpPort, "UDP");
            } catch (Exception ignored) {
                // si el router se ha reiniciado ya no está
            }
            upnp = null;
        }
        if (natPmpGateway != null) {
            NatPmp.map(natPmpGateway, port, true, 0);
            if (udpMapped) NatPmp.map(natPmpGateway, udpPort, false, 0);
            natPmpGateway = null;
        }
        udpMapped = false;
        if (had || last != null) {
            Status l = last;
            publish(new Status(State.CLOSED, null, l == null ? null : l.routerIp(), l == null ? null : l.publicIp(),
                    l == null ? null : l.localIp(), port, 0, "Puerto cerrado en el router."));
        }
    }

    /** NAT-PMP caduca: se renueva a mitad de tiempo mientras el servidor sigue en marcha. */
    private void scheduleRenewal() {
        renewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "natpmp-renew-" + port);
            t.setDaemon(true);
            return t;
        });
        renewal = renewer.scheduleWithFixedDelay(() -> {
            String gw = natPmpGateway;
            if (gw == null) return;
            NatPmp.map(gw, port, true, NATPMP_LIFETIME);
            if (udpMapped) NatPmp.map(gw, udpPort, false, NATPMP_LIFETIME);
        }, NATPMP_LIFETIME / 2, NATPMP_LIFETIME / 2, TimeUnit.SECONDS);
    }

    private Status publish(Status s) {
        last = s;
        if (listener != null) listener.changed(s);
        return s;
    }

    // ---------------- diagnóstico ----------------

    /** Decide si el puerto abierto sirve de algo según la IP que tiene el router. Paquete para los tests. */
    static Status classify(String method, String routerIp, String publicIp, String localIp, int port, String okDetail) {
        if (routerIp != null && isCgnat(routerIp)) {
            return new Status(State.CGNAT, method, routerIp, publicIp, localIp, port, 0,
                    "El router tiene la IP " + routerIp + ", que no es de internet: tu operador comparte una IP pública "
                            + "entre varios clientes (CG-NAT) y desde fuera no se puede llegar a tu router.");
        }
        if (routerIp != null && isPrivate(routerIp)) {
            return new Status(State.DOUBLE_NAT, method, routerIp, publicIp, localIp, port, 0,
                    "Tu router está detrás de otro (su IP es " + routerIp + "). Hay que abrir el puerto también en el "
                            + "primero, el del operador, o poner este en modo bridge.");
        }
        if (routerIp != null && publicIp != null && !routerIp.equals(publicIp)) {
            return new Status(State.CGNAT, method, routerIp, publicIp, localIp, port, 0,
                    "El router dice que su IP es " + routerIp + ", pero internet te ve como " + publicIp
                            + ": hay CG-NAT u otro router por medio.");
        }
        return new Status(State.OPEN, method, routerIp, publicIp, localIp, port, 0, okDetail);
    }

    /** 100.64.0.0/10, el rango que usan los operadores para CG-NAT. */
    static boolean isCgnat(String ip) {
        int[] o = octets(ip);
        return o != null && o[0] == 100 && o[1] >= 64 && o[1] <= 127;
    }

    static boolean isPrivate(String ip) {
        int[] o = octets(ip);
        if (o == null) return false;
        return o[0] == 10 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31) || (o[0] == 192 && o[1] == 168)
                || o[0] == 127 || (o[0] == 169 && o[1] == 254) || o[0] == 0;
    }

    private static int[] octets(String ip) {
        String[] p = ip.trim().split("\\.");
        if (p.length != 4) return null;
        int[] o = new int[4];
        try {
            for (int i = 0; i < 4; i++) o[i] = Integer.parseInt(p[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return o;
    }

    /** IP con la que te ve internet (null sin conexión). */
    public static String publicIp() {
        for (String url : PUBLIC_IP_URLS) {
            try {
                String ip = Json.str(Json.obj(Json.parse(Http.getText(url, Duration.ofSeconds(6)))).get("ip"));
                if (ip != null && octetsOk(ip)) return ip;
            } catch (Exception ignored) {
                // probar la siguiente
            }
        }
        return null;
    }

    private static boolean octetsOk(String ip) { return octets(ip) != null; }
}
