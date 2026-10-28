package com.blockhost.core.mods;

import java.util.List;

/** Un sitio de donde descargar mods y modpacks (hoy, Modrinth) con un modelo común. */
public interface ModRepository {

    String id();          // "modrinth"

    String displayName();

    /**
     * Busca mods o modpacks compatibles con el loader y la versión de Minecraft (null = cualquiera).
     *
     * @param projectType "mod" o "modpack"
     */
    List<Project> search(String query, String projectType, String loader, String mcVersion, int limit) throws Exception;

    /** Proyecto por id (o slug en Modrinth); null si no existe. */
    Project project(String id) throws Exception;

    /** Versiones para ese loader y versión de Minecraft (null = todas), de más nueva a más antigua. */
    List<Version> versions(String projectId, String loader, String mcVersion) throws Exception;

    Version version(String projectId, String versionId) throws Exception;

    /** La más reciente de tipo release; si no hay ninguna, la más reciente (beta/alpha). */
    static Version best(List<Version> versions) {
        for (Version v : versions) if ("release".equals(v.versionType())) return v;
        return versions.isEmpty() ? null : versions.get(0);
    }

    // ---------------- modelo común ----------------

    /**
     * @param clientOnly el servidor no lo carga
     * @param clientToo  los jugadores también necesitan instalarlo
     */
    record Project(String repo, String id, String slug, String title, String description, String author,
                   long downloads, String projectType, boolean clientOnly, boolean clientToo,
                   String iconUrl, String pageUrl) {
        @Override
        public String toString() { return title; }
    }

    /** @param url null si no hay enlace de descarga */
    record File(String url, String fileName, String sha1, String sha512, long size) { }

    /** @param type "required", "optional", "incompatible" o "embedded" */
    record Dependency(String projectId, String versionId, String type) { }

    /** @param versionType "release", "beta" o "alpha" */
    record Version(String repo, String id, String projectId, String versionNumber, String versionType,
                   List<String> gameVersions, List<String> loaders, List<Dependency> dependencies, File file) {

        public List<Dependency> dependencies(String type) {
            return dependencies.stream().filter(d -> type.equals(d.type())).toList();
        }
    }
}
