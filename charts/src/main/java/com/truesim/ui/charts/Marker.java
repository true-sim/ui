package com.truesim.ui.charts;

/** A marker's shape (the style key {@code marker}): boxes, triangles or lines, outlined and filled as styled. */
public enum Marker {
    /** No marker. */
    NONE,
    /** A disc. */
    CIRCLE,
    /** A square. */
    SQUARE,
    /** A square on its corner. */
    DIAMOND,
    /** A triangle pointing up. */
    UP,
    /** A triangle pointing down. */
    DOWN,
    /** A triangle pointing left. */
    LEFT,
    /** A triangle pointing right. */
    RIGHT,
    /** A diagonal cross (lines). */
    CROSS,
    /** A plus (lines). */
    PLUS,
    /** Three crossed lines. */
    ASTERISK
}
