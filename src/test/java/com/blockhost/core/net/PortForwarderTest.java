package com.blockhost.core.net;

import com.blockhost.core.net.PortForwarder.State;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortForwarderTest {

    @Test
    void detectsCgnatAndDoubleNat() {
        assertEquals(State.OPEN, PortForwarder.classify("UPnP", "85.1.2.3", "85.1.2.3", "192.168.1.34", 25565, "ok").state());
        assertEquals(State.OPEN, PortForwarder.classify("UPnP", "85.1.2.3", null, "192.168.1.34", 25565, "ok").state());
        assertEquals(State.CGNAT, PortForwarder.classify("UPnP", "100.72.10.4", "85.1.2.3", "192.168.1.34", 25565, "ok").state());
        assertEquals(State.DOUBLE_NAT, PortForwarder.classify("UPnP", "192.168.0.5", "85.1.2.3", "192.168.1.34", 25565, "ok").state());
        assertEquals(State.CGNAT, PortForwarder.classify("UPnP", "85.1.2.3", "90.4.4.4", "192.168.1.34", 25565, "ok").state());
        assertTrue(PortForwarder.isCgnat("100.64.0.1"));
        assertFalse(PortForwarder.isCgnat("100.128.0.1"));
        assertTrue(PortForwarder.isPrivate("172.20.1.1"));
        assertFalse(PortForwarder.isPrivate("172.32.1.1"));
    }

    @Test
    void shareAddressOmitsDefaultPort() {
        var s = PortForwarder.classify("UPnP", "85.1.2.3", "85.1.2.3", "192.168.1.34", 25565, "ok");
        assertEquals("85.1.2.3", s.shareAddress());
        var other = PortForwarder.classify("UPnP", "85.1.2.3", "85.1.2.3", "192.168.1.34", 25570, "ok");
        assertEquals("85.1.2.3:25570", other.shareAddress());
    }

    @Test
    void parsesSystemRoutes() {
        String win = """
                IPv4 Route Table
                ===========================================================================
                Active Routes:
                Network Destination        Netmask          Gateway       Interface  Metric
                          0.0.0.0          0.0.0.0      192.168.1.1    192.168.1.34     25
                ===========================================================================
                """;
        assertEquals(List.of("192.168.1.1"), NatPmp.parseWindowsRoutes(win));
        String linux = "Iface\tDestination\tGateway \tFlags\nwlan0\t00000000\t0101A8C0\t0003\nwlan0\t0001A8C0\t00000000\t0001\n";
        assertEquals(List.of("192.168.1.1"), NatPmp.parseLinuxRoutes(linux));
    }

    @Test
    void guideHasThePcDataFilledIn() {
        var c = new ConnectionGuide.Context("192.168.1.34", "192.168.1.1", "85.1.2.3", 25570, false);
        var methods = ConnectionGuide.methods(c);
        assertEquals(4, methods.size());
        String manual = String.join("\n", methods.get(0).host());
        assertTrue(manual.contains("http://192.168.1.1"));
        assertTrue(manual.contains("Protocolo: TCP"));
        assertTrue(manual.contains("Puerto externo (o público): 25570"));
        assertTrue(manual.contains("IP del equipo (o IP local / destino): 192.168.1.34"));
        assertTrue(String.join("\n", methods.get(0).players()).contains("85.1.2.3:25570"));
        assertTrue(String.join("\n", methods.get(1).host()).contains("Local Port: 25570"));

        // con CG-NAT abrir el puerto no sirve: va al final y lo avisa
        var cg = ConnectionGuide.methods(new ConnectionGuide.Context("192.168.1.34", null, null, 25565, true));
        assertTrue(cg.get(0).title().contains("playit"));
        assertTrue(cg.get(3).note().contains("CG-NAT"));
    }
}
