package com.blockhost.core;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /** Texto corto con un tiempo máximo (para consultas rápidas, como la IP pública). */
    public static String getText(String url, Duration timeout) throws IOException, InterruptedException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).timeout(timeout).GET().build();
        HttpResponse<String> resp = CLIENT.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(resp, url);
        return resp.body();
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
                    + ".");
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

    /**
     * Sube un fichero en un formulario multipart (campos de texto + el fichero) y devuelve el cuerpo de la respuesta.
     * El fichero se envía en streaming, sin cargarlo en memoria, con el avance en p.
     */
    public static String uploadFile(String url, Map<String, String> fields, String fileField, Path file, String label,
                                    ProgressListener p) throws IOException, InterruptedException {
        String boundary = "----BlockHost" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder head = new StringBuilder();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            head.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(f.getKey()).append("\"\r\n\r\n")
                    .append(f.getValue()).append("\r\n");
        }
        String name = file.getFileName().toString().replace("\"", "");
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(fileField).append("\"; filename=\"")
                .append(name).append("\"\r\n")
                .append("Content-Type: application/zip\r\n\r\n");
        byte[] before = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] after = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long size = Files.size(file);
        long total = before.length + size + after.length;

        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        InputStream counted = new FilterInputStream(Files.newInputStream(file)) {
                            private long done, lastReport;

                            @Override
                            public int read(byte[] b, int off, int len) throws IOException {
                                int n = super.read(b, off, len);
                                if (n > 0) {
                                    done += n;
                                    if (done - lastReport > 512 * 1024 || done == size) {
                                        lastReport = done;
                                        p.onProgress(String.format("%s (%.1f de %.1f MB)", label, done / 1048576.0,
                                                size / 1048576.0), size > 0 ? (double) done / size : -1);
                                    }
                                }
                                return n;
                            }
                        };
                        return new SequenceInputStream(Collections.enumeration(List.of(
                                new ByteArrayInputStream(before), counted, new ByteArrayInputStream(after))));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }), total);
        HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofHours(3)) // mundos grandes con poca subida
                .POST(body)
                .build();
        HttpResponse<String> resp = CLIENT.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(resp, url);
        return resp.body();
    }
}
