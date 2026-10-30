package com.blockhost.core.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Un router de mentira en localhost que habla UPnP IGD. */
class UpnpTest {

    private static final String DESC = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
                <friendlyName>Router de prueba</friendlyName>
                <serviceList><service>
                  <serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
                  <controlURL>/l3f</controlURL>
                </service></serviceList>
                <deviceList><device>
                  <deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType>
                  <deviceList><device>
                    <deviceType>urn:schemas-upnp-org:device:WANConnectionDevice:1</deviceType>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType>
                        <controlURL>/ppp</controlURL>
                      </service>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
                        <controlURL>/ctl/IPConn</controlURL>
                      </service>
                    </serviceList>
                  </device></deviceList>
                </device></deviceList>
              </device>
            </root>
            """;

    private HttpServer server;
    private final List<String> calls = new ArrayList<>();
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/desc.xml", ex -> reply(ex, 200, DESC));
        server.createContext("/ctl/IPConn", ex -> {
            String action = ex.getRequestHeaders().getFirst("SOAPAction");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(action + " " + body);
            if (action.contains("GetExternalIPAddress")) {
                reply(ex, 200, envelope("<u:GetExternalIPAddressResponse xmlns:u=\"x\"><NewExternalIPAddress>85.1.2.3"
                        + "</NewExternalIPAddress></u:GetExternalIPAddressResponse>"));
            } else if (action.contains("AddPortMapping") && body.contains("<NewExternalPort>25570<")) {
                reply(ex, 500, envelope("<s:Fault><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                        + "<errorCode>718</errorCode><errorDescription>ConflictInMappingEntry</errorDescription>"
                        + "</UPnPError></detail></s:Fault>"));
            } else if (action.contains("GetGenericPortMappingEntry")) {
                if (body.contains("<NewPortMappingIndex>0<")) {
                    reply(ex, 200, envelope("<u:R xmlns:u=\"x\"><NewRemoteHost></NewRemoteHost><NewExternalPort>25565"
                            + "</NewExternalPort><NewProtocol>TCP</NewProtocol><NewInternalPort>25565</NewInternalPort>"
                            + "<NewInternalClient>192.168.1.34</NewInternalClient><NewEnabled>1</NewEnabled>"
                            + "<NewPortMappingDescription>BlockHost Survival</NewPortMappingDescription>"
                            + "<NewLeaseDuration>0</NewLeaseDuration></u:R>"));
                } else if (body.contains("<NewPortMappingIndex>1<")) {
                    reply(ex, 200, envelope("<u:R xmlns:u=\"x\"><NewExternalPort>3074</NewExternalPort>"
                            + "<NewProtocol>UDP</NewProtocol><NewInternalPort>3074</NewInternalPort>"
                            + "<NewInternalClient>192.168.1.50</NewInternalClient><NewEnabled>0</NewEnabled>"
                            + "<NewPortMappingDescription>Xbox</NewPortMappingDescription>"
                            + "<NewLeaseDuration>3600</NewLeaseDuration></u:R>"));
                } else {
                    reply(ex, 500, envelope("<s:Fault><detail><UPnPError><errorCode>713</errorCode></UPnPError></detail></s:Fault>"));
                }
            } else if (action.contains("GetSpecificPortMappingEntry")) {
                reply(ex, 500, envelope("<s:Fault><detail><UPnPError><errorCode>714</errorCode></UPnPError></detail></s:Fault>"));
            } else {
                reply(ex, 200, envelope(""));
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private static String envelope(String inner) {
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + inner + "</s:Body></s:Envelope>";
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    @Test
    void findsWanIpServiceAndTalksSoap() throws Exception {
        Upnp.Gateway g = Upnp.describe(base + "/desc.xml", "192.168.1.34");
        assertEquals(base + "/ctl/IPConn", g.controlUrl());
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", g.serviceType());
        assertEquals("Router de prueba", g.friendlyName());

        assertEquals("85.1.2.3", Upnp.externalIp(g));
        assertNull(Upnp.mappedClient(g, 25565, "TCP"));
        Upnp.addPortMapping(g, 25565, "TCP", "BlockHost <Prueba & co>", 0);
        String add = calls.stream().filter(c -> c.contains("#AddPortMapping")).findFirst().orElseThrow();
        assertTrue(add.contains("<NewInternalClient>192.168.1.34</NewInternalClient>"));
        assertTrue(add.contains("<NewProtocol>TCP</NewProtocol>"));
        assertTrue(add.contains("BlockHost &lt;Prueba &amp; co&gt;"));
        Upnp.deletePortMapping(g, 25565, "TCP");
        assertTrue(calls.stream().anyMatch(c -> c.contains("#DeletePortMapping")));
    }

    @Test
    void listsWhatTheRouterHasOpen() throws Exception {
        Upnp.Gateway g = Upnp.describe(base + "/desc.xml", "192.168.1.34");
        var list = Upnp.listPortMappings(g);
        assertEquals(2, list.size());
        assertEquals(new Upnp.Mapping(25565, "TCP", "192.168.1.34", 25565, true, "BlockHost Survival", 0), list.get(0));
        assertEquals(new Upnp.Mapping(3074, "UDP", "192.168.1.50", 3074, false, "Xbox", 3600), list.get(1));

        var ours = RouterPorts.toPort(list.get(0), "192.168.1.34");
        assertTrue(ours.ours() && ours.thisPc());
        var xbox = RouterPorts.toPort(list.get(1), "192.168.1.34");
        assertTrue(!xbox.ours() && !xbox.thisPc());
    }

    @Test
    void reportsRouterErrorsInSpanish() throws Exception {
        Upnp.Gateway g = Upnp.describe(base + "/desc.xml", "192.168.1.34");
        Upnp.UpnpException e = assertThrows(Upnp.UpnpException.class,
                () -> Upnp.addPortMapping(g, 25570, "TCP", "x", 0));
        assertEquals(718, e.code);
        assertTrue(e.getMessage().contains("otro equipo"));
    }
}
