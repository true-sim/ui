package com.truesim.ui.charts;

import com.truesim.ui.controls.charts.ChartStyles;

import com.truesim.ui.controls.charts.Marker;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.style.ResolvedStyle;

/**
 * A line through the samples (style part {@code chart-line}), straight or as stairs ({@link Step}), with markers when
 * the theme gives it a {@code marker}, and filled down to a baseline when it is an area ({@link #fill(double)}, part
 * {@code chart-area}). Many samples draw as their min-max envelope per pixel column (exact: the same pixels as every
 * segment), recorded as one primitive over a vertex per column ({@link Canvas#columns}), its fill another over the
 * same vertices; markers then show LTTB's picks. Fewer samples draw as a path the GPU strokes.
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
    // the stream (Chart's streaming): vertex i - from of the block holds sample i at the anchor's mapping
    private final Canvas.Vertices streamBlock = new Canvas.Vertices();
    private long from = -1;
    private long to = -1;
    private float lastX;
    private float lastY;
    private float length;
    private float yMap0 = Float.NaN; // the Y mapping the vertices were written with
    private float yMap1 = Float.NaN;
    private long structure = -1;
    private final long[] breaks = new long[MAX_BREAKS]; // samples that are not numbers: the path breaks there
    private int breakCount;
    private static final int MAX_BREAKS = 32;

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
    protected boolean streams() {
        if (step != Step.NONE || baseline == baseline || !sorted() || breakCount >= MAX_BREAKS
            || decimation == Decimation.LTTB || decimation == Decimation.MIN_MAX) {
            return false;
        }
        ResolvedStyle s = style();
        if (s.get(ChartStyles.MARKER) != Marker.NONE || !s.get(ChartStyles.LINE_DASH).isEmpty()) {
            return false;
        }
        long a = visibleFrom();
        long b = visibleTo();
        float pixels = Math.abs(xAxis.endPixel() - xAxis.startPixel());
        return !envelope(b - a, pixels) && b - a <= (long) ChartTunables.MAX_POINTS.get();
    }

    /**
     * The line in the chart's stream: its samples' vertices are written once, at the anchor's mapping, and kept in a
     * block of its own; each recording appends the new samples and records one path per unbroken run, from the
     * sample before the visible ones to the newest. Everything is written again when the chart takes a new anchor,
     * the Y mapping changed, the data changed other than by appending, or the block is full.
     */
    @Override
    protected void paintStream(Canvas c) {
        if (!itemEnabled(0)) {
            return;
        }
        long a = Math.max(first(), visibleFrom() - 1);
        long b = end();
        if (b - a < 2) {
            return;
        }
        float y0 = yAxis.toPixel(0);
        float y1 = yAxis.toPixel(1);
        long capacity = Math.max(2 * (b - a), x.capacity() * 2 + 2);
        int first = c.streamBlock(streamBlock, (int) Math.min(Integer.MAX_VALUE / 8, capacity));
        boolean rewrite = first < 0 || chart.streamRebuild() || y0 != yMap0 || y1 != yMap1
            || x.structureVersion() + y.structureVersion() != structure || a < from || b < to
            || b - from > streamBlockCapacity();
        if (first < 0) {
            first = c.streamBlock(streamBlock, 0);
        }
        if (rewrite) {
            from = a;
            to = a;
            yMap0 = y0;
            yMap1 = y1;
            structure = x.structureVersion() + y.structureVersion();
            breakCount = 0;
            length = 0;
        }
        for (long i = to; i < b; i++) {
            double xv = x.get(i);
            double yv = y.get(i);
            float sx = chart.streamX(xv);
            float sy = yAxis.toPixel(yv);
            if (!finite(xv) || !finite(yv)) {
                if (breakCount < MAX_BREAKS) {
                    breaks[breakCount] = i;
                }
                breakCount++;
                sx = lastX;
                sy = lastY;
            } else if (i > from) {
                float dx = sx - lastX;
                float dy = sy - lastY;
                length += (float) Math.sqrt(dx * dx + dy * dy);
            }
            c.streamVertex(first + (int) (i - from), sx, sy, length);
            lastX = sx;
            lastY = sy;
        }
        to = b;
        // breaks before what shows are dropped
        int kept = 0;
        for (int k = 0; k < Math.min(breakCount, MAX_BREAKS); k++) {
            if (breaks[k] >= a) {
                breaks[kept++] = breaks[k];
            }
        }
        breakCount = kept;
        c.stroke(style(), color(0));
        long run = a;
        for (int k = 0; k <= breakCount; k++) {
            long stop = k < breakCount ? breaks[k] : b;
            c.streamPath(first + (int) (run - from), (int) (stop - run));
            run = stop + 1;
        }
    }

    private long streamBlockCapacity() {
        return streamBlock.capacity();
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
            int at = envelopeColumns(c);
            if (area && at >= 0) {
                c.fillColumns(at, envelopeCount, envelopeX, envelopeStep, yAxis.toPixel(baseline), fill);
            }
            c.stroke(s, color);
            if (c.stroking() && at >= 0) {
                envelopeLine(c, a, b, at);
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
     * The envelope's pixel columns as vertices ({@link Canvas#columns}): every column from the first to the last with
     * samples, each its first, last, lowest and highest sample; a column without samples between two with them lies on
     * the line joining them (level, then up or down, for stairs), so connectors cross it as one segment would. Returns
     * the first vertex, or -1 without columns; {@link #envelopeCount}, {@link #envelopeX}, {@link #envelopeStep} say
     * where they lie.
     */
    private int envelopeColumns(Canvas c) {
        int n = decimator.columns();
        envelopeCount = 0;
        if (n == 0) {
            return -1;
        }
        float p0 = decimator.pixel(0);
        float pn = decimator.pixel(n - 1);
        envelopeStep = pn >= p0 ? 1 : -1; // an inverted axis: columns leftwards, in the data's order
        envelopeX = p0;
        int count = Math.round(Math.abs(pn - p0)) + 1;
        int at = c.columns(count);
        int prev = -1;
        float prevLast = Float.NaN;
        for (int o = 0; o < n; o++) {
            int k = Math.round(Math.abs(decimator.pixel(o) - p0));
            float first = yAxis.toPixel(decimator.firstY(o));
            float last = yAxis.toPixel(decimator.lastY(o));
            float yMin = yAxis.toPixel(decimator.minY(o));
            float yMax = yAxis.toPixel(decimator.maxY(o));
            for (int g = prev + 1; g < k; g++) {
                float y;
                if (prev < 0 || prevLast != prevLast || first != first) {
                    y = Float.NaN;
                } else if (step != Step.NONE) {
                    y = prevLast;
                } else {
                    y = prevLast + (first - prevLast) * (g - prev) / (k - prev);
                }
                c.column(at, g, y, y, y, y);
            }
            c.column(at, k, first, last, Math.min(yMin, yMax), Math.max(yMin, yMax));
            prev = k;
            prevLast = last;
        }
        envelopeCount = count;
        return at;
    }

    private int envelopeCount;
    private float envelopeX;
    private float envelopeStep;

    /**
     * The envelope as a line: its columns stroked (a vertical stroke over each column's span, a connector from the last
     * column's last sample to its first where their spans do not touch), and the samples just outside the plot joined
     * to its ends.
     */
    private void envelopeLine(Canvas c, long a, long b, int at) {
        int n = decimator.columns();
        long firstIn = decimator.from(0);
        if (firstIn > a && finite(y.get(firstIn - 1))) {
            float yFirst = yAxis.toPixel(decimator.firstY(0));
            if (yFirst == yFirst) {
                connector(c, xAxis.toPixel(x.get(firstIn - 1)), yAxis.toPixel(y.get(firstIn - 1)),
                    decimator.pixel(0), yFirst);
            }
        }
        c.strokeColumns(at, envelopeCount, envelopeX, envelopeStep, step != Step.NONE);
        long lastIn = decimator.to(n - 1);
        float py = yAxis.toPixel(decimator.lastY(n - 1));
        if (lastIn < b && py == py && finite(y.get(lastIn))) {
            connector(c, decimator.pixel(n - 1), py, xAxis.toPixel(x.get(lastIn)), yAxis.toPixel(y.get(lastIn)));
        }
    }

    /** A segment from {@code px, py} to {@code cx, cy}, or stairs. */
    private void connector(Canvas c, float px, float py, float cx, float cy) {
        if (step == Step.NONE) {
            c.line(px, py, cx, cy);
        } else {
            c.line(px, py, cx, py);
            c.line(cx, py, cx, cy);
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

    @Override
    protected void swatch(Canvas canvas, int item, float x0, float y0, float x1, float y1) {
        if (baseline == baseline) {
            ResolvedStyle s = style();
            canvas.box(x0, (y0 + y1) * 0.5f, x1, y1, canvas.fade(fill(s, item)), 0, 0, 0);
        }
        super.swatch(canvas, item, x0, y0, x1, y1);
    }
}
