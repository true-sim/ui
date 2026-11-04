package com.truesim.ui.console;

import java.util.List;

/**
 * Where {@code exec} reads console scripts (the engine: the game's config folder or its virtual file system): one
 * command line per line, {@code //} comments.
 */
public interface ScriptSource {

    /** The text of script {@code path}, or null when there is none. */
    String read(String path);

    /** Adds the script paths starting with {@code prefix} to {@code out} (for completion); none by default. */
    default void list(String prefix, List<String> out) {
    }
}
