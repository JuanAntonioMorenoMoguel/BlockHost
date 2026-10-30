package com.blockhost.core.net;

import com.blockhost.core.net.ReachabilityCheck.Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReachabilityCheckTest {

    @Test
    void parsesBothServices() {
        assertEquals(Result.REACHABLE, ReachabilityCheck.parseOnline("{\"online\":true,\"host\":\"85.1.2.3\",\"port\":25565}"));
        assertEquals(Result.UNREACHABLE, ReachabilityCheck.parseOnline("{\"ip\":\"85.1.2.3\",\"port\":25565,\"online\":false}"));
        assertEquals(Result.UNKNOWN, ReachabilityCheck.parseOnline("{\"error\":\"rate limited\"}"));
    }

    @Test
    void askThePublicIpAndPort() {
        List<String> urls = new ArrayList<>();
        var o = ReachabilityCheck.check("85.1.2.3", 25565, url -> { urls.add(url); return "{\"online\":true}"; });
        assertEquals(Result.REACHABLE, o.result());
        assertEquals("mcstatus.io", o.service());
        assertEquals(List.of("https://api.mcstatus.io/v2/status/java/85.1.2.3:25565"), urls);
    }

    @Test
    void aNoIsConfirmedWithTheOtherService() {
        Map<String, String> answers = Map.of(
                "https://api.mcstatus.io/v2/status/java/85.1.2.3:25570", "{\"online\":false}",
                "https://api.mcsrvstat.us/3/85.1.2.3:25570", "{\"online\":true}");
        var o = ReachabilityCheck.check("85.1.2.3", 25570, answers::get);
        assertEquals(Result.REACHABLE, o.result());
        assertEquals("mcsrvstat.us", o.service());

        var no = ReachabilityCheck.check("85.1.2.3", 25565, url -> "{\"online\":false}");
        assertEquals(Result.UNREACHABLE, no.result());
        assertEquals("mcstatus.io", no.service());
    }

    @Test
    void unknownWithoutInternetOrIp() {
        var o = ReachabilityCheck.check("85.1.2.3", 25565, url -> { throw new java.io.IOException("sin red"); });
        assertEquals(Result.UNKNOWN, o.result());
        assertNull(o.service());
        assertEquals(Result.UNKNOWN, ReachabilityCheck.check(null, 25565, url -> "{\"online\":true}").result());
    }
}
