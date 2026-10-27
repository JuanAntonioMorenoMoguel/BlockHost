package com.blockhost.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Huellas de ficheros para verificar descargas e identificar mods. */
public final class Hashes {

    private Hashes() { }

    public static String sha1(Path file) throws IOException { return hash(file, "SHA-1"); }

    public static String sha512(Path file) throws IOException { return hash(file, "SHA-512"); }

    private static String hash(Path file, String algorithm) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Falla si la huella no coincide (descarga corrupta o manipulada) y borra el fichero. */
    public static void verify(Path file, String expectedSha1, String expectedSha512) throws IOException {
        String bad = null;
        if (expectedSha512 != null && !expectedSha512.equalsIgnoreCase(sha512(file))) bad = "SHA-512";
        else if (expectedSha1 != null && !expectedSha1.equalsIgnoreCase(sha1(file))) bad = "SHA-1";
        if (bad != null) {
            Files.deleteIfExists(file);
            throw new IOException("La descarga de " + file.getFileName() + " está corrupta (" + bad + " no coincide)");
        }
    }
}
