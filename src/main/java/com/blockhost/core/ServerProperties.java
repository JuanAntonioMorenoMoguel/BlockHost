package com.blockhost.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * server.properties de un servidor: leer, cambiar y guardar (ordenado por clave, como lo deja Minecraft),
 * con una explicación en español de cada opción conocida para la pantalla de ajustes avanzados.
 */
public final class ServerProperties {

    public static final String FILE = "server.properties";

    /** Tipo de valor, para validar lo que se escribe a mano. */
    public enum Kind { BOOL, INT, TEXT }

    /** @param min @param max solo para INT */
    public record Info(String label, String help, Kind kind, long min, long max) { }

    private static final Map<String, Info> KNOWN = new LinkedHashMap<>();

    private static void text(String key, String label, String help) { KNOWN.put(key, new Info(label, help, Kind.TEXT, 0, 0)); }

    private static void bool(String key, String label, String help) { KNOWN.put(key, new Info(label, help, Kind.BOOL, 0, 0)); }

    private static void num(String key, String label, String help, long min, long max) {
        KNOWN.put(key, new Info(label, help, Kind.INT, min, max));
    }

    static {
        text("motd", "Mensaje del servidor", "Texto que se ve en la lista de servidores.");
        num("server-port", "Puerto", "Puerto TCP en el que escucha el servidor.", 1, 65535);
        text("server-ip", "IP en la que escuchar", "Déjalo vacío para escuchar en todas las IPs del PC.");
        num("max-players", "Jugadores máximos", "Cuántos pueden estar conectados a la vez.", 0, 2147483647);
        text("difficulty", "Dificultad", "peaceful, easy, normal o hard.");
        text("gamemode", "Modo de juego", "survival, creative, adventure o spectator.");
        bool("force-gamemode", "Forzar modo de juego", "Pone el modo de juego por defecto a cada jugador al entrar.");
        bool("hardcore", "Extremo (hardcore)", "Al morir pasas a espectador. Dificultad difícil fija.");
        bool("pvp", "PvP", "Los jugadores pueden hacerse daño entre ellos.");
        bool("online-mode", "Solo cuentas oficiales", "Comprueba con Mojang que cada jugador tiene Minecraft original.");
        bool("white-list", "Lista blanca", "Solo pueden entrar los jugadores de la lista blanca.");
        bool("enforce-whitelist", "Aplicar lista blanca al recargar", "Echa a quien no esté en la lista cuando se recarga.");
        num("view-distance", "Distancia de renderizado", "Chunks que se envían a cada jugador (3–32). Más = más RAM y CPU.", 3, 32);
        num("simulation-distance", "Distancia de simulación", "Chunks alrededor del jugador en los que hay vida (3–32).", 3, 32);
        num("entity-broadcast-range-percentage", "Distancia de entidades (%)",
                "A qué distancia se ven mobs y objetos, en % de la normal (10–1000).", 10, 1000);
        num("spawn-protection", "Protección del spawn",
                "Radio en bloques alrededor del spawn que solo pueden tocar los operadores (0 = sin protección).", 0, 2147483647);
        bool("allow-flight", "Permitir volar", "Evita que se eche a quien vuela (necesario con algunos mods).");
        bool("allow-nether", "Permitir el Nether", "Si se puede ir al Nether.");
        bool("spawn-monsters", "Monstruos", "Si aparecen monstruos.");
        bool("spawn-animals", "Animales", "Si aparecen animales (versiones antiguas).");
        bool("spawn-npcs", "Aldeanos", "Si aparecen aldeanos (versiones antiguas).");
        bool("generate-structures", "Generar estructuras", "Aldeas, templos... en el mundo nuevo.");
        text("level-name", "Carpeta del mundo", "Nombre de la carpeta del mundo. Si lo cambias se crea uno nuevo.");
        text("level-seed", "Semilla", "Semilla del mundo nuevo. Vacío = aleatoria.");
        text("level-type", "Tipo de mundo", "minecraft:normal, minecraft:flat, minecraft:large_biomes, minecraft:amplified...");
        text("generator-settings", "Ajustes del generador", "JSON para mundos personalizados (por ejemplo superplanos).");
        num("max-world-size", "Tamaño máximo del mundo", "Radio máximo en bloques.", 1, 29999984);
        num("max-build-height", "Altura máxima de construcción", "Solo versiones antiguas.", 0, 4096);
        num("player-idle-timeout", "Echar por inactividad (min)", "Minutos sin moverse antes de echar al jugador (0 = nunca).", 0, 2147483647);
        num("op-permission-level", "Nivel de los operadores", "1–4: qué comandos pueden usar los operadores.", 1, 4);
        num("function-permission-level", "Nivel de las funciones", "1–4: permisos de las funciones de datapacks.", 1, 4);
        bool("enable-command-block", "Bloques de comandos", "Si funcionan los bloques de comandos.");
        bool("allow-cheats", "Permitir trucos", "Solo algunas versiones.");
        bool("enable-status", "Aparecer en la lista", "Si el servidor responde en la lista de servidores.");
        bool("hide-online-players", "Ocultar jugadores conectados", "No muestra quién está conectado en la lista de servidores.");
        bool("enable-query", "GameSpy Query", "Protocolo para webs que muestran el estado del servidor.");
        num("query.port", "Puerto de Query", "Puerto UDP de GameSpy Query.", 1, 65535);
        bool("enable-rcon", "RCON", "Control remoto por red. Actívalo solo si sabes lo que haces.");
        num("rcon.port", "Puerto RCON", "Puerto TCP de RCON.", 1, 65535);
        text("rcon.password", "Contraseña RCON", "Contraseña de RCON.");
        bool("broadcast-rcon-to-ops", "Avisar a ops de RCON", "Enseña a los operadores los comandos enviados por RCON.");
        bool("broadcast-console-to-ops", "Avisar a ops de la consola", "Enseña a los operadores los comandos de la consola.");
        bool("enable-jmx-monitoring", "JMX", "Expone métricas del servidor por JMX.");
        bool("sync-chunk-writes", "Escritura síncrona de chunks", "Más seguro ante cortes de luz, algo más lento.");
        num("network-compression-threshold", "Compresión de red", "Tamaño desde el que se comprimen paquetes (-1 = nunca).", -1, 2147483647);
        num("rate-limit", "Límite de paquetes", "Paquetes por segundo antes de echar a un jugador (0 = sin límite).", 0, 2147483647);
        num("max-tick-time", "Tiempo máximo por tick (ms)", "Si un tick tarda más, el servidor se detiene (-1 = nunca).", -1, Long.MAX_VALUE);
        num("max-chained-neighbor-updates", "Actualizaciones en cadena", "Límite de actualizaciones de bloques encadenadas.", -1, 2147483647);
        bool("use-native-transport", "Transporte nativo (Linux)", "Optimización de red en Linux.");
        bool("prevent-proxy-connections", "Bloquear proxies/VPN", "Echa a quien entre por un proxy distinto del de su cuenta.");
        bool("enforce-secure-profile", "Perfil seguro (chat firmado)", "Exige claves de chat firmadas de Mojang.");
        bool("log-ips", "Guardar IPs en el log", "Escribe la IP de cada jugador en el registro.");
        text("resource-pack", "Paquete de recursos (URL)", "Enlace directo a un .zip que se ofrece a los jugadores.");
        text("resource-pack-sha1", "SHA-1 del paquete", "Huella del paquete de recursos.");
        text("resource-pack-prompt", "Mensaje del paquete", "Texto que se enseña al ofrecer el paquete.");
        bool("require-resource-pack", "Paquete obligatorio", "Echa a quien no acepte el paquete de recursos.");
        text("initial-enabled-packs", "Datapacks activos al crear", "Datapacks activados al crear el mundo.");
        text("initial-disabled-packs", "Datapacks desactivados al crear", "Datapacks desactivados al crear el mundo.");
        text("text-filtering-config", "Filtro de texto", "Configuración del filtro de chat.");
        bool("accepts-transfers", "Aceptar transferencias", "Permite que otros servidores te manden jugadores (/transfer).");
        num("pause-when-empty-seconds", "Pausar sin jugadores (s)", "Segundos sin nadie antes de pausar el mundo (0 = nunca).", -1, 2147483647);
    }

    private final Path file;
    private final Map<String, String> values = new TreeMap<>();

    private ServerProperties(Path file) { this.file = file; }

    public static ServerProperties load(Path serverDir) throws IOException {
        ServerProperties sp = new ServerProperties(serverDir.resolve(FILE));
        if (Files.isRegularFile(sp.file)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(sp.file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            for (String k : p.stringPropertyNames()) sp.values.put(k, p.getProperty(k));
        }
        return sp;
    }

    public String get(String key) { return values.get(key); }

    public String get(String key, String def) { return values.getOrDefault(key, def); }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(values.getOrDefault(key, "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        String v = values.get(key);
        return v == null ? def : "true".equalsIgnoreCase(v.trim());
    }

    public void set(String key, String value) { values.put(key, value == null ? "" : value); }

    public void setIfAbsent(String key, String value) { values.putIfAbsent(key, value); }

    public void remove(String key) { values.remove(key); }

    public Map<String, String> asMap() { return Collections.unmodifiableMap(values); }

    /** Todas las claves: las del fichero y las conocidas aunque no estén (para poder añadirlas). */
    public Set<String> allKeys() {
        Set<String> keys = new LinkedHashSet<>(values.keySet());
        keys.addAll(KNOWN.keySet());
        return new java.util.TreeSet<>(keys);
    }

    public static Info info(String key) { return KNOWN.get(key); }

    /** null si el valor vale; si no, el motivo. */
    public static String validate(String key, String value) {
        Info i = KNOWN.get(key);
        if (i == null || value == null) return null;
        String v = value.trim();
        return switch (i.kind()) {
            case BOOL -> v.equals("true") || v.equals("false") ? null : "Tiene que ser true o false.";
            case INT -> {
                if (v.isEmpty()) yield null;
                try {
                    long n = Long.parseLong(v);
                    yield n < i.min() || n > i.max() ? "Tiene que estar entre " + i.min() + " y " + i.max() + "." : null;
                } catch (NumberFormatException e) {
                    yield "Tiene que ser un número.";
                }
            }
            case TEXT -> null;
        };
    }

    public void save() throws IOException {
        Properties p = new Properties() {
            @Override
            public Set<Map.Entry<Object, Object>> entrySet() {
                Set<Map.Entry<Object, Object>> sorted = new java.util.TreeSet<>(
                        Comparator.comparing((Map.Entry<Object, Object> e) -> e.getKey().toString()));
                sorted.addAll(super.entrySet());
                return sorted;
            }
        };
        values.forEach(p::setProperty);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "Minecraft server properties (BlockHost)");
        }
    }
}
