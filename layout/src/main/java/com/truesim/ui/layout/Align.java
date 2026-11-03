package com.truesim.ui.layout;

/**
 * Alignment of an item in its line or cell ({@code align-items}, {@code align-self}, {@code justify-items},
 * {@code justify-self}); same order as ui-style's {@code Align}. {@link #BASELINE} aligns like {@link #START}.
 */
public enum Align {
    /** The container's {@code align-items} (or {@code justify-items}). */
    AUTO,
    START,
    END,
    CENTER,
    STRETCH,
    BASELINE
}
