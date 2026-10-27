package com.blockhost.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** Peticiones HTTP: JSON y descargas con progreso. */
public final class Http {

    // PaperMC (Fill v3) y Modrinth exigen un User-Agent que identifique la app.
    public static final String USER_AGENT =
            "BlockHost/0.1 (+https://github.com/JuanAntonioMorenoMoguel/BlockHost; gestor de servidores de Minecraft)";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private Http() { }

    private static HttpRequest request(String url, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(5))
                .GET();
        if (headers.length > 0) b.headers(headers);
        return b.build();
    }

    /** @param headers pares nombre, valor (p. ej. la clave de una API) */
    public static Object getJson(String url, String... headers) throws IOException, InterruptedException {
        Object o = getJsonOrNull(url, headers);
        if (o == null) throw new IOException("HTTP 404 al consultar " + url);
        return o;
    }

    /** Como getJson, pero un 404 devuelve null en vez de fallar. */
    public static Object getJsonOrNull(String url, String... headers) throws IOException, InterruptedException {
        HttpResponse<String> r = CLIENT.send(request(url, headers), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() == 404) return null;
        check(r, url);
        return Json.parse(r.body());
    }

    public static Object postJson(String url, Object body, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        if (headers.length > 0) b.headers(headers);
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(r, url);
        return Json.parse(r.body());
    }

    private static void check(HttpResponse<String> r, String url) throws IOException {
        if (r.statusCode() == 200) return;
        if (r.statusCode() == 401 || r.statusCode() == 403) {
            throw new IOException("Acceso denegado (HTTP " + r.statusCode() + ") al consultar " + URI.create(url).getHost()
                    + ". Si es CurseForge, revisa la clave de API.");
        }
        throw new IOException("HTTP " + r.statusCode() + " al consultar " + url);
    }

    /** Descarga a un fichero temporal y lo mueve al destino al terminar (nunca deja ficheros a medias). */
    public static void download(String url, Path target, String label, ProgressListener p)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> r = CLIENT.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            r.body().close();
            throw new IOException("HTTP " + r.statusCode() + " al descargar " + url);
        }
        long total = r.headers().firstValueAsLong("Content-Length").orElse(-1);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0, lastReport = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                out.write(buf, 0, n);
                done += n;
                if (done - lastReport > 512 * 1024) {
                    lastReport = done;
                    p.onProgress(String.format("%s (%.1f MB)", label, done / 1048576.0),
                            total > 0 ? (double) done / total : -1);
                }
            }
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        p.onProgress(label + " completado", 1);
    }
}
