package com.truesim.ui.charts;

import com.truesim.ui.controls.charts.ChartStyles;
import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.ResolvedStyle;

/**
 * A digital signal (ImPlot's digital plot; style part {@code chart-digital}): each sample's level (0 low, 1 high,
 * values between drawn in proportion) holds until the next one, drawn in a lane {@code digital-height} pixels high
 * along the bottom of the plot whatever the Y axis shows; digital series stack upwards in their own lanes. Many
 * samples draw per pixel column, high wherever any sample in the column is.
 */
public final class DigitalSeries extends XYSeries {

    private float laneBottom;
    private float laneHeight;

    @AllowAllocation("series are made when a chart is built")
    DigitalSeries(Chart chart, String name, Axis xAxis, Axis yAxis, Column x, Column y) {
        super(chart, "chart-digital", name, null, xAxis, yAxis, x, y);
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        long a = first();
        long b = end();
        if (b > a && !visibleX) {
            bounds.x(x.get(a));
            bounds.x(x.get(b - 1));
        }
    }

    private static float level(double v) {
        return v != v ? 0 : (float) Math.max(0, Math.min(1, v));
    }

    @Override
    protected void paint(Canvas c) {
        if (!itemEnabled(0)) {
            return;
        }
        ResolvedStyle s = style();
        long color = color(0);
        float h = s.getFloat(ChartStyles.DIGITAL_HEIGHT) * c.scale();
        int lane = chart.digitalSlot++;
        laneHeight = h;
        laneBottom = c.bottom() - lane * h * 1.5f - h * 0.25f;
        long fill = c.fade(fill(s, 0));
        long a = visibleFrom();
        long b = visibleTo();
        if (b <= a) {
            return;
        }
        if (envelope(b - a, c.right() - c.left())) {
            envelope(a, b, c);
            for (int k = 0; k < decimator.columns(); k++) {
                float top = laneBottom - h * level(decimator.maxY(k));
                float cx = decimator.pixel(k);
                c.box(cx - 0.5f, top, cx + 0.5f, laneBottom, fill, 0, 0, 0);
            }
            return;
        }
        c.stroke(s, color);
        boolean stroking = c.stroking();
        float px = Float.NaN;
        float py = 0;
        long end = Math.min(b, a + (long) ChartTunables.MAX_POINTS.get());
        for (long i = a; i < end; i++) {
            double xv = x.get(i);
            if (!finite(xv)) {
                continue;
            }
            float sx = xAxis.toPixel(xv);
            float sy = laneBottom - h * level(y.get(i));
            if (px == px) {
                c.box(px, py, sx, laneBottom, fill, 0, 0, 0);
                if (stroking) {
                    c.line(px, py, sx, py);
                    c.line(sx, py, sx, sy);
                }
            }
            px = sx;
            py = sy;
        }
    }

    @Override
    protected boolean nearest(float px, float py, float maxPixels, boolean byX, Hit hit) {
        boolean found = super.nearest(px, laneBottom, Float.POSITIVE_INFINITY, true, hit);
        if (!found) {
            return false;
        }
        hit.py = laneBottom - laneHeight * level(hit.y);
        hit.distance = byX ? Math.abs(hit.px - px) : Math.abs(hit.px - px) + Math.abs(hit.py - py);
        return hit.distance <= maxPixels;
    }

    @Override
    protected void swatch(Canvas canvas, int item, float x0, float y0, float x1, float y1) {
        canvas.box(x0, (y0 + y1) * 0.5f, x1, y1, canvas.fade(fill(style(), item)), 0, 0, 0);
    }
}
