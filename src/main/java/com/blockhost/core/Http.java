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

    // PaperMC (Fill v3) exige un User-Agent identificable.
    public static final String USER_AGENT = "BlockHost/0.1 (gestor de servidores de Minecraft)";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private Http() { }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
    }

    public static Object getJson(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = CLIENT.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " al consultar " + url);
        return Json.parse(r.body());
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
