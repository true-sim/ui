package com.truesim.ui.charts;

import com.truesim.ui.controls.charts.ChartStyles;
import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.ResolvedStyle;

/**
 * Lines across the whole plot (style part {@code chart-inf-lines}): vertical at each X value, or horizontal at each Y
 * value (thresholds, events, limits). They take no part in fitting.
 */
public final class InfiniteLines extends Series {

    private final Column values;
    private final boolean vertical;
    private long seen = Long.MIN_VALUE;

    @AllowAllocation("series are made when a chart is built")
    InfiniteLines(Chart chart, String name, Axis xAxis, Axis yAxis, Column values, boolean vertical) {
        super(chart, "chart-inf-lines", name, null, xAxis, yAxis);
        this.values = values;
        this.vertical = vertical;
    }

    /** Whether the lines are vertical (at X values). */
    public boolean vertical() {
        return vertical;
    }

    @Override
    protected boolean poll() {
        if (values.version() == seen) {
            return false;
        }
        seen = values.version();
        return true;
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        // lines across everything: no extent
    }

    @Override
    protected void paint(Canvas c) {
        if (!itemEnabled(0)) {
            return;
        }
        ResolvedStyle s = style();
        c.stroke(s, color(0));
        if (!c.stroking()) {
            return;
        }
        for (long i = values.first(), end = values.end(); i < end; i++) {
            double v = values.get(i);
            if (!finite(v)) {
                continue;
            }
            if (vertical) {
                float p = xAxis.toPixel(v);
                c.line(p, c.top(), p, c.bottom());
            } else {
                float p = yAxis.toPixel(v);
                c.line(c.left(), p, c.right(), p);
            }
        }
    }

    @Override
    protected boolean streams() {
        return vertical && style().get(ChartStyles.LINE_DASH).isEmpty();
    }

    @Override
    protected void paintStream(Canvas c) {
        if (!itemEnabled(0)) {
            return;
        }
        c.stroke(style(), color(0));
        if (!c.stroking()) {
            return;
        }
        for (long i = values.first(), end = values.end(); i < end; i++) {
            double v = values.get(i);
            if (finite(v)) {
                float p = chart.streamX(v);
                c.line(p, c.top(), p, c.bottom());
            }
        }
    }

    @Override
    protected boolean nearest(float px, float py, float maxPixels, boolean byX, Hit hit) {
        if (!vertical || !byX) {
            return false;
        }
        long best = -1;
        float bestD = maxPixels;
        for (long i = values.first(), end = values.end(); i < end; i++) {
            float d = Math.abs(xAxis.toPixel(values.get(i)) - px);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) {
            return false;
        }
        hit.series = this;
        hit.item = 0;
        hit.index = best;
        hit.x = values.get(best);
        hit.y = yAxis.fromPixel(py);
        hit.px = xAxis.toPixel(hit.x);
        hit.py = py;
        hit.distance = bestD;
        return true;
    }

    @Override
    protected void describe(Hit hit, StringBuilder out) {
        xAxis.format(out, hit.x);
    }

    @Override
    protected void swatch(Canvas canvas, int item, float x0, float y0, float x1, float y1) {
        canvas.stroke(Math.max(1, style().getFloat(ChartStyles.LINE_WIDTH) * canvas.scale()), color(item),
            style().get(ChartStyles.LINE_DASH), false);
        canvas.line(x0, (y0 + y1) * 0.5f, x1, (y0 + y1) * 0.5f);
    }
}
