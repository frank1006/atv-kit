package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class RecentAppsTest {
    private static final Set<String> LAUNCHABLE = new HashSet<>(Arrays.asList("a.tube", "b.stream", "c.vlc", "d.old"));

    private static Map<String, Long> used() {
        Map<String, Long> m = new HashMap<>();
        m.put("a.tube", 3_000L);
        m.put("b.stream", 5_000L);
        m.put("c.vlc", 4_000L);
        m.put("d.old", 500L);              // last used before the TV started
        m.put("com.android.systemui", 6_000L);   // not launchable
        m.put("com.gathin.tvhome", 7_000L);      // the launcher itself
        return m;
    }

    @Test
    public void mostRecentFirst_onlySinceBoot_launchable_notSelf() {
        List<String> shown = RecentApps.order(used(), Collections.emptyMap(), 1_000L, LAUNCHABLE, "com.gathin.tvhome", 10);
        assertEquals(Arrays.asList("b.stream", "c.vlc", "a.tube"), shown);
    }

    @Test
    public void closedApp_staysHiddenUntilUsedAgain() {
        Map<String, Long> closed = new HashMap<>();
        closed.put("b.stream", 5_500L);   // closed after its last use: hidden
        closed.put("c.vlc", 3_500L);      // used again after it was closed: shown
        List<String> shown = RecentApps.order(used(), closed, 1_000L, LAUNCHABLE, "com.gathin.tvhome", 10);
        assertEquals(Arrays.asList("c.vlc", "a.tube"), shown);
    }

    @Test
    public void limit() {
        List<String> shown = RecentApps.order(used(), Collections.emptyMap(), 1_000L, LAUNCHABLE, "com.gathin.tvhome", 2);
        assertEquals(Arrays.asList("b.stream", "c.vlc"), shown);
    }
}
