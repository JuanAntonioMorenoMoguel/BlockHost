package com.blockhost.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operadores, lista blanca y baneados de un servidor (ops.json, whitelist.json, banned-players.json).
 *
 * <p>Con el servidor en marcha se cambian con comandos (op, whitelist add, ban...), porque el servidor sobrescribe
 * estos ficheros. Detenido, BlockHost edita los ficheros directamente y el servidor los lee al arrancar.
 */
public final class PlayerLists {

    public enum Kind {
        OPS("ops.json", "Operadores"),
        WHITELIST("whitelist.json", "Lista blanca"),
        BANNED("banned-players.json", "Baneados");

        public final String file;
        public final String label;

        Kind(String file, String label) {
            this.file = file;
            this.label = label;
        }
    }

    /** @param detail motivo del baneo o nivel de operador, para mostrarlo */
    public record Entry(String name, String uuid, String detail) { }

    private static final DateTimeFormatter BAN_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");
    private static final String MOJANG_PROFILE = "https://api.mojang.com/users/profiles/minecraft/";

    private final Path dir;
    private final boolean onlineMode;

    public PlayerLists(ServerInstance inst) { this(inst.dir(), inst.config().onlineMode()); }

    PlayerLists(Path dir, boolean onlineMode) {
        this.dir = dir;
        this.onlineMode = onlineMode;
    }

    public List<Entry> read(Kind kind) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (Map<String, Object> m : raw(kind)) {
            String detail = switch (kind) {
                case OPS -> "nivel " + Json.num(m.get("level"), 4);
                case BANNED -> Json.str(m.get("reason"));
                case WHITELIST -> null;
            };
            out.add(new Entry(Json.str(m.get("name")), Json.str(m.get("uuid")), detail));
        }
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.name()), String.valueOf(b.name())));
        return out;
    }

    public boolean contains(Kind kind, String name) throws IOException {
        return read(kind).stream().anyMatch(e -> name.equalsIgnoreCase(e.name()));
    }

    /** Añade a la lista editando el fichero (servidor detenido). Busca el UUID del jugador. */
    public void add(Kind kind, String name, String reason) throws Exception {
        checkName(name);
        if (contains(kind, name)) return;
        String[] profile = profile(name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", profile[0]);
        m.put("name", profile[1]);
        switch (kind) {
            case OPS -> {
                m.put("level", 4);
                m.put("bypassesPlayerLimit", false);
            }
            case BANNED -> {
                m.put("created", ZonedDateTime.now().format(BAN_DATE));
                m.put("source", "BlockHost");
                m.put("expires", "forever");
                m.put("reason", reason == null || reason.isBlank() ? "Baneado por un operador." : reason.trim());
            }
            case WHITELIST -> { }
        }
        List<Map<String, Object>> list = raw(kind);
        list.add(m);
        write(kind, list);
    }

    /** Quita de la lista editando el fichero (servidor detenido). */
    public void remove(Kind kind, String name) throws IOException {
        List<Map<String, Object>> list = raw(kind);
        list.removeIf(m -> name.equalsIgnoreCase(Json.str(m.get("name"))));
        write(kind, list);
    }

    /** Comando equivalente para el servidor en marcha. */
    public static String command(Kind kind, boolean add, String name, String reason) {
        return switch (kind) {
            case OPS -> (add ? "op " : "deop ") + name;
            case WHITELIST -> "whitelist " + (add ? "add " : "remove ") + name;
            case BANNED -> add ? "ban " + name + (reason == null || reason.isBlank() ? "" : " " + reason.trim()) : "pardon " + name;
        };
    }

    // ---------------- lista blanca activada ----------------

    public boolean whitelistEnabled() throws IOException {
        return ServerProperties.load(dir).getBool("white-list", false);
    }

    /** Activa la lista blanca en server.properties (con enforce: echa a quien no esté al recargar). */
    public void setWhitelistEnabled(boolean on) throws IOException {
        ServerProperties p = ServerProperties.load(dir);
        p.set("white-list", String.valueOf(on));
        p.set("enforce-whitelist", String.valueOf(on));
        p.save();
    }

    // ---------------- UUID ----------------

    /** {uuid, nombre con sus mayúsculas}. En modo online se pregunta a Mojang; en offline se calcula como el servidor. */
    String[] profile(String name) throws Exception {
        if (!onlineMode) return new String[]{offlineUuid(name), name};
        Object o = Http.getJsonOrNull(MOJANG_PROFILE + name);
        if (o == null) throw new IllegalArgumentException("No existe ninguna cuenta de Minecraft llamada «" + name + "».");
        Map<String, Object> m = Json.obj(o);
        return new String[]{dashed(Json.str(m.get("id"))), Json.str(m.get("name"))};
    }

    static String offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String dashed(String hex) {
        if (hex == null || hex.length() != 32) return hex;
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    /**
     * Las cuentas oficiales usan 3–16 letras, números o _, pero en modo offline, con Geyser (".Nombre") o con
     * algunos mods hay otros nombres. Solo se rechaza lo que rompería el comando: espacios o vacío.
     */
    public static void checkName(String name) {
        if (name == null || !name.matches("\\S{1,32}")) {
            throw new IllegalArgumentException("Escribe el nombre del jugador tal cual sale en el juego, sin espacios.");
        }
    }

    // ---------------- ficheros ----------------

    private List<Map<String, Object>> raw(Kind kind) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        Path f = dir.resolve(kind.file);
        if (!Files.isRegularFile(f)) return out;
        String text = Files.readString(f, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return out;
        try {
            for (Object o : Json.arr(Json.parse(text))) {
                if (o instanceof Map<?, ?>) out.add(new LinkedHashMap<>(Json.obj(o)));
            }
        } catch (IllegalArgumentException e) {
            throw new IOException(kind.file + " está dañado: " + e.getMessage());
        }
        return out;
    }

    private void write(Kind kind, List<Map<String, Object>> list) throws IOException {
        Files.writeString(dir.resolve(kind.file), Json.write(new ArrayList<Object>(list)), StandardCharsets.UTF_8);
    }
}
