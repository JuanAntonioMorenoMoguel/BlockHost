package com.blockhost.core.net;

import com.blockhost.core.SystemInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Puertos del router para la pantalla de gestión: ver, abrir y cerrar a mano. Con UPnP se ve todo lo que tiene
 * abierto el router; con NAT-PMP solo lo que se ha abierto desde aquí (el protocolo no permite listarlo).
 */
public final class RouterPorts {

    public enum Method { UPNP, NATPMP, NONE }

    /**
     * @param ours    descripción que empieza por "BlockHost": lo abrió esta app
     * @param thisPc  apunta a este PC
     */
    public record Port(int port, String protocol, String internalClient, String description, boolean enabled,
                       boolean ours, boolean thisPc) { }

    /** Atajos para puertos que suelen hacer falta además del de Minecraft. */
    public record Preset(String label, int port, String protocol) { }

    public static final List<Preset> PRESETS = List.of(
            new Preset("Simple Voice Chat (mod/plugin de voz)", 24454, "UDP"),
            new Preset("Geyser (jugadores de Bedrock: móvil, consola)", 19132, "UDP"),
            new Preset("Plasmo Voice", 60606, "UDP"),
            new Preset("BlueMap / Dynmap (mapa web)", 8100, "TCP"),
            new Preset("RCON (consola remota)", 25575, "TCP"));

    public static final String PREFIX = "BlockHost";
    private static final int NATPMP_LIFETIME = 7200;

    // lo abierto por NAT-PMP desde la pantalla de puertos: se renueva mientras la app siga abierta
    private static final Map<String, Port> NATPMP_OPEN = new ConcurrentHashMap<>();
    private static ScheduledExecutorService renewer;

    private final Method method;
    private final Upnp.Gateway upnp;
    private final String natPmpGateway;
    private final String localIp;
    private final String routerName;

    private RouterPorts(Method method, Upnp.Gateway upnp, String natPmpGateway, String localIp, String routerName) {
        this.method = method;
        this.upnp = upnp;
        this.natPmpGateway = natPmpGateway;
        this.localIp = localIp;
        this.routerName = routerName;
    }

    /** Busca el router (unos segundos): llamar desde un hilo de fondo. */
    public static RouterPorts discover() {
        List<String> locals = SystemInfo.localIPv4();
        String local = locals.isEmpty() ? null : locals.get(0);
        if (locals.isEmpty()) return new RouterPorts(Method.NONE, null, null, null, null);
        Upnp.Gateway g = Upnp.discover(locals, 2500);
        if (g != null) {
            String name = g.friendlyName() == null ? null : g.friendlyName().trim();
            return new RouterPorts(Method.UPNP, g, null, g.localIp(), name);
        }
        for (String gw : NatPmp.gateways(locals)) {
            if (NatPmp.externalIp(gw) != null) return new RouterPorts(Method.NATPMP, null, gw, local, gw);
        }
        return new RouterPorts(Method.NONE, null, null, local, null);
    }

    public Method method() { return method; }
    public String localIp() { return localIp; }
    public String routerName() { return routerName; }

    public List<Port> list() throws IOException {
        List<Port> out = new ArrayList<>();
        if (method == Method.UPNP) {
            for (Upnp.Mapping m : Upnp.listPortMappings(upnp)) out.add(toPort(m, localIp));
        } else if (method == Method.NATPMP) {
            out.addAll(NATPMP_OPEN.values());
        }
        out.sort(Comparator.comparingInt(Port::port).thenComparing(Port::protocol));
        return out;
    }

    static Port toPort(Upnp.Mapping m, String localIp) {
        String desc = m.description();
        return new Port(m.externalPort(), m.protocol().toUpperCase(Locale.ROOT), m.internalClient(), desc, m.enabled(),
                desc != null && desc.startsWith(PREFIX), localIp != null && localIp.equals(m.internalClient()));
    }

    /** @param protocol "TCP" o "UDP" */
    public void open(int port, String protocol, String description) throws IOException {
        String desc = description == null || description.isBlank() ? PREFIX : description.trim();
        if (!desc.startsWith(PREFIX)) desc = PREFIX + " " + desc; // para reconocerlo luego y poder cerrarlo todo
        switch (method) {
            case UPNP -> {
                String current = Upnp.mappedClient(upnp, port, protocol);
                if (current != null && !current.isBlank() && !current.equals(upnp.localIp())) {
                    throw new IOException("El puerto " + port + " " + protocol + " ya apunta a otro equipo de tu red ("
                            + current + "). Ciérralo primero.");
                }
                Upnp.addPortMapping(upnp, port, protocol, desc, 0);
            }
            case NATPMP -> {
                if (NatPmp.map(natPmpGateway, port, "TCP".equals(protocol), NATPMP_LIFETIME) <= 0) {
                    throw new IOException("El router no quiso abrir el puerto " + port + " " + protocol + ".");
                }
                NATPMP_OPEN.put(port + "/" + protocol, new Port(port, protocol, localIp, desc, true, true, true));
                startRenewer(natPmpGateway);
            }
            case NONE -> throw new IOException("Tu router no tiene UPnP ni NAT-PMP.");
        }
    }

    public void close(Port p) throws IOException {
        switch (method) {
            case UPNP -> Upnp.deletePortMapping(upnp, p.port(), p.protocol());
            case NATPMP -> {
                NatPmp.map(natPmpGateway, p.port(), "TCP".equals(p.protocol()), 0);
                NATPMP_OPEN.remove(p.port() + "/" + p.protocol());
            }
            case NONE -> { }
        }
    }

    /** Cierra todo lo que abrió BlockHost. @return cuántos se cerraron */
    public int closeAllOurs() throws IOException {
        int n = 0;
        IOException last = null;
        for (Port p : list()) {
            if (!p.ours()) continue;
            try {
                close(p);
                n++;
            } catch (IOException e) {
                last = e;
            }
        }
        if (n == 0 && last != null) throw last;
        return n;
    }

    private static synchronized void startRenewer(String gateway) {
        if (renewer != null) return;
        renewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "natpmp-renew-ports");
            t.setDaemon(true);
            return t;
        });
        renewer.scheduleWithFixedDelay(() -> {
            for (Port p : NATPMP_OPEN.values()) NatPmp.map(gateway, p.port(), "TCP".equals(p.protocol()), NATPMP_LIFETIME);
        }, NATPMP_LIFETIME / 2, NATPMP_LIFETIME / 2, TimeUnit.SECONDS);
    }

    public static String validate(int port) {
        return port < 1 || port > 65535 ? "El puerto tiene que estar entre 1 y 65535." : null;
    }
}
