package com.truesim.ui.controls.charts;

/**
 * How the segments of a series' line meet (the style key {@code line-join}): the line primitive draws segments with
 * butt ends, so wide lines show a notch at sharp bends unless the joins are rounded by a disc at each vertex (one more
 * primitive per vertex; decimated lines are one pixel column per vertex and join without it).
 */
public enum LineJoin {
    /** Segments end where they end. */
    NONE,
    /** A disc of the line's width at each vertex. */
    ROUND
}
