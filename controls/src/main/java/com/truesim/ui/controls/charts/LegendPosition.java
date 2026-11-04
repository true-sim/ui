package com.truesim.ui.controls.charts;

/**
 * Where a chart's legend sits (the style key {@code legend-position}): in a corner or along an edge of the plot, over
 * the data; at the corner or edge of the plot with the least data under it ({@link #BEST}, as matplotlib's
 * {@code 'best'}); or outside the plot, which then gives up the room the legend takes ({@code outside-*}: never over
 * the data).
 */
public enum LegendPosition {
    /** The top left corner. */
    TOP_LEFT,
    /** Centred along the top. */
    TOP,
    /** The top right corner. */
    TOP_RIGHT,
    /** Centred along the left. */
    LEFT,
    /** Centred along the right. */
    RIGHT,
    /** The bottom left corner. */
    BOTTOM_LEFT,
    /** Centred along the bottom. */
    BOTTOM,
    /** The bottom right corner. */
    BOTTOM_RIGHT,
    /** No legend. */
    NONE,
    /**
     * In the plot, at the first of the corners and edges (top right, top left, bottom left, bottom right, right, left,
     * bottom, top) with the least data under it, chosen again as the data, ranges or size change.
     */
    BEST,
    /** Above the plot (below the title, above top axes), centred over it; entries in rows wrap at the chart's width. */
    OUTSIDE_TOP,
    /** Below the plot and its bottom axes, centred under it. */
    OUTSIDE_BOTTOM,
    /** Left of the plot and its left axes, level with the plot's top. */
    OUTSIDE_LEFT,
    /** Right of the plot and its right axes, level with the plot's top. */
    OUTSIDE_RIGHT
}
