package com.truesim.ui.charts;

/** How a chart shows the values at the hovered X (the style key {@code chart-tooltips}). */
public enum TooltipMode {
    /** One box listing every series' nearest value (Grafana's shared tooltip). */
    MERGED,
    /** A small box beside each highlighted point. */
    PER_SERIES,
    /** No tooltip: the crosshair and the highlighted points only. */
    NONE
}
