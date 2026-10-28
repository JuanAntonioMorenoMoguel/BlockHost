package com.blockhost.core.net;

import java.util.ArrayList;
import java.util.List;

/**
 * Guía para que entren amigos de fuera de casa cuando el router no deja abrir el puerto solo: pasos para quien
 * aloja el servidor y para los jugadores, con los datos de este PC ya puestos.
 */
public final class ConnectionGuide {

    /**
     * @param localIp   IP de este PC en la red de casa (null si no se sabe)
     * @param gatewayIp IP del router (null si no se sabe)
     * @param publicIp  IP de internet (null si no se sabe)
     * @param cgnat     si se ha detectado CG-NAT u otro router delante: abrir el puerto no basta
     */
    public record Context(String localIp, String gatewayIp, String publicIp, int port, boolean cgnat) { }

    /** Una forma de conectarse, con lo que hace cada uno. */
    public record Method(String title, String summary, List<String> host, List<String> players, String note) { }

    private ConnectionGuide() { }

    public static List<Method> methods(Context c) {
        List<Method> out = new ArrayList<>();
        Method manual = manual(c);
        if (!c.cgnat()) out.add(manual);
        out.add(playit(c));
        out.add(tailscale(c));
        out.add(zerotier(c));
        if (c.cgnat()) out.add(manual); // con CG-NAT solo sirve si el operador da IP pública: al final
        return out;
    }

    private static String or(String v, String fallback) { return v == null || v.isBlank() ? fallback : v; }

    private static String address(String ip, int port) { return port == 25565 ? ip : ip + ":" + port; }

    static Method manual(Context c) {
        String local = or(c.localIp(), "la IP de este PC (en Windows: ipconfig → Dirección IPv4)");
        String pub = c.publicIp() == null ? "tu IP pública (búscala en https://www.cual-es-mi-ip.net)"
                : address(c.publicIp(), c.port());
        List<String> host = List.of(
                (c.gatewayIp() == null
                        ? "Abre en el navegador la página de tu router (suele ser http://192.168.1.1 o http://192.168.0.1)."
                        : "Abre en el navegador http://" + c.gatewayIp() + " (es la página de tu router).")
                        + " El usuario y la contraseña suelen venir en una pegatina debajo del router.",
                "Busca el apartado «Puertos», «NAT», «Port forwarding», «Redirección de puertos» o «Servidores virtuales».",
                "Crea una regla nueva con estos datos:\n"
                        + "   • Nombre: Minecraft\n"
                        + "   • Protocolo: TCP\n"
                        + "   • Puerto externo (o público): " + c.port() + "\n"
                        + "   • Puerto interno (o privado): " + c.port() + "\n"
                        + "   • IP del equipo (o IP local / destino): " + local,
                "Guarda y, si lo pide, reinicia el router.",
                "Para que la IP de este PC no cambie, reserva su IP en el router (apartado «DHCP», «IP estática» o "
                        + "«Reserva de direcciones»). Si cambia, la regla deja de funcionar.",
                "En Windows, permite el puerto en el Firewall (botón «Permitir en el Firewall» del panel del servidor).",
                "Pasa a tus amigos esta dirección: " + pub + ". Comprueba que funciona en "
                        + "https://mcsrvstat.us poniendo esa dirección con el servidor en marcha.");
        List<String> players = List.of(
                "Abrir Minecraft Java con la misma versión (y los mismos mods, si los hay).",
                "Multijugador → Añadir servidor → Dirección: " + pub + ".",
                "Nada más: no tienen que instalar nada.");
        String note = c.cgnat()
                ? "Hemos detectado CG-NAT: tu operador comparte tu IP pública con otros clientes y esta regla no "
                + "llegará a funcionar. Puedes pedirle a tu operador una IP pública (muchos la dan gratis si la pides) "
                + "o usar uno de los otros métodos."
                : "La IP pública de casa puede cambiar de vez en cuando (al reiniciar el router). Si deja de "
                + "funcionar, mira la nueva en el panel del servidor.";
        return new Method("Abrir el puerto a mano en el router",
                "La forma clásica: tus amigos entran directamente, sin instalar nada.", host, players, note);
    }

    static Method playit(Context c) {
        List<String> host = List.of(
                "Entra en https://playit.gg, crea una cuenta gratis y descarga el programa para Windows.",
                "Ábrelo y sigue el enlace que te da para vincularlo con tu cuenta.",
                "En la web, «Add Tunnel» → tipo «Minecraft Java (game)» → Local Address: 127.0.0.1, Local Port: "
                        + c.port() + ".",
                "Te dará una dirección del estilo algo.joinmc.link (o una IP con puerto). Esa es la que compartes.",
                "Deja el programa de playit abierto mientras juegáis.");
        List<String> players = List.of(
                "Multijugador → Añadir servidor → Dirección: la que te dé playit (por ejemplo algo.joinmc.link).",
                "No tienen que instalar nada.");
        return new Method("Túnel con playit.gg",
                "Funciona con cualquier router, también con CG-NAT. Es lo más fácil para los jugadores.", host, players,
                "La versión gratis añade algo de latencia porque el tráfico pasa por sus servidores.");
    }

    static Method tailscale(Context c) {
        List<String> host = List.of(
                "Instala Tailscale desde https://tailscale.com/download e inicia sesión (con Google, Microsoft...).",
                "En https://login.tailscale.com/admin/machines, en este PC: «Share...» y manda el enlace a cada amigo "
                        + "(o invítalos a tu red en «Users → Invite users»).",
                "Mira la IP de este PC en Tailscale: empieza por 100. (aparece en el icono de Tailscale y en esa web).",
                "Pasa a tus amigos esa IP" + (c.port() == 25565 ? "." : " seguida de :" + c.port() + "."));
        List<String> players = List.of(
                "Instalar Tailscale, iniciar sesión y aceptar el enlace o la invitación.",
                "Con Tailscale conectado: Multijugador → Añadir servidor → Dirección: la IP 100.x.y.z del anfitrión"
                        + (c.port() == 25565 ? "." : ":" + c.port() + "."));
        return new Method("Red privada con Tailscale",
                "Como si estuvierais en la misma casa. Funciona con CG-NAT y es gratis para grupos pequeños.", host,
                players, "Todos los jugadores tienen que instalarlo y tenerlo conectado para jugar.");
    }

    static Method zerotier(Context c) {
        List<String> host = List.of(
                "Crea una cuenta en https://my.zerotier.com y pulsa «Create A Network». Copia el Network ID (16 caracteres).",
                "Instala ZeroTier desde https://www.zerotier.com/download y, en su icono, «Join New Network» con ese ID.",
                "En la web de tu red, marca la casilla «Auth» de cada equipo que se una (el tuyo y el de tus amigos).",
                "Mira la IP «Managed IP» de este PC en esa misma lista y pásala a tus amigos"
                        + (c.port() == 25565 ? "." : " con :" + c.port() + " al final."));
        List<String> players = List.of(
                "Instalar ZeroTier y unirse a la red con el Network ID que les pases.",
                "Esperar a que el anfitrión les autorice.",
                "Multijugador → Añadir servidor → Dirección: la Managed IP del anfitrión"
                        + (c.port() == 25565 ? "." : ":" + c.port() + "."));
        return new Method("Red privada con ZeroTier",
                "Parecido a Tailscale, sin iniciar sesión los jugadores. Funciona con CG-NAT.", host, players,
                "Todos tienen que tener ZeroTier abierto mientras juegan.");
    }
}
