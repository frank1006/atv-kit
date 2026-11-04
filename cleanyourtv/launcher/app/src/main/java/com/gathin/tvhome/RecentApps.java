package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which apps the recent-apps panel (SwitcherActivity) shows, and in what order. A launcher cannot read the system's
 * task list, so "recent" means used since the TV last started (UsageStats), and not closed from the panel since.
 */
final class RecentApps {
    private RecentApps() {
    }

    /**
     * @param lastUsed   package to the last time it was in front (ms since the epoch), from UsageStats
     * @param closedAt   package to the time it was closed from the panel
     * @param bootTime   when the TV started (ms since the epoch); apps last used before it are not running
     * @param launchable packages that can be opened (others, such as system UI, are not shown)
     * @param self       the launcher's own package, never shown
     * @return the packages to show, most recently used first, at most {@code limit}
     */
    static List<String> order(Map<String, Long> lastUsed, Map<String, Long> closedAt, long bootTime,
                              Set<String> launchable, String self, int limit) {
        List<Map.Entry<String, Long>> shown = new ArrayList<>();
        for (Map.Entry<String, Long> e : lastUsed.entrySet()) {
            String pkg = e.getKey();
            long used = e.getValue();
            if (pkg.equals(self) || !launchable.contains(pkg) || used < bootTime) continue;
            Long closed = closedAt.get(pkg);
            if (closed != null && closed >= used) continue;
            shown.add(e);
        }
        Collections.sort(shown, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> packages = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, shown.size()); i++) packages.add(shown.get(i).getKey());
        return packages;
    }
}
