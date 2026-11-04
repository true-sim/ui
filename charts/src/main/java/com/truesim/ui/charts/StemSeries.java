package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.ResolvedStyle;

/**
 * Stems: a line from a baseline to each sample, with the theme's marker at its end (style part {@code chart-stems}).
 */
public final class StemSeries extends XYSeries {

    private double baseline;

    @AllowAllocation("series are made when a chart is built")
    StemSeries(Chart chart, String name, Axis xAxis, Axis yAxis, Column x, Column y, double baseline) {
        super(chart, "chart-stems", name, null, xAxis, yAxis, x, y);
        this.baseline = baseline;
    }

    /** The baseline the stems start at. */
    public double baseline() {
        return baseline;
    }

    /** Sets the baseline; returns this. */
    public StemSeries baseline(double value) {
        baseline = value;
        chart.changed();
        return this;
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        super.bounds(bounds, visibleX);
        bounds.y(baseline);
    }

    @Override
    protected void paint(Canvas c) {
        if (!itemEnabled(0)) {
            return;
        }
        ResolvedStyle s = style();
        long color = color(0);
        long a = visibleFrom();
        long b = Math.min(visibleTo(), visibleFrom() + (long) ChartTunables.MAX_POINTS.get());
        float base = yAxis.toPixel(baseline);
        c.stroke(s, color);
        if (c.stroking()) {
            for (long i = a; i < b; i++) {
                double xv = x.get(i);
                double yv = y.get(i);
                if (finite(xv) && finite(yv)) {
                    float px = xAxis.toPixel(xv);
                    c.line(px, base, px, yAxis.toPixel(yv));
                }
            }
        }
        markers(c, a, b, s, color);
    }
}
