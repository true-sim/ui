package com.truesim.ui.controls.charts;

/**
 * How the segments of a series' line meet (the style key {@code line-join}). Lines are paths the GPU strokes
 * (ui-core's {@code Primitive.PATH}): each segment with butt ends, the join filling the gap on the outer side of a
 * bend; miters longer than {@code line-miter-limit} widths are beveled. Decimated lines are one pixel column per
 * vertex and need no joins.
 */
public enum LineJoin {
    /** Segments end where they end: wide lines show a notch at sharp bends. */
    NONE,
    /** A disc of the line's width at each vertex. */
    ROUND,
    /** The segments' outer edges extended until they meet (beveled past the miter limit). */
    MITER,
    /** The segments' outer corners joined by a straight edge. */
    BEVEL
}
