package com.truesim.ui.charts;

/** How a series with more samples than pixels is reduced before drawing (design UI23), chosen per series. */
public enum Decimation {
    /** Every visible sample is drawn (the series' own limit: {@code ui.chart.maxPoints}). */
    NONE,
    /** The exact min-max envelope per pixel column from a min-max pyramid (lines, areas, sorted X). */
    MIN_MAX,
    /** Largest-Triangle-Three-Buckets: representative samples that keep the shape (markers, scatter). */
    LTTB,
    /** {@link #MIN_MAX} for lines and areas with sorted X, {@link #LTTB} for markers and scatter. */
    AUTO
}
