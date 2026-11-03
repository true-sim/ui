package com.truesim.ui.layout;

/**
 * The unit of a length. The first four are ui-style's {@code Length.Unit} in the same order; {@link #FR} is for grid
 * tracks only.
 */
public enum Unit {
    /** Panel units (scaled to UI pixels by {@code ui.scale} and the content scale). */
    PX,
    /** Multiples of the element's own font size ({@link Layout#fontSize}). */
    EM,
    /** Percent (50 is half) of the parent's content box on the same axis; auto when that is not definite. */
    PERCENT,
    /** From the content, or none for minimum and maximum sizes. */
    AUTO,
    /** A share of a grid's leftover space (a track's maximum only). */
    FR
}
