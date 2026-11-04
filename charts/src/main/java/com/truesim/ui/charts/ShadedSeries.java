package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.style.ResolvedStyle;

/**
 * The band between two lines over the same X (style part {@code chart-shaded}: a confidence interval, a min-max
 * range), filled in the series' colour at {@code fill-opacity}, its edges stroked when the theme gives it a
 * {@code line-width}. Many samples draw per pixel column from the lowest to the highest value of either line (min-max
 * pyramids over both).
 */
public final class ShadedSeries extends XYSeries {

    private final Tracked second;

    @AllowAllocation("series are made when a chart is built")
    ShadedSeries(Chart chart, String name, Axis xAxis, Axis yAxis, Column x, Column y1, Column y2) {
        super(chart, "chart-shaded", name, null, xAxis, yAxis, x, y1);
        second = new Tracked(y2);
    }

    /** The second line. */
    public Column y2() {
        return second.column;
    }

    @Override
    public long end() {
        return Math.min(super.end(), second.column.end());
    }

    @Override
    public long first() {
        return Math.max(super.first(), second.column.first());
    }

    @Override
    protected boolean poll() {
        boolean a = super.poll();
        boolean b = second.poll();
        return a || b;
    }

    @Override
    protected void invalidate() {
        super.invalidate();
        second.invalidate();
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        super.bounds(bounds, visibleX);
        long a = visibleX ? visibleFrom() : first();
        long b = visibleX ? visibleTo() : end();
        if (b > a && second.pyramid.query(a, b)) {
            bounds.y(second.pyramid.min());
            bounds.y(second.pyramid.max());
        }
    }

    @Override
    protected void paint(Canvas c) {
        if (!itemEnabled(0)) {
            return;
        }
        ResolvedStyle s = style();
        long fill = c.fade(fill(s, 0));
        long a = visibleFrom();
        long b = visibleTo();
        if (b <= a) {
            return;
        }
        Column y2 = second.column;
        if (envelope(b - a, c.right() - c.left())) {
            envelope(a, b, c);
            for (int k = 0; k < decimator.columns(); k++) {
                double lo = decimator.minY(k);
                double hi = decimator.maxY(k);
                if (second.pyramid.query(decimator.from(k), decimator.to(k))) {
                    lo = Math.min(lo, second.pyramid.min());
                    hi = Math.max(hi, second.pyramid.max());
                }
                float cx = decimator.pixel(k);
                c.box(cx - 0.5f, yAxis.toPixel(hi), cx + 0.5f, yAxis.toPixel(lo), fill, 0, 0, 0);
            }
        } else {
            long end = Math.min(b, a + (long) ChartTunables.MAX_POINTS.get());
            float px = Float.NaN;
            float pa = 0;
            float pb = 0;
            for (long i = a; i < end; i++) {
                double xv = x.get(i);
                double va = y.get(i);
                double vb = y2.get(i);
                if (!finite(xv) || !finite(va) || !finite(vb)) {
                    px = Float.NaN;
                    continue;
                }
                float sx = xAxis.toPixel(xv);
                float sa = yAxis.toPixel(va);
                float sb = yAxis.toPixel(vb);
                if (px == px) {
                    band(c, px, pa, pb, sx, sa, sb, fill);
                }
                px = sx;
                pa = sa;
                pb = sb;
            }
            c.stroke(s, color(0));
            if (c.stroking()) {
                edge(c, a, end, y);
                edge(c, a, end, y2);
            }
        }
    }

    /** The band between two segments: a quad, or two triangles where the lines cross. */
    static void band(Canvas c, float x0, float a0, float b0, float x1, float a1, float b1, long fill) {
        float d0 = a0 - b0;
        float d1 = a1 - b1;
        if (d0 * d1 < 0) {
            float t = d0 / (d0 - d1);
            float xm = x0 + (x1 - x0) * t;
            float ym = a0 + (a1 - a0) * t;
            c.triangle(x0, a0, xm, ym, x0, b0, fill, Primitive.EDGE_AB | Primitive.EDGE_BC);
            c.triangle(xm, ym, x1, a1, x1, b1, fill, Primitive.EDGE_AB | Primitive.EDGE_CA);
            return;
        }
        c.quad(x0, a0, x1, a1, x1, b1, x0, b0, fill, 1 | 4);
    }

    private void edge(Canvas c, long a, long b, Column values) {
        c.moveTo(Float.NaN, Float.NaN);
        for (long i = a; i < b; i++) {
            double xv = x.get(i);
            double v = values.get(i);
            if (finite(xv) && finite(v)) {
                c.lineTo(xAxis.toPixel(xv), yAxis.toPixel(v));
            } else {
                c.lineTo(Float.NaN, Float.NaN);
            }
        }
    }

    @Override
    protected void describe(Hit hit, StringBuilder out) {
        yAxis.format(out, hit.y);
        out.append(" to ");
        yAxis.format(out, second.column.get(hit.index));
    }

    @Override
    protected void swatch(Canvas canvas, int item, float x0, float y0, float x1, float y1) {
        canvas.box(x0, y0, x1, y1, canvas.fade(fill(style(), item)), 0, 0, 0);
    }
}
