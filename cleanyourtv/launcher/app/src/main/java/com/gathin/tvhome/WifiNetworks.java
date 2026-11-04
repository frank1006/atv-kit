package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rows of the Wi-Fi drop-down (wifi_menu): the connected network first, then saved networks in range, then the
 * other networks around, each group strongest first. A network name seen on several access points is listed once,
 * with its strongest signal; hidden networks (no name) are left out.
 */
final class WifiNetworks {
    /** Security types, numbered as TV settings' Wi-Fi screens number them (AccessPoint.SECURITY_*). */
    static final int NONE = 0, WEP = 1, PSK = 2, EAP = 3;

    private WifiNetworks() {
    }

    static final class Network {
        final String ssid;
        /** Signal bars, 0 to 3. */
        final int level;
        /** NONE, WEP, PSK or EAP. */
        final int security;
        final boolean saved;
        final boolean connected;

        Network(String ssid, int level, int security, boolean saved, boolean connected) {
            this.ssid = ssid;
            this.level = level;
            this.security = security;
            this.saved = saved;
            this.connected = connected;
        }
    }

    /** One scan result, as Android reports it. */
    static final class Seen {
        final String ssid;
        final int level;
        final int security;

        Seen(String ssid, int level, int security) {
            this.ssid = ssid;
            this.level = level;
            this.security = security;
        }
    }

    /**
     * @param seen      the latest scan results
     * @param connected the connected network's name, or null
     * @param saved     the names of the networks saved on the TV
     * @param limit     the most rows to return; the connected network always has one
     */
    static List<Network> order(List<Seen> seen, String connected, Set<String> saved, int limit) {
        Map<String, Seen> strongest = new LinkedHashMap<>();
        for (Seen s : seen) {
            if (s.ssid == null || s.ssid.isEmpty()) continue;
            Seen had = strongest.get(s.ssid);
            if (had == null || s.level > had.level) strongest.put(s.ssid, s);
        }
        List<Network> rows = new ArrayList<>();
        for (Seen s : strongest.values()) {
            rows.add(new Network(s.ssid, s.level, s.security, saved.contains(s.ssid), s.ssid.equals(connected)));
        }
        // Connected before saved before the rest; within a group, stronger first, then by name.
        Collections.sort(rows, (a, b) -> {
            if (a.connected != b.connected) return a.connected ? -1 : 1;
            if (a.saved != b.saved) return a.saved ? -1 : 1;
            if (a.level != b.level) return Integer.compare(b.level, a.level);
            return a.ssid.compareToIgnoreCase(b.ssid);
        });
        return rows.subList(0, Math.min(limit, rows.size()));
    }

    /** A scan result's security, from its capabilities ("[WPA2-PSK-CCMP][ESS]"); WPA3's SAE asks for a password too. */
    static int security(String capabilities) {
        if (capabilities == null) return NONE;
        if (capabilities.contains("EAP")) return EAP;
        if (capabilities.contains("PSK") || capabilities.contains("SAE")) return PSK;
        if (capabilities.contains("WEP")) return WEP;
        return NONE;
    }

    /** A saved network's name without the quotes Android keeps around it. */
    static String unquote(String ssid) {
        if (ssid == null) return null;
        return ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"") ? ssid.substring(1, ssid.length() - 1) : ssid;
    }
}
