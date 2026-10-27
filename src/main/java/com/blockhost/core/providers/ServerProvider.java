package com.blockhost.core.providers;

import com.blockhost.core.ProgressListener;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Fuente de un tipo de servidor (Vanilla, Paper, Fabric...). */
public interface ServerProvider {

    /** Versiones estables de Minecraft disponibles, de más nueva a más antigua. */
    List<String> listVersions() throws Exception;

    /** Descarga el servidor en dir y devuelve el nombre del .jar a ejecutar. */
    String install(String mcVersion, Path dir, ProgressListener p) throws Exception;

    /** "1.21.4", "26.1"... solo números y puntos (descarta snapshots, pre y rc). */
    static boolean isStableVersion(String v) {
        return v != null && v.matches("\\d+(\\.\\d+)*");
    }

    /** Compara versiones numéricamente: 1.21.10 > 1.21.9. */
    Comparator<String> VERSION_ORDER = (a, b) -> {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parse(x[i]) : 0;
            int yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    };

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
