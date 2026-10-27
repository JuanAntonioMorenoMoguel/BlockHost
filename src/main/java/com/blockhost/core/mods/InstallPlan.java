package com.blockhost.core.mods;

import java.util.List;

/**
 * Lo que se va a descargar para instalar un mod.
 *
 * @param items    versiones elegidas: el mod pedido y sus dependencias obligatorias que faltaban
 * @param problems impedimentos (sin versión compatible, incompatible con algo instalado...)
 * @param notes    avisos que no impiden instalar
 */
public record InstallPlan(List<Item> items, List<String> problems, List<String> notes) {

    /** @param requiredBy título del mod que lo necesita, o null si es el que pidió el usuario */
    public record Item(ModRepository.Project project, ModRepository.Version version, String requiredBy) {
        @Override
        public String toString() {
            String s = project.title() + " " + version.versionNumber();
            return requiredBy == null ? s : s + "  (lo necesita " + requiredBy + ")";
        }
    }
}
