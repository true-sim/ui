package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the console keeps its command history between runs, per app id (the engine: a file in the user's settings
 * folder). {@link #memory()} keeps it for the process only (tests, tools).
 */
public interface HistoryStore {

    /** The lines saved for {@code appId}, oldest first (empty when none). */
    List<String> load(String appId);

    /** Saves {@code lines} (oldest first) for {@code appId}; called after each command run. */
    void save(String appId, List<String> lines);

    /** A store in memory. */
    @AllowAllocation("one per console")
    static HistoryStore memory() {
        Map<String, List<String>> saved = new HashMap<>();
        return new HistoryStore() {
            @Override
            public List<String> load(String appId) {
                return saved.getOrDefault(appId, List.of());
            }

            @Override
            @AllowAllocation("saved on a command run")
            public void save(String appId, List<String> lines) {
                saved.put(appId, new ArrayList<>(lines));
            }
        };
    }
}
