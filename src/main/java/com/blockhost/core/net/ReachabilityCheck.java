package com.blockhost.core.net;

import com.blockhost.core.Http;
import com.blockhost.core.Json;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Comprueba desde internet si se llega al servidor: le pide a un servicio público de estado de Minecraft que lo
 * consulte. Así se sabe si el puerto está abierto aunque el router no tenga UPnP (por ejemplo, abierto a mano).
 * Desde este PC no se puede probar contra la propia IP pública: muchos routers no lo permiten (hairpin NAT).
 */
public final class ReachabilityCheck {

    public enum Result { REACHABLE, UNREACHABLE, UNKNOWN }

    /** Servicio y cómo construir su URL. Los dos guardan la respuesta en caché alrededor de un minuto. */
    record Service(String name, String urlFormat) {
        String url(String host, int port) { return urlFormat.formatted(host, port); }
    }

    static final List<Service> SERVICES = List.of(
            new Service("mcstatus.io", "https://api.mcstatus.io/v2/status/java/%s:%d"),
            new Service("mcsrvstat.us", "https://api.mcsrvstat.us/3/%s:%d"));

    /** Para los tests: devuelve el cuerpo de la respuesta o lanza si no hay conexión. */
    interface Fetcher { String get(String url) throws Exception; }

    /** El resultado y qué servicio lo dio. */
    public record Outcome(Result result, String service) { }

    private ReachabilityCheck() { }

    /** Tarda unos segundos: llamar desde un hilo de fondo. El servidor tiene que estar ya en marcha. */
    public static Outcome check(String publicIp, int port) {
        return check(publicIp, port, url -> Http.getText(url, Duration.ofSeconds(10)));
    }

    static Outcome check(String publicIp, int port, Fetcher fetcher) {
        if (publicIp == null) return new Outcome(Result.UNKNOWN, null);
        Outcome negative = null;
        for (Service s : SERVICES) {
            Result r;
            try {
                r = parseOnline(fetcher.get(s.url(publicIp, port)));
            } catch (Exception e) {
                r = Result.UNKNOWN;
            }
            if (r == Result.REACHABLE) return new Outcome(r, s.name());
            if (r == Result.UNREACHABLE && negative == null) negative = new Outcome(r, s.name());
            // un "no" se confirma con el otro servicio, por si el primero tenía un resultado viejo en caché
        }
        return negative != null ? negative : new Outcome(Result.UNKNOWN, null);
    }

    /** Ambos servicios responden {"online": true|false, ...}. */
    static Result parseOnline(String json) {
        Object o = Json.parse(json);
        if (!(o instanceof Map<?, ?>)) return Result.UNKNOWN;
        Object online = ((Map<?, ?>) o).get("online");
        if (online instanceof Boolean b) return b ? Result.REACHABLE : Result.UNREACHABLE;
        return Result.UNKNOWN;
    }
}
