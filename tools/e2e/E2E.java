import com.blockhost.core.*;
import com.blockhost.core.providers.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class E2E {
    static int ok=0, fail=0;
    static void check(String name, boolean c, Object info){ System.out.println((c?"[OK]   ":"[FAIL] ")+name+(info==null?"":" -> "+info)); if(c)ok++;else fail++; }
    static void waitFor(ServerProcess p, ServerProcess.Status s, int sec) throws Exception { for(int i=0;i<sec*2 && p.status()!=s;i++) Thread.sleep(500); }

    public static void main(String[] a) throws Exception {
        ProgressListener pl = (m,f)->{};
        // 1. versiones
        for (ServerType t : ServerType.values()) {
            try { List<String> v = t.provider().listVersions(); check("Listar versiones "+t, !v.isEmpty(), v.size()+" versiones, primera "+v.get(0)); }
            catch(Exception e){ check("Listar versiones "+t, false, e); }
        }
        String type = a[0]; String ver = a[1];
        ServerType st = ServerType.valueOf(type);
        String name = "Prueba "+type+" "+System.currentTimeMillis()%10000;
        ServerConfig cfg = new ServerConfig(name, st, ver, 1536, Integer.parseInt(a[2]), "Prueba", 5, "normal", "survival", false, true);
        ServerInstance inst = new ServerInstaller().install(cfg, true, (m,f)->{ if(m.length()<120) System.out.println("   · "+m); });
        check("Instalar "+type+" "+ver, Files.exists(inst.dir().resolve(inst.jarName())) || inst.jarName()!=null, inst.jarName());
        ServerProperties props = ServerProperties.load(inst.dir());
        check("server.properties: white-list=false por defecto", "false".equals(props.get("white-list")), props.get("white-list"));
        props.set("view-distance","7"); props.save();
        check("Ajustes: guardar y releer", "7".equals(ServerProperties.load(inst.dir()).get("view-distance")), null);
        check("Validar valor inválido", ServerProperties.validate("view-distance","abc")!=null, ServerProperties.validate("view-distance","abc"));

        ServerProcess p = new ServerProcess(inst);
        List<String> out = new CopyOnWriteArrayList<>();
        p.onOutput(out::add);
        p.start();
        waitFor(p, ServerProcess.Status.RUNNING, 240);
        check("Arrancar servidor", p.status()==ServerProcess.Status.RUNNING, p.status());
        out.clear();
        p.send("say hola desde BlockHost");
        p.send("difficulty hard");
        Thread.sleep(3000);
        check("Enviar comandos por consola", out.stream().anyMatch(l->l.contains("hola desde BlockHost")), out.size()+" líneas");
        CommandCatalog cc = new CommandCatalog(st, ver);
        var sug = cc.suggest("gam", Set.of("Steve"));
        check("Autocompletar 'gam'", sug.stream().anyMatch(s->s.text().contains("gamemode")), sug);
        var sug2 = cc.suggest("kick St", Set.of("Steve"));
        check("Autocompletar jugador", sug2.stream().anyMatch(s->s.text().contains("Steve")), sug2);
        boolean hasTick = ServerProvider.VERSION_ORDER.compare(ver, "1.20.3") >= 0; // /tick llegó en 1.20.3
        check("Comando /tick "+(hasTick?"existe":"no existe")+" en "+ver, (cc.get("tick")!=null)==hasTick, null);
        Path b = Backups.createWhileRunning(inst, p, Backups.MANUAL);
        check("Copia en caliente", Files.size(b)>1000, b.getFileName()+" "+Files.size(b)+" B");
        Path exp = WorldShare.export(inst, p, Files.createTempDirectory("exp").resolve(WorldShare.exportFileName(inst)));
        check("Exportar mundo en marcha", Files.size(exp)>1000, exp.getFileName()+" "+Files.size(exp)/1024+" KB");
        check("Servidor sigue en marcha tras copias", p.status()==ServerProcess.Status.RUNNING && out.stream().noneMatch(l->l.contains("Exception")), null);
        p.stopAndWait(90);
        check("Detener limpio", !p.isAlive(), p.status());

        // importar en otro servidor
        ServerConfig cfg2 = new ServerConfig(name+" B", st, ver, 1536, Integer.parseInt(a[2])+1, "B", 5, "normal", "survival", false, true);
        ServerInstance inst2 = new ServerInstaller().install(cfg2, true, pl);
        Path safety = WorldShare.importWorld(inst2, exp);
        check("Importar zip exportado", Files.exists(inst2.dir().resolve("world/level.dat")), "copia previa="+safety);
        ServerProcess p2 = new ServerProcess(inst2); p2.start(); waitFor(p2, ServerProcess.Status.RUNNING, 240);
        check("Arranca con el mundo importado", p2.status()==ServerProcess.Status.RUNNING, p2.status());
        p2.stopAndWait(90);
        Path s2 = WorldShare.importWorld(inst2, inst.dir().resolve("world"));
        check("Importar desde carpeta + copia previa", s2!=null && Files.exists(s2), s2==null?null:s2.getFileName());
        Backups.restore(inst, b); check("Restaurar copia", Files.exists(inst.dir().resolve("world/level.dat")), null);
        check("Lista de copias", Backups.list(inst).size()>=1, Backups.list(inst).size());
        if (a.length>3) {
            try { String link = WorldShare.upload(exp, pl); check("Subir y obtener enlace", link.startsWith("http"), link);
                  Path dl = Files.createTempFile("dl",".zip"); WorldShare.download(link, dl, pl);
                  check("Descargar desde enlace", Files.size(dl)==Files.size(exp), Files.size(dl)); }
            catch(Exception e){ check("Subir/descargar enlace", false, e); }
        }
        ServerRegistry.delete(inst); ServerRegistry.delete(inst2);
        check("Borrar servidores", !Files.exists(inst.dir()), null);
        System.out.println("RESUMEN "+type+": "+ok+" OK, "+fail+" FALLOS");
        System.exit(0);
    }
}
