package com.truesim.ui.console;

import java.util.List;

/**
 * The scene nodes {@link Arg#node} arguments name (the engine's scene): completion by name or path and a check that a
 * typed one exists. Called on events, on the UI thread.
 */
public interface NodeProvider {

    /** Adds the names or paths of nodes starting with {@code prefix} to {@code out}, best first. */
    void complete(String prefix, List<String> out);

    /** Whether a node is named {@code name}. */
    default boolean exists(String name) {
        return true;
    }
}
