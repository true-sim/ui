package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.ResolvedStyle;

/**
 * Error bars (style part {@code chart-errors}): at each sample a line from {@code y - negative} to
 * {@code y + positive} with caps {@code cap-width} wide, and the theme's marker at the sample; horizontal error bars
 * ({@link #horizontal(boolean)}) reach along X instead.
 */
public final class ErrorBarSeries extends XYSeries {

    private final Column negative;
    private final Column positive;
    private boolean horizontal;
    private long seenNeg = Long.MIN_VALUE;
    private long seenPos = Long.MIN_VALUE;

    @AllowAllocation("series are made when a chart is built")
    ErrorBarSeries(Chart chart, String name, Axis xAxis, Axis yAxis, Column x, Column y, Column negative,
        Column positive) {
        super(chart, "chart-errors", name, null, xAxis, yAxis, x, y);
        this.negative = negative;
        this.positive = positive == null ? negative : positive;
    }

    /** Reaches along X instead of Y; returns this. */
    public ErrorBarSeries horizontal(boolean on) {
        horizontal = on;
        chart.changed();
        return this;
    }

    @Override
    public long first() {
        return Math.max(super.first(), Math.max(negative.first(), positive.first()));
    }

    @Override
    public long end() {
        return Math.min(super.end(), Math.min(negative.end(), positive.end()));
    }

    @Override
    protected boolean poll() {
        boolean changed = super.poll() | negative.version() != seenNeg | positive.version() != seenPos;
        seenNeg = negative.version();
        seenPos = positive.version();
        return changed;
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        long a = visibleX ? visibleFrom() : first();
        long b = visibleX ? visibleTo() : end();
        for (long i = a; i < b; i++) {
            double xv = x.get(i);
            double yv = y.get(i);
            if (horizontal) {
                bounds.x(xv - negative.get(i));
                bounds.x(xv + positive.get(i));
                bounds.y(yv);
            } else {
                bounds.x(xv);
                bounds.y(yv - negative.get(i));
                bounds.y(yv + positive.get(i));
            }
        }
    }

    @Override
    protected void paint(Canvas c) {
        if (!itemEnabled(0)) {
            return;
        }
        ResolvedStyle s = style();
        long color = color(0);
        float cap = s.getFloat(ChartStyles.CAP_WIDTH) * c.scale() * 0.5f;
        long a = visibleFrom();
        long b = Math.min(visibleTo(), a + (long) ChartTunables.MAX_POINTS.get());
        c.stroke(s, color);
        if (c.stroking()) {
            for (long i = a; i < b; i++) {
                double xv = x.get(i);
                double yv = y.get(i);
                double n = negative.get(i);
                double p = positive.get(i);
                if (!finite(xv) || !finite(yv) || !finite(n) || !finite(p)) {
                    continue;
                }
                if (horizontal) {
                    float py = yAxis.toPixel(yv);
                    float lo = xAxis.toPixel(xv - n);
                    float hi = xAxis.toPixel(xv + p);
                    c.line(lo, py, hi, py);
                    c.line(lo, py - cap, lo, py + cap);
                    c.line(hi, py - cap, hi, py + cap);
                } else {
                    float px = xAxis.toPixel(xv);
                    float lo = yAxis.toPixel(yv - n);
                    float hi = yAxis.toPixel(yv + p);
                    c.line(px, lo, px, hi);
                    c.line(px - cap, lo, px + cap, lo);
                    c.line(px - cap, hi, px + cap, hi);
                }
            }
        }
        markers(c, a, b, s, color);
    }

    @Override
    protected void describe(Hit hit, StringBuilder out) {
        Axis value = horizontal ? xAxis : yAxis;
        value.format(out, horizontal ? hit.x : hit.y).append(" -");
        value.format(out, negative.get(hit.index)).append(" +");
        value.format(out, positive.get(hit.index));
    }
}
