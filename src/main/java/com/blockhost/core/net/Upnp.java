package com.blockhost.core.net;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente UPnP IGD mínimo: busca el router por SSDP y le pide abrir o cerrar un puerto
 * (WANIPConnection o WANPPPConnection, versiones 1 y 2).
 */
public final class Upnp {

    /** El servicio del router que gestiona los puertos. localIp es la IP de este PC que lo ve. */
    public record Gateway(String controlUrl, String serviceType, String localIp, String friendlyName) { }

    /** Error devuelto por el router (código UPnP: 718 = el puerto ya lo usa otro equipo, 725 = solo permanentes...). */
    public static final class UpnpException extends IOException {
        public final int code;

        UpnpException(int code, String description) {
            super(errorText(code, description));
            this.code = code;
        }
    }

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final List<String> SEARCH_TARGETS = List.of(
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1");
    private static final Pattern LOCATION = Pattern.compile("(?im)^location:\\s*(\\S+)\\s*$");

    private Upnp() { }

    /**
     * Busca el router desde cada IP local (así se usa la tarjeta de red buena aunque haya VPN o máquinas virtuales).
     *
     * @return el primero que responda con un servicio de puertos, o null
     */
    public static Gateway discover(List<String> localIps, int timeoutMs) {
        Set<String> seen = new LinkedHashSet<>();
        for (String local : localIps) {
            for (String location : search(local, timeoutMs)) {
                if (!seen.add(location)) continue;
                try {
                    Gateway g = describe(location, local);
                    if (g != null) return g;
                } catch (Exception ignored) {
                    // otro dispositivo UPnP (tele, impresora...): seguir buscando
                }
            }
        }
        return null;
    }

    private static List<String> search(String localIp, int timeoutMs) {
        List<String> found = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(localIp), 0))) {
            s.setSoTimeout(300);
            InetAddress group = InetAddress.getByName(SSDP_ADDR);
            for (String st : SEARCH_TARGETS) {
                byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                s.send(new DatagramPacket(msg, msg.length, group, SSDP_PORT));
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    if (!found.isEmpty()) break; // ya ha contestado alguien y no llegan más
                    continue;
                }
                Matcher m = LOCATION.matcher(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                if (m.find() && !found.contains(m.group(1))) found.add(m.group(1));
            }
        } catch (IOException ignored) {
            // esa IP no sirve para multicast
        }
        return found;
    }

    /** Lee la descripción del dispositivo y busca el servicio de puertos. Paquete para los tests. */
    static Gateway describe(String location, String localIp) throws Exception {
        byte[] xml;
        HttpURLConnection c = open(location);
        try (InputStream in = c.getInputStream()) {
            xml = in.readAllBytes();
        }
        return parseDescription(xml, location, localIp);
    }

    static Gateway parseDescription(byte[] xml, String location, String localIp) throws Exception {
        Document doc = parse(xml);
        String base = text(doc.getDocumentElement(), "URLBase");
        if (base == null || base.isBlank()) base = location;
        String name = text(doc.getDocumentElement(), "friendlyName");
        NodeList services = doc.getElementsByTagNameNS("*", "service");
        Gateway ppp = null;
        for (int i = 0; i < services.getLength(); i++) {
            Element s = (Element) services.item(i);
            String type = text(s, "serviceType");
            String control = text(s, "controlURL");
            if (type == null || control == null) continue;
            String url = URI.create(base.trim()).resolve(control.trim()).toString();
            if (type.contains(":WANIPConnection:")) return new Gateway(url, type.trim(), localIp, name);
            if (type.contains(":WANPPPConnection:") && ppp == null) ppp = new Gateway(url, type.trim(), localIp, name);
        }
        return ppp;
    }

    // ---------------- acciones ----------------

    public static String externalIp(Gateway g) throws IOException {
        Document d = soap(g, "GetExternalIPAddress", new String[0][]);
        String ip = firstText(d, "NewExternalIPAddress");
        return ip == null || ip.isBlank() ? null : ip.trim();
    }

    /** @param protocol "TCP" o "UDP" @param leaseSeconds 0 = hasta que se borre */
    public static void addPortMapping(Gateway g, int port, String protocol, String description, int leaseSeconds)
            throws IOException {
        soap(g, "AddPortMapping", new String[][]{
                {"NewRemoteHost", ""},
                {"NewExternalPort", String.valueOf(port)},
                {"NewProtocol", protocol},
                {"NewInternalPort", String.valueOf(port)},
                {"NewInternalClient", g.localIp()},
                {"NewEnabled", "1"},
                {"NewPortMappingDescription", description},
                {"NewLeaseDuration", String.valueOf(leaseSeconds)}});
    }

    public static void deletePortMapping(Gateway g, int port, String protocol) throws IOException {
        soap(g, "DeletePortMapping", new String[][]{
                {"NewRemoteHost", ""},
                {"NewExternalPort", String.valueOf(port)},
                {"NewProtocol", protocol}});
    }

    /** Una apertura de puerto que tiene el router. */
    public record Mapping(int externalPort, String protocol, String internalClient, int internalPort, boolean enabled,
                          String description, int leaseSeconds) { }

    /** Lista lo abierto en el router (GetGenericPortMappingEntry, de uno en uno hasta que no haya más). */
    public static List<Mapping> listPortMappings(Gateway g) throws IOException {
        List<Mapping> out = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            Document d;
            try {
                d = soap(g, "GetGenericPortMappingEntry", new String[][]{{"NewPortMappingIndex", String.valueOf(i)}});
            } catch (UpnpException e) {
                if (i == 0 && e.code != 713 && e.code != 714) throw e; // 713: no hay ninguna
                break; // fin de la lista (cada router la termina con un error distinto)
            }
            out.add(new Mapping(num(firstText(d, "NewExternalPort")), trim(firstText(d, "NewProtocol")),
                    trim(firstText(d, "NewInternalClient")), num(firstText(d, "NewInternalPort")),
                    !"0".equals(trim(firstText(d, "NewEnabled"))), trim(firstText(d, "NewPortMappingDescription")),
                    num(firstText(d, "NewLeaseDuration"))));
        }
        return out;
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static int num(String s) {
        try {
            return Integer.parseInt(trim(s));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A qué IP local apunta ya ese puerto en el router (null si no está abierto). */
    public static String mappedClient(Gateway g, int port, String protocol) throws IOException {
        try {
            Document d = soap(g, "GetSpecificPortMappingEntry", new String[][]{
                    {"NewRemoteHost", ""},
                    {"NewExternalPort", String.valueOf(port)},
                    {"NewProtocol", protocol}});
            return firstText(d, "NewInternalClient");
        } catch (UpnpException e) {
            if (e.code == 714) return null; // NoSuchEntryInArray
            throw e;
        }
    }

    private static Document soap(Gateway g, String action, String[][] args) throws IOException {
        StringBuilder body = new StringBuilder("<?xml version=\"1.0\"?>\r\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + g.serviceType() + "\">");
        for (String[] a : args) body.append('<').append(a[0]).append('>').append(escape(a[1])).append("</").append(a[0]).append('>');
        body.append("</u:").append(action).append("></s:Body></s:Envelope>");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = open(g.controlUrl());
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"" + g.serviceType() + "#" + action + "\"");
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] resp = in == null ? new byte[0] : in.readAllBytes();
        if (in != null) in.close();
        Document d;
        try {
            d = parse(resp);
        } catch (Exception e) {
            throw new IOException("El router respondió algo que no se entiende (HTTP " + status + ").");
        }
        if (status >= 400) {
            String code = firstText(d, "errorCode");
            int n;
            try {
                n = code == null ? status : Integer.parseInt(code.trim());
            } catch (NumberFormatException e) {
                n = status;
            }
            throw new UpnpException(n, firstText(d, "errorDescription"));
        }
        return d;
    }

    static String errorText(int code, String description) {
        String what = switch (code) {
            case 402, 606 -> "El router no deja cambiar puertos por UPnP (puede que esté limitado en sus ajustes).";
            case 713 -> "No hay más puertos abiertos.";
            case 714 -> "Ese puerto no estaba abierto.";
            case 718 -> "El router ya tiene ese puerto abierto hacia otro equipo de tu red.";
            case 724 -> "El router exige que el puerto interno y el externo sean iguales.";
            case 725 -> "El router solo admite aperturas permanentes.";
            case 727, 728 -> "El router no admite más aperturas de puertos.";
            default -> "El router rechazó la petición (error " + code + ").";
        };
        return description == null || description.isBlank() ? what : what + " [" + description.trim() + "]";
    }

    // ---------------- utilidades ----------------

    /** Conexión directa (sin el proxy del sistema: el router está en la red local). */
    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        return c;
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // el XML viene de la red: sin DTD ni entidades externas
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        f.setXIncludeAware(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static String text(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) return e.getTextContent();
        }
        NodeList all = parent.getElementsByTagNameNS("*", localName);
        return all.getLength() == 0 ? null : all.item(0).getTextContent();
    }

    private static String firstText(Document d, String localName) {
        NodeList l = d.getElementsByTagNameNS("*", localName);
        return l.getLength() == 0 ? null : l.item(0).getTextContent();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
