package com.truesim.ui.console;

import java.util.List;

/**
 * The assets {@link Arg#asset} arguments name (the engine's asset database): completion and a check that a typed
 * path exists. Called on events (a key typed, a command run), on the UI thread.
 */
public interface AssetProvider {

    /** Adds the paths of assets of {@code type} starting with {@code prefix} to {@code out}, best first. */
    void complete(String type, String prefix, List<String> out);

    /** Whether an asset of {@code type} has path {@code path}. */
    default boolean exists(String type, String path) {
        return true;
    }
}
