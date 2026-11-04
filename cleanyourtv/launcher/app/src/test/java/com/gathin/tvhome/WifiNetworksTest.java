package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

public class WifiNetworksTest {
    private static List<String> names(List<WifiNetworks.Network> rows) {
        List<String> out = new ArrayList<>();
        for (WifiNetworks.Network n : rows) out.add(n.ssid);
        return out;
    }

    @Test
    public void connectedThenSavedThenOthers_strongestFirst() {
        List<WifiNetworks.Seen> seen = Arrays.asList(
                new WifiNetworks.Seen("Cafe", 3, WifiNetworks.NONE),
                new WifiNetworks.Seen("Home", 1, WifiNetworks.PSK),
                new WifiNetworks.Seen("Office", 2, WifiNetworks.PSK),
                new WifiNetworks.Seen("Neighbour", 2, WifiNetworks.PSK),
                new WifiNetworks.Seen("Attic", 3, WifiNetworks.PSK));
        List<WifiNetworks.Network> rows = WifiNetworks.order(seen, "Home",
                new HashSet<>(Arrays.asList("Home", "Office", "Attic")), 10);
        assertEquals(Arrays.asList("Home", "Attic", "Office", "Cafe", "Neighbour"), names(rows));
        assertTrue(rows.get(0).connected);
        assertTrue(rows.get(1).saved);
        assertEquals(WifiNetworks.NONE, rows.get(3).security);
    }

    @Test
    public void oneRowPerName_strongestKept_hiddenLeftOut_limit() {
        List<WifiNetworks.Seen> seen = Arrays.asList(
                new WifiNetworks.Seen("Mesh", 1, WifiNetworks.PSK),
                new WifiNetworks.Seen("Mesh", 3, WifiNetworks.PSK),
                new WifiNetworks.Seen("", 3, WifiNetworks.PSK),
                new WifiNetworks.Seen(null, 3, WifiNetworks.PSK),
                new WifiNetworks.Seen("Far", 0, WifiNetworks.PSK));
        List<WifiNetworks.Network> rows = WifiNetworks.order(seen, null, Collections.emptySet(), 1);
        assertEquals(Collections.singletonList("Mesh"), names(rows));
        assertEquals(3, rows.get(0).level);
    }

    @Test
    public void securityAndUnquote() {
        assertEquals(WifiNetworks.PSK, WifiNetworks.security("[WPA2-PSK-CCMP][ESS]"));
        assertEquals(WifiNetworks.PSK, WifiNetworks.security("[RSN-SAE-CCMP][ESS]"));
        assertEquals(WifiNetworks.EAP, WifiNetworks.security("[WPA2-EAP-CCMP][ESS]"));
        assertEquals(WifiNetworks.WEP, WifiNetworks.security("[WEP][ESS]"));
        assertEquals(WifiNetworks.NONE, WifiNetworks.security("[ESS]"));
        assertEquals("Home", WifiNetworks.unquote("\"Home\""));
        assertEquals("0x1234", WifiNetworks.unquote("0x1234"));
    }
}
