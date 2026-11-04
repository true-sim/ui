package com.truesim.ui.controls.windows;

/**
 * Where window and dock layouts are remembered, implemented by the application: its settings, a file per profile,
 * nothing. Layouts are text ({@link WindowLayout#toText()}, {@link DockLayout#toText()}), read when a manager is given
 * the store and written when the user moved, resized, docked or closed something (never per frame).
 */
public interface LayoutStore {

    /** The text saved under {@code key}, or null if there is none. */
    String load(String key);

    /** Saves {@code text} under {@code key}. */
    void save(String key, String text);

    /** A store in memory (tests, a session without settings). */
    static LayoutStore inMemory() {
        return new MemoryLayoutStore();
    }
}
