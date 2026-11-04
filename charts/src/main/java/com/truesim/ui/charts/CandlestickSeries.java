package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.ResolvedStyle;

/**
 * Candlesticks (style part {@code chart-candles}): for each X a wick from low to high and a body from open to close,
 * in {@code up-color} when it closed higher and {@code down-color} when lower (the series' colour when unset), the
 * body {@code bar-width} of the space between samples wide; or OHLC bars ({@link #ohlc(boolean)}): the wick with ticks
 * to the left at open and to the right at close.
 */
public final class CandlestickSeries extends XYSeries {

    private final Column open;
    private final Column high;
    private final Column low;
    private boolean ohlc;
    private double space = 1;
    private long seenX = Long.MIN_VALUE;

    @AllowAllocation("series are made when a chart is built")
    CandlestickSeries(Chart chart, String name, Axis xAxis, Axis yAxis, Column x, Column open, Column high,
        Column low, Column close) {
        super(chart, "chart-candles", name, null, xAxis, yAxis, x, close);
        this.open = open;
        this.high = high;
        this.low = low;
    }

    /** Draws OHLC bars instead of candles; returns this. */
    public CandlestickSeries ohlc(boolean on) {
        ohlc = on;
        chart.changed();
        return this;
    }

    @Override
    public long first() {
        return Math.max(super.first(), Math.max(open.first(), Math.max(high.first(), low.first())));
    }

    @Override
    public long end() {
        return Math.min(super.end(), Math.min(open.end(), Math.min(high.end(), low.end())));
    }

    @Override
    protected boolean poll() {
        boolean changed = super.poll() | open.version() != seenOpen | high.version() != seenHigh
            | low.version() != seenLow;
        seenOpen = open.version();
        seenHigh = high.version();
        seenLow = low.version();
        if (x.version() != seenX) {
            seenX = x.version();
            double min = Double.POSITIVE_INFINITY;
            for (long i = first() + 1, end = end(); i < end; i++) {
                double d = x.get(i) - x.get(i - 1);
                if (d > 0 && d < min) {
                    min = d;
                }
            }
            space = min < Double.POSITIVE_INFINITY ? min : 1;
        }
        return changed;
    }

    private long seenOpen = Long.MIN_VALUE;
    private long seenHigh = Long.MIN_VALUE;
    private long seenLow = Long.MIN_VALUE;

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        long a = visibleX ? visibleFrom() : first();
        long b = visibleX ? visibleTo() : end();
        for (long i = a; i < b; i++) {
            bounds.x(x.get(i) - space * 0.5);
            bounds.x(x.get(i) + space * 0.5);
            bounds.y(low.get(i));
            bounds.y(high.get(i));
        }
    }

    @Override
    protected void paint(Canvas c) {
        if (!itemEnabled(0)) {
            return;
        }
        ResolvedStyle s = style();
        long base = color(0);
        long up = c.fade(s.isSet(ChartStyles.UP_COLOR) ? s.rgba(ChartStyles.UP_COLOR) : base);
        long down = c.fade(s.isSet(ChartStyles.DOWN_COLOR) ? s.rgba(ChartStyles.DOWN_COLOR) : base);
        float width = s.getFloat(ChartStyles.LINE_WIDTH) * c.scale();
        double half = space * s.getFloat(ChartStyles.BAR_WIDTH) * 0.5;
        long a = visibleFrom();
        long b = Math.min(visibleTo(), a + (long) ChartTunables.MAX_POINTS.get());
        for (long i = a; i < b; i++) {
            double xv = x.get(i);
            double o = open.get(i);
            double h = high.get(i);
            double l = low.get(i);
            double cl = y.get(i);
            if (!finite(xv) || !finite(o) || !finite(h) || !finite(l) || !finite(cl)) {
                continue;
            }
            long col = cl >= o ? up : down;
            float px = xAxis.toPixel(xv);
            float left = xAxis.toPixel(xv - half);
            float right = xAxis.toPixel(xv + half);
            c.stroke(Math.max(1, width), col, Chart.solid(), false);
            c.line(px, yAxis.toPixel(l), px, yAxis.toPixel(h));
            float po = yAxis.toPixel(o);
            float pc = yAxis.toPixel(cl);
            if (ohlc) {
                c.line(left, po, px, po);
                c.line(px, pc, right, pc);
            } else {
                float top = Math.min(po, pc);
                float bottom = Math.max(po, pc);
                if (bottom - top < 1) {
                    bottom = top + 1; // a doji still shows
                }
                c.box(left, top, right, bottom, col, 0, 0, 0);
            }
        }
    }

    @Override
    protected void describe(Hit hit, StringBuilder out) {
        long i = hit.index;
        out.append("O ");
        yAxis.format(out, open.get(i)).append("  H ");
        yAxis.format(out, high.get(i)).append("  L ");
        yAxis.format(out, low.get(i)).append("  C ");
        yAxis.format(out, y.get(i));
    }
}
