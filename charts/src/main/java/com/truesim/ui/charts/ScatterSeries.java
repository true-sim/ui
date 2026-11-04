package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.ResolvedStyle;

/**
 * Markers at the samples, no line (style part {@code chart-scatter}: {@code marker}, {@code marker-size},
 * {@code marker-fill}, {@code marker-outline}). Unsorted data is fine: the nearest point is found through a
 * {@link ScatterGrid}. Many samples show LTTB's picks when X is sorted (or all of them up to
 * {@code ui.chart.maxPoints}).
 */
public final class ScatterSeries extends XYSeries {

    @AllowAllocation("series are made when a chart is built")
    ScatterSeries(Chart chart, String name, Axis xAxis, Axis yAxis, Column x, Column y) {
        super(chart, "chart-scatter", name, null, xAxis, yAxis, x, y);
    }

    @Override
    protected void paint(Canvas c) {
        if (!itemEnabled(0)) {
            return;
        }
        ResolvedStyle s = style();
        long a = visibleFrom();
        long b = visibleTo();
        if (!sorted()) {
            b = Math.min(b, a + (long) ChartTunables.MAX_POINTS.get());
        }
        markers(c, a, b, s, color(0));
    }
}
