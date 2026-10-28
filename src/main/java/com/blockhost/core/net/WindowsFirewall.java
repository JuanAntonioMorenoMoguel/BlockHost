package com.blockhost.core.net;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Regla de entrada del Firewall de Windows para el puerto del servidor. Crearla necesita permisos de
 * administrador, así que se lanza netsh con la ventana de Windows que los pide (UAC).
 */
public final class WindowsFirewall {

    private WindowsFirewall() { }

    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Sin espacios, para no pelear con las comillas al pasarlo a netsh. */
    public static String ruleName(int port) { return "BlockHost-Minecraft-" + port + "-TCP"; }

    /** Si ya hay una regla de BlockHost para ese puerto (consultarlo no necesita permisos). */
    public static boolean hasRule(int port) {
        if (!supported()) return false;
        try {
            String out = NatPmp.run("netsh", "advfirewall", "firewall", "show", "rule", "name=" + ruleName(port));
            return out.contains(ruleName(port));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Crea la regla pidiendo permiso de administrador. Espera a que el usuario conteste.
     *
     * @return true si la regla existe al terminar
     */
    public static boolean addRule(int port) throws Exception {
        if (!supported()) return false;
        String script = "Start-Process netsh -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList "
                + "'advfirewall firewall add rule name=" + ruleName(port) + " dir=in action=allow protocol=TCP localport="
                + port + " profile=any'";
        // codificado para que Windows no toque las comillas por el camino
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        NatPmp.run("powershell", "-NoProfile", "-EncodedCommand", encoded);
        return hasRule(port);
    }
}
