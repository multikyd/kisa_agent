package com.kisapilot.kisaagent;

import java.util.*;

/** Durable queue policy, independent of Android and the network. */
final class AutoBookmarkPolicy {
    static void observe(Set<String> seen, Set<String> pending, Map<String, Boolean> active, boolean initialized) {
        for (Map.Entry<String, Boolean> item : active.entrySet()) {
            String id = item.getKey();
            if (!id.matches("[A-Za-z0-9_-]+--[0-9]+")) continue;
            if (!seen.contains(id)) {
                seen.add(id);
                if (initialized || item.getValue()) pending.add(id);
            }
        }
    }
    // Call only after a complete, successful bookmark-list response. Network failures
    // are not evidence that a source was deleted. A transient omission gets two retries.
    static Set<String> discardMissing(Set<String> pending, Set<String> available,
                                     Map<String, Integer> missingPolls) {
        Set<String> removed = new HashSet<>();
        missingPolls.keySet().retainAll(pending);
        for (String id : new ArrayList<>(pending)) {
            if (available.contains(id)) {
                missingPolls.remove(id);
            } else {
                int count = Math.min(3, missingPolls.getOrDefault(id, 0) + 1);
                missingPolls.put(id, count);
                if (count >= 3) {
                    pending.remove(id);
                    missingPolls.remove(id);
                    removed.add(id);
                }
            }
        }
        return removed;
    }
    static boolean canTransfer(String relative) {
        return relative != null && !DriveLogFiles.isLock(relative);
    }
}
