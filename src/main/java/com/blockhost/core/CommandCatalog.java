package com.blockhost.core;

import com.blockhost.core.providers.ServerProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Comandos de consola que existen en una versión del juego, para autocompletar.
 *
 * <p>Parte de una lista propia (con la versión en la que apareció o desapareció cada comando y una sintaxis
 * sencilla) y, con el servidor en marcha, aprende los que faltan de la salida de "help": así salen también
 * los comandos de mods y datapacks.
 *
 * <p>Sintaxis: una palabra por argumento. {@code a|b|c} son valores fijos; {@code <jugador>} y
 * {@code <objetivos>} se completan con los conectados; el resto de {@code <...>} solo se enseña como pista.
 */
public final class CommandCatalog {

    /** @param since primera versión con el comando (null = siempre) @param until primera versión sin él (null = sigue) */
    public record Command(String name, String syntax, String since, String until) {
        boolean existsIn(String mc) {
            return (since == null || cmp(mc, since) >= 0) && (until == null || cmp(mc, until) < 0);
        }
    }

    /** Una sugerencia: el texto a escribir y una pista para enseñar al lado. */
    public record Suggestion(String text, String hint) { }

    private static final List<Command> ALL = new ArrayList<>();

    private static void c(String name, String syntax) { ALL.add(new Command(name, syntax, null, null)); }

    private static void c(String name, String syntax, String since, String until) {
        ALL.add(new Command(name, syntax, since, until));
    }

    private static final String MODES = "survival|creative|adventure|spectator";
    private static final String GAMERULES = "keepInventory|doDaylightCycle|doWeatherCycle|doMobSpawning|mobGriefing"
            + "|doFireTick|naturalRegeneration|showDeathMessages|doImmediateRespawn|playersSleepingPercentage"
            + "|announceAdvancements|fallDamage|fireDamage|drowningDamage|doInsomnia|doPatrolSpawning"
            + "|doTraderSpawning|doMobLoot|doTileDrops|doEntityDrops|commandBlockOutput|sendCommandFeedback"
            + "|spawnRadius|randomTickSpeed|maxEntityCramming|disableRaids|reducedDebugInfo";

    static {
        // administración
        c("help", "[<comando>]");
        c("list", "uuids");
        c("stop", "");
        c("save-all", "flush");
        c("save-on", "");
        c("save-off", "");
        c("reload", "", "1.12", null);
        c("op", "<jugador>");
        c("deop", "<jugador>");
        c("kick", "<jugador> [<motivo>]");
        c("ban", "<jugador> [<motivo>]");
        c("ban-ip", "<jugador> [<motivo>]");
        c("banlist", "players|ips");
        c("pardon", "<jugador>");
        c("pardon-ip", "<ip>");
        c("whitelist", "on|off|add|remove|list|reload <jugador>");
        c("seed", "");
        c("debug", "start|stop");
        c("publish", "", null, null);
        c("transfer", "<host> [<puerto>] [<objetivos>]", "1.20.5", null);
        c("jfr", "start|stop", "1.18", null);
        c("perf", "start|stop", "1.17", null);
        c("tick", "query|rate|freeze|unfreeze|step|sprint", "1.20.3", null);

        // chat
        c("say", "<mensaje>");
        c("me", "<acción>");
        c("tell", "<jugador> <mensaje>");
        c("msg", "<jugador> <mensaje>");
        c("w", "<jugador> <mensaje>");
        c("tellraw", "<objetivos> <json>");
        c("title", "<objetivos> title|subtitle|actionbar|clear|reset|times");
        c("teammsg", "<mensaje>", "1.14", null);
        c("tm", "<mensaje>", "1.14", null);

        // jugadores
        c("gamemode", MODES + " <objetivos>");
        c("defaultgamemode", MODES);
        c("difficulty", "peaceful|easy|normal|hard");
        c("give", "<objetivos> <objeto> [<cantidad>]");
        c("clear", "<objetivos> [<objeto>]");
        c("kill", "<objetivos>");
        c("tp", "<objetivos> <destino>");
        c("teleport", "<objetivos> <destino>");
        c("spawnpoint", "<objetivos> [<x y z>]");
        c("xp", "add|set|query <objetivos> <cantidad>");
        c("experience", "add|set|query <objetivos> <cantidad>", "1.13", null);
        c("effect", "give|clear <objetivos> <efecto>", "1.13", null);
        c("effect", "<jugador> <efecto> [<segundos>]", null, "1.13");
        c("enchant", "<objetivos> <encantamiento> [<nivel>]");
        c("advancement", "grant|revoke <objetivos> everything|only|from|through|until", "1.12", null);
        c("achievement", "give|take <logro> <jugador>", null, "1.12");
        c("recipe", "give|take <objetivos> <receta>", "1.12", null);
        c("attribute", "<objetivo> <atributo> get|base|modifier", "1.16", null);
        c("spectate", "<objetivo> [<jugador>]", "1.15", null);
        c("ride", "<objetivo> mount|dismount", "1.19.4", null);
        c("damage", "<objetivo> <cantidad>", "1.19.4", null);
        c("item", "replace|modify <objetivos>", "1.17", null);
        c("replaceitem", "block|entity", "1.13", "1.17");
        c("trigger", "<objetivo>");
        c("rotate", "<objetivo> <rotación>", "1.21.2", null);

        // mundo
        c("time", "set|add|query day|noon|night|midnight");
        c("weather", "clear|rain|thunder");
        c("toggledownfall", "", null, "1.13");
        c("gamerule", GAMERULES + " true|false");
        c("setworldspawn", "[<x y z>]");
        c("worldborder", "set|add|center|damage|warning|get");
        c("summon", "<entidad> [<x y z>]");
        c("setblock", "<x y z> <bloque>");
        c("fill", "<desde> <hasta> <bloque>");
        c("clone", "<desde> <hasta> <destino>");
        c("locate", "structure|biome|poi", "1.19", null);
        c("locate", "<estructura>", "1.11", "1.19");
        c("locatebiome", "<bioma>", "1.16", "1.19");
        c("place", "feature|jigsaw|structure|template", "1.19", null);
        c("fillbiome", "<desde> <hasta> <bioma>", "1.19.3", null);
        c("forceload", "add|remove|query", "1.14", null);
        c("spreadplayers", "<x z> <distancia> <rango> <respetar equipos> <objetivos>");
        c("playsound", "<sonido> <fuente> <objetivos>");
        c("stopsound", "<objetivos>", "1.10", null);
        c("particle", "<partícula>");
        c("loot", "give|insert|spawn|replace", "1.14", null);
        c("schedule", "function|clear", "1.14", null);

        // datos y scoreboard
        c("scoreboard", "objectives|players");
        c("team", "add|remove|empty|join|leave|list|modify", "1.13", null);
        c("tag", "<objetivos> add|remove|list", "1.13", null);
        c("bossbar", "add|remove|list|set|get", "1.13", null);
        c("data", "get|merge|modify|remove", "1.13", null);
        c("datapack", "enable|disable|list", "1.13", null);
        c("function", "<función>", "1.12", null);
        c("execute", "as|at|if|unless|in|positioned|run", "1.13", null);
        c("execute", "<objetivo> <x y z> <comando>", null, "1.13");
        c("random", "value|roll|reset", "1.20.2", null);
        c("return", "<valor>", "1.20.2", null);
        c("testfor", "<jugador>", null, "1.13");
        c("testforblock", "<x y z> <bloque>", null, "1.13");
        c("testforblocks", "<desde> <hasta> <destino>", null, "1.13");
        c("blockdata", "<x y z> <nbt>", null, "1.13");
        c("entitydata", "<objetivo> <nbt>", null, "1.13");
        c("stats", "block|entity", null, "1.13");
    }

    /** Comandos que añade cada tipo de servidor por su cuenta. */
    private static List<Command> extras(ServerType type) {
        return switch (type) {
            case PAPER -> List.of(new Command("plugins", "", null, null), new Command("pl", "", null, null),
                    new Command("version", "[<plugin>]", null, null), new Command("tps", "", null, null),
                    new Command("mspt", "", null, null), new Command("paper", "version|reload|heap|entity", null, null),
                    new Command("spigot", "", null, null), new Command("restart", "", null, null));
            case FORGE -> List.of(new Command("forge", "tps|track|entity|generate|dimensions|mods", null, null));
            case NEOFORGE -> List.of(new Command("neoforge", "tps|track|entity|generate|dimensions|mods", null, null));
            default -> List.of();
        };
    }

    private static final List<String> SELECTORS = List.of("@a", "@p", "@r", "@s", "@e");

    private final Map<String, Command> known = new TreeMap<>();
    private final Map<String, Command> learned = new ConcurrentSkipListMap<>();

    public CommandCatalog(ServerType type, String mcVersion) {
        for (Command c : ALL) if (c.existsIn(mcVersion)) known.put(c.name(), c);
        for (Command c : extras(type)) known.putIfAbsent(c.name(), c);
    }

    /** Nombres de todos los comandos conocidos para esta versión, en orden. */
    public List<String> names() {
        TreeMap<String, Command> all = new TreeMap<>(learned);
        all.putAll(known);
        return List.copyOf(all.keySet());
    }

    public Command get(String name) {
        Command c = known.get(name);
        return c != null ? c : learned.get(name);
    }

    /**
     * Aprende un comando de una línea de "help" ("/gamerule &lt;rule&gt; ..."). Devuelve true si era una línea de
     * comando (aunque ya se conociera).
     */
    public boolean learn(String helpLine) {
        String line = helpLine.trim();
        if (!line.startsWith("/") || line.length() < 2) return false;
        int sp = line.indexOf(' ');
        String name = (sp < 0 ? line.substring(1) : line.substring(1, sp)).toLowerCase(Locale.ROOT);
        if (name.endsWith(":")) name = name.substring(0, name.length() - 1); // formato de Bukkit "/cmd: descripción"
        if (name.isEmpty() || !name.matches("[\\w:.-]+")) return false;
        if (!known.containsKey(name)) {
            String syntax = sp < 0 || helpLine.contains(": ") ? "" : simplify(line.substring(sp + 1));
            learned.put(name, new Command(name, syntax, null, null));
        }
        return true;
    }

    /** Pasa la sintaxis de Brigadier ("(add|remove) [&lt;targets&gt;]") a la nuestra ("add|remove &lt;targets&gt;"). */
    static String simplify(String brigadier) {
        List<String> out = new ArrayList<>();
        for (String t : brigadier.trim().split("\\s+")) {
            if (t.isEmpty() || t.equals("->") || t.contains("...")) break;
            String s = t;
            while (s.length() > 1 && (s.startsWith("(") || s.startsWith("["))) s = s.substring(1);
            while (s.length() > 1 && (s.endsWith(")") || s.endsWith("]"))) s = s.substring(0, s.length() - 1);
            out.add(s);
        }
        return String.join(" ", out);
    }

    /**
     * Sugerencias para lo escrito hasta ahora (con o sin "/" delante).
     *
     * @param players los conectados, para los argumentos de jugador
     */
    public List<Suggestion> suggest(String typed, Collection<String> players) {
        String text = typed.startsWith("/") ? typed.substring(1) : typed;
        if (text.isBlank()) return List.of();
        String[] parts = text.split(" ", -1);
        String last = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        List<Suggestion> out = new ArrayList<>();

        if (parts.length == 1) {
            for (String name : names()) {
                if (name.startsWith(last) && !name.equals(last)) out.add(new Suggestion(name, get(name).syntax()));
            }
            return out;
        }

        Command cmd = get(parts[0].toLowerCase(Locale.ROOT));
        if (cmd == null || cmd.syntax().isBlank()) return out;
        String[] args = cmd.syntax().split(" ");
        int argIndex = parts.length - 2;
        if (argIndex >= args.length) return out;
        String arg = args[argIndex];
        String bare = arg.replaceAll("^[\\[<]+|[\\]>]+$", "");

        List<String> options;
        if (!arg.startsWith("<") && !arg.startsWith("[") && arg.contains("|")) options = List.of(arg.split("\\|"));
        else if (!arg.startsWith("<") && !arg.startsWith("[")) options = List.of(arg);
        else if (isPlayerArg(bare)) {
            options = new ArrayList<>(players.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
            if (!bare.equals("jugador") && !bare.equals("player")) options.addAll(SELECTORS);
        } else {
            return List.of(new Suggestion("", arg)); // solo la pista
        }
        String rest = String.join(" ", java.util.Arrays.copyOfRange(args, argIndex + 1, args.length));
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(last) && !o.equalsIgnoreCase(last)) out.add(new Suggestion(o, rest));
        }
        return out;
    }

    private static boolean isPlayerArg(String bare) {
        return switch (bare) {
            case "jugador", "player", "objetivos", "objetivo", "targets", "target", "destino", "destination" -> true;
            default -> false;
        };
    }

    /** Aplica una sugerencia: cambia la última palabra por ella y deja un espacio para seguir. */
    public static String apply(String typed, Suggestion s) {
        if (s.text().isEmpty()) return typed;
        int sp = typed.lastIndexOf(' ');
        String head = sp < 0 ? (typed.startsWith("/") ? "/" : "") : typed.substring(0, sp + 1);
        return head + s.text() + " ";
    }

    private static int cmp(String a, String b) { return ServerProvider.VERSION_ORDER.compare(a, b); }
}
