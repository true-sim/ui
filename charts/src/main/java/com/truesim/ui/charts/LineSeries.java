package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.style.ResolvedStyle;

/**
 * A line through the samples (style part {@code chart-line}), straight or as stairs ({@link Step}), with markers when
 * the theme gives it a {@code marker}, and filled down to a baseline when it is an area ({@link #fill(double)}, part
 * {@code chart-area}). Many samples draw as their min-max envelope per pixel column (exact: the same pixels as every
 * segment); markers then show LTTB's picks.
 */
public class LineSeries extends XYSeries {

    /** How a line goes from one sample to the next. */
    public enum Step {
        /** Straight. */
        NONE,
        /** Level until the next sample's X, then up or down to it (the value holds until it changes). */
        POST,
        /** Up or down at once, then level to the next sample's X. */
        PRE,
        /** Level to halfway, up or down there, level on. */
        MID
    }

    private Step step = Step.NONE;
    private double baseline = Double.NaN;

    @AllowAllocation("series are made when a chart is built")
    LineSeries(Chart chart, String type, String name, String style, Axis xAxis, Axis yAxis, Column x, Column y) {
        super(chart, type, name, style, xAxis, yAxis, x, y);
    }

    /** Draws stairs; returns this. */
    public LineSeries step(Step value) {
        step = value;
        chart.changed();
        return this;
    }

    /** How samples are joined. */
    public Step step() {
        return step;
    }

    /** Fills the area between the line and {@code value} (NaN: no fill); returns this. */
    public LineSeries fill(double value) {
        baseline = value;
        chart.changed();
        return this;
    }

    /** The fill's baseline (NaN: none). */
    public double baseline() {
        return baseline;
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        super.bounds(bounds, visibleX);
        if (baseline == baseline) {
            bounds.y(baseline);
        }
    }

    @Override
    protected void paint(Canvas c) {
        if (!itemEnabled(0)) {
            return;
        }
        ResolvedStyle s = style();
        long color = color(0);
        long a = visibleFrom();
        long b = visibleTo();
        if (b <= a) {
            return;
        }
        float pixels = c.right() - c.left();
        boolean area = baseline == baseline;
        long fill = c.fade(fill(s, 0));
        if (envelope(b - a, pixels)) {
            envelope(a, b, c);
            if (area) {
                envelopeFill(c, fill);
            }
            c.stroke(s, color);
            if (c.stroking()) {
                envelopeLine(c, a, b);
            }
        } else if (sorted() && lttb(b - a, pixels) && decimation == Decimation.LTTB) {
            pick(a, b, pixels);
            if (area) {
                pickedFill(c, fill);
            }
            c.stroke(s, color);
            if (c.stroking()) {
                pickedLine(c);
            }
        } else {
            long end = Math.min(b, a + (long) ChartTunables.MAX_POINTS.get());
            if (area) {
                rawFill(c, a, end, fill);
            }
            c.stroke(s, color);
            if (c.stroking()) {
                rawLine(c, a, end);
            }
        }
        markers(c, a, b, s, color);
    }

    // ---------------------------------------------------------------- lines

    private void rawLine(Canvas c, long a, long b) {
        boolean started = false;
        float px = 0;
        float py = 0;
        for (long i = a; i < b; i++) {
            double xv = x.get(i);
            double yv = y.get(i);
            if (!finite(xv) || !finite(yv)) {
                started = false;
                c.lineTo(Float.NaN, Float.NaN);
                continue;
            }
            float sx = xAxis.toPixel(xv);
            float sy = yAxis.toPixel(yv);
            if (!started) {
                c.moveTo(sx, sy);
                started = true;
            } else {
                stepTo(c, px, py, sx, sy);
            }
            px = sx;
            py = sy;
        }
    }

    private void stepTo(Canvas c, float px, float py, float sx, float sy) {
        switch (step) {
            case POST -> {
                c.lineTo(sx, py);
                c.lineTo(sx, sy);
            }
            case PRE -> {
                c.lineTo(px, sy);
                c.lineTo(sx, sy);
            }
            case MID -> {
                float mx = (px + sx) * 0.5f;
                c.lineTo(mx, py);
                c.lineTo(mx, sy);
                c.lineTo(sx, sy);
            }
            default -> c.lineTo(sx, sy);
        }
    }

    private void pickedLine(Canvas c) {
        float px = 0;
        float py = 0;
        for (int k = 0; k < decimator.pickedCount(); k++) {
            long i = decimator.picked(k);
            float sx = xAxis.toPixel(x.get(i));
            float sy = yAxis.toPixel(y.get(i));
            if (k == 0) {
                c.moveTo(sx, sy);
            } else {
                stepTo(c, px, py, sx, sy);
            }
            px = sx;
            py = sy;
        }
    }

    /**
     * The envelope as a line: into each column at its first sample, a vertical stroke over its minimum to maximum,
     * out at its last sample; the samples just outside the plot join it at the ends.
     */
    private void envelopeLine(Canvas c, long a, long b) {
        int n = decimator.columns();
        if (n == 0) {
            return;
        }
        float px = Float.NaN;
        float py = Float.NaN;
        long firstIn = decimator.from(0);
        if (firstIn > a && finite(y.get(firstIn - 1))) {
            px = xAxis.toPixel(x.get(firstIn - 1));
            py = yAxis.toPixel(y.get(firstIn - 1));
        }
        float w = c.strokeWidth();
        float prevLo = Float.NaN;
        float prevHi = Float.NaN;
        for (int k = 0; k < n; k++) {
            float cx = decimator.pixel(k);
            float yFirst = yAxis.toPixel(decimator.firstY(k));
            float yLast = yAxis.toPixel(decimator.lastY(k));
            float yMin = yAxis.toPixel(decimator.minY(k));
            float yMax = yAxis.toPixel(decimator.maxY(k));
            float lo = Math.min(yMin, yMax);
            float hi = Math.max(yMin, yMax);
            boolean tall = hi - lo >= 0.5f;
            // the connector from the last column: needed unless both columns are strokes whose spans overlap, as
            // adjacent pixel columns then touch already (every segment between them lies within the two spans)
            boolean touching = tall && prevHi - prevLo >= 0.5f && prevLo <= hi + w && lo <= prevHi + w
                && cx - px <= 1.5f;
            if (px == px && yFirst == yFirst && !touching) {
                if (step == Step.NONE) {
                    c.line(px, py, cx, yFirst);
                } else {
                    c.line(px, py, cx, py);
                    c.line(cx, py, cx, yFirst);
                }
            }
            if (tall) {
                c.line(cx, lo - w * 0.5f, cx, hi + w * 0.5f); // butt ends: grow by half the width to meet the joins
            }
            prevLo = lo;
            prevHi = hi;
            px = cx;
            py = yLast;
        }
        long lastIn = decimator.to(n - 1);
        if (lastIn < b && px == px && finite(y.get(lastIn))) {
            float sx = xAxis.toPixel(x.get(lastIn));
            float sy = yAxis.toPixel(y.get(lastIn));
            if (step == Step.NONE) {
                c.line(px, py, sx, sy);
            } else {
                c.line(px, py, sx, py);
                c.line(sx, py, sx, sy);
            }
        }
    }

    // ---------------------------------------------------------------- fills

    private void rawFill(Canvas c, long a, long b, long fill) {
        float base = yAxis.toPixel(baseline);
        float px = Float.NaN;
        float py = Float.NaN;
        for (long i = a; i < b; i++) {
            double xv = x.get(i);
            double yv = y.get(i);
            if (!finite(xv) || !finite(yv)) {
                px = Float.NaN;
                continue;
            }
            float sx = xAxis.toPixel(xv);
            float sy = yAxis.toPixel(yv);
            if (px == px) {
                switch (step) {
                    case POST -> c.box(px, py, sx, base, fill, 0, 0, 0);
                    case PRE -> c.box(px, sy, sx, base, fill, 0, 0, 0);
                    case MID -> {
                        float mx = (px + sx) * 0.5f;
                        c.box(px, py, mx, base, fill, 0, 0, 0);
                        c.box(mx, sy, sx, base, fill, 0, 0, 0);
                    }
                    default -> trapezoid(c, px, py, sx, sy, base, fill);
                }
            }
            px = sx;
            py = sy;
        }
    }

    private void pickedFill(Canvas c, long fill) {
        float base = yAxis.toPixel(baseline);
        float px = 0;
        float py = 0;
        for (int k = 0; k < decimator.pickedCount(); k++) {
            long i = decimator.picked(k);
            float sx = xAxis.toPixel(x.get(i));
            float sy = yAxis.toPixel(y.get(i));
            if (k > 0) {
                trapezoid(c, px, py, sx, sy, base, fill);
            }
            px = sx;
            py = sy;
        }
    }

    /** The area under one segment down to the baseline: a quad, or two triangles where it crosses the baseline. */
    static void trapezoid(Canvas c, float xa, float ya, float xb, float yb, float base, long fill) {
        float da = ya - base;
        float db = yb - base;
        if (da * db < 0) {
            float t = da / (da - db);
            float xm = xa + (xb - xa) * t;
            c.triangle(xa, ya, xm, base, xa, base, fill, Primitive.EDGE_AB | Primitive.EDGE_BC);
            c.triangle(xm, base, xb, yb, xb, base, fill, Primitive.EDGE_AB | Primitive.EDGE_CA);
            return;
        }
        // a (top left), b (top right), b' and a' on the baseline: the line and the baseline are its outline
        c.quad(xa, ya, xb, yb, xb, base, xa, base, fill, 1 | 4);
    }

    /** The envelope's fill: each column from the baseline to its farthest sample (both ways when it crosses). */
    private void envelopeFill(Canvas c, long fill) {
        float base = yAxis.toPixel(baseline);
        for (int k = 0; k < decimator.columns(); k++) {
            float cx = decimator.pixel(k);
            float yMin = yAxis.toPixel(decimator.minY(k));
            float yMax = yAxis.toPixel(decimator.maxY(k));
            float lo = Math.min(base, Math.min(yMin, yMax));
            float hi = Math.max(base, Math.max(yMin, yMax));
            c.box(cx - 0.5f, lo, cx + 0.5f, hi, fill, 0, 0, 0);
        }
    }

    @Override
    protected void swatch(Canvas canvas, int item, float x0, float y0, float x1, float y1) {
        if (baseline == baseline) {
            ResolvedStyle s = style();
            canvas.box(x0, (y0 + y1) * 0.5f, x1, y1, canvas.fade(fill(s, item)), 0, 0, 0);
        }
        super.swatch(canvas, item, x0, y0, x1, y1);
    }
}
