package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.RangeVisitor;

/**
 * A series of points: an X and a Y column over the same indices (their common window: a ring's moving one, an
 * array's whole length). It keeps what queries and decimation need, up to date as the data changes: whether X is
 * sorted (found once on a change of structure, then checked over appended samples only), the Y column's
 * {@link MinMaxPyramid} (appended samples update it in O(log n) each), and for unsorted data a {@link ScatterGrid} for
 * nearest-point queries (built when first asked after a change).
 */
public abstract class XYSeries extends Series {

    static final java.util.List<Float> NO_DASH = java.util.List.of();

    protected Column x;
    protected Column y;
    private long seenX = Long.MIN_VALUE;
    private long seenY = Long.MIN_VALUE;
    private long seenXStructure = Long.MIN_VALUE;
    private long seenYStructure = Long.MIN_VALUE;
    private boolean sorted;
    private final MinMaxPyramid pyramid = new MinMaxPyramid(64);
    private boolean pyramidBuilt;
    protected final Decimator decimator = new Decimator();
    private final ScatterGrid grid = new ScatterGrid();
    private boolean gridBuilt;
    private Axis gridX;
    private Axis gridY;
    private final SortCheck sortCheck = new SortCheck();
    private int pyramidBlock = -1;

    @AllowAllocation("series are made when a chart is built")
    protected XYSeries(Chart chart, String type, String name, String style, Axis xAxis, Axis yAxis, Column x,
        Column y) {
        super(chart, type, name, style, xAxis, yAxis);
        this.x = x;
        this.y = y;
    }

    /** The X column. */
    public Column x() {
        return x;
    }

    /** The Y column. */
    public Column y() {
        return y;
    }

    /** Replaces the data; returns this. */
    public XYSeries data(Column newX, Column newY) {
        x = newX;
        y = newY;
        invalidate();
        chart.changed();
        return this;
    }

    /** The first index of the data's window. */
    public long first() {
        return Math.max(x.first(), y.first());
    }

    /** The index after the window. */
    public long end() {
        return Math.min(x.end(), y.end());
    }

    /** Whether X never decreases (binary searches and min-max decimation need it). */
    public boolean sorted() {
        return sorted;
    }

    /** The min-max pyramid over Y (built for sorted series). */
    MinMaxPyramid pyramid() {
        return pyramid;
    }

    @Override
    protected void invalidate() {
        seenX = Long.MIN_VALUE;
        seenY = Long.MIN_VALUE;
        seenXStructure = Long.MIN_VALUE;
        seenYStructure = Long.MIN_VALUE;
        pyramidBuilt = false;
        gridBuilt = false;
    }

    @Override
    protected boolean poll() {
        long xv = x.version();
        long yv = y.version();
        int block = (int) ChartTunables.PYRAMID_BLOCK.get();
        if (xv == seenX && yv == seenY && block == pyramidBlock) {
            return false;
        }
        // X: sorted or not, checked over what was appended when nothing else changed
        if (xv != seenX) {
            if (x.uniformStep() == x.uniformStep()) {
                sorted = x.uniformStep() >= 0;
            } else if (x.structureVersion() != seenXStructure || !sorted || !appendedSorted()) {
                sorted = scanSorted();
            }
        }
        // Y: the pyramid, appended to when only samples were appended
        if (block != pyramidBlock || yv != seenY && (y.structureVersion() != seenYStructure || pyramid.stale()
            || !pyramidBuilt || !y.appendedSince(seenY, pyramid))) {
            pyramid.rebuild(y, block);
            pyramidBuilt = true;
            pyramidBlock = block;
        }
        seenX = xv;
        seenY = yv;
        seenXStructure = x.structureVersion();
        seenYStructure = y.structureVersion();
        gridBuilt = false;
        return true;
    }

    private boolean appendedSorted() {
        sortCheck.ok = true;
        if (!x.appendedSince(seenX, sortCheck)) {
            return false;
        }
        return sortCheck.ok;
    }

    /** Checks appended X stays sorted, from the sample before the first appended one. */
    private final class SortCheck implements RangeVisitor {
        boolean ok;

        @Override
        public void changed(long from, long to) {
            long start = Math.max(x.first(), from - 1);
            double previous = Double.NEGATIVE_INFINITY;
            for (long i = start; i < to; i++) {
                double v = x.get(i);
                if (v < previous) {
                    ok = false;
                    return;
                }
                if (v == v) {
                    previous = v;
                }
            }
        }
    }

    private boolean scanSorted() {
        double previous = Double.NEGATIVE_INFINITY;
        for (long i = first(), end = end(); i < end; i++) {
            double v = x.get(i);
            if (v < previous) {
                return false;
            }
            if (v == v) {
                previous = v;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- visible window

    /**
     * The first index to draw: one before the first sample inside the X axis's range (so the line enters the plot),
     * or the window's start when unsorted.
     */
    protected long visibleFrom() {
        long a = first();
        long b = end();
        if (!sorted || b <= a) {
            return a;
        }
        double lo = Math.min(xAxis.range().min(), xAxis.range().max());
        return Math.max(a, Decimator.lowerBound(x, lo, a, b) - 1);
    }

    /** The index after the last to draw: one past the last sample inside the range. */
    protected long visibleTo() {
        long a = first();
        long b = end();
        if (!sorted || b <= a) {
            return b;
        }
        double hi = Math.max(xAxis.range().min(), xAxis.range().max());
        long i = Decimator.lowerBound(x, hi, a, b);
        while (i < b && x.get(i) <= hi) {
            i++;
        }
        return Math.min(b, i + 1);
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        long a = visibleX ? visibleFrom() : first();
        long b = visibleX ? visibleTo() : end();
        if (b <= a) {
            return;
        }
        if (sorted && !visibleX) {
            bounds.x(x.get(a));
            bounds.x(x.get(b - 1));
        }
        if (visibleX && sorted) {
            // only what lies inside the range: the extra sample at each end is out of it
            double lo = Math.min(xAxis.range().min(), xAxis.range().max());
            double hi = Math.max(xAxis.range().min(), xAxis.range().max());
            if (a < b && x.get(a) < lo) {
                a++;
            }
            if (b > a && x.get(b - 1) > hi) {
                b--;
            }
        }
        if (sorted && pyramidBuilt && b - a > 64) {
            if (pyramid.query(a, b)) {
                bounds.y(pyramid.min());
                bounds.y(pyramid.max());
            }
            return;
        }
        for (long i = a; i < b; i++) {
            if (!sorted) {
                bounds.x(x.get(i));
            }
            bounds.y(y.get(i));
        }
    }

    // ---------------------------------------------------------------- nearest

    @Override
    protected boolean nearest(float px, float py, float maxPixels, boolean byX, Hit hit) {
        long a = first();
        long b = end();
        if (b <= a || !enabled()) {
            return false;
        }
        if (sorted) {
            double vx = xAxis.fromPixel(px);
            long i = Decimator.lowerBound(x, vx, a, b);
            long best = -1;
            double bestD = Double.POSITIVE_INFINITY;
            for (long j = Math.max(a, i - 1); j <= Math.min(b - 1, i); j++) {
                double yv = y.get(j);
                if (!finite(yv)) {
                    continue;
                }
                float sx = xAxis.toPixel(x.get(j));
                float sy = yAxis.toPixel(yv);
                double d = byX ? Math.abs(sx - px) : Math.sqrt((sx - px) * (sx - px) + (sy - py) * (sy - py));
                if (d < bestD) {
                    bestD = d;
                    best = j;
                }
            }
            if (best < 0 || bestD > maxPixels) {
                return false;
            }
            return fill(hit, best, bestD);
        }
        if (!gridBuilt || gridX != xAxis || gridY != yAxis) {
            grid.build(x, y, a, b, xAxis, yAxis, ChartTunables.GRID_PER_CELL.get());
            gridBuilt = true;
            gridX = xAxis;
            gridY = yAxis;
        }
        long i = grid.nearest(x, y, xAxis, yAxis, px, py, maxPixels);
        return i >= 0 && fill(hit, i, grid.distance());
    }

    private boolean fill(Hit hit, long index, double distance) {
        hit.series = this;
        hit.item = 0;
        hit.index = index;
        hit.x = x.get(index);
        hit.y = y.get(index);
        hit.px = xAxis.toPixel(hit.x);
        hit.py = yAxis.toPixel(hit.y);
        hit.distance = distance;
        return true;
    }

    // ---------------------------------------------------------------- helpers for kinds

    /** Whether to draw the min-max envelope for {@code count} visible samples over {@code pixels} columns. */
    protected boolean envelope(long count, float pixels) {
        if (!sorted) {
            return false;
        }
        return switch (decimation) {
            case MIN_MAX -> true;
            case AUTO -> count > pixels * ChartTunables.DECIMATE_ABOVE.get();
            case LTTB -> count > ChartTunables.LTTB_MAX_INPUT.get();
            default -> false;
        };
    }

    /** Whether to draw LTTB's picks for {@code count} visible samples over {@code pixels} columns. */
    protected boolean lttb(long count, float pixels) {
        float target = pixels * ChartTunables.LTTB_PER_PIXEL.get();
        if (count <= target || count > ChartTunables.LTTB_MAX_INPUT.get()) {
            return false;
        }
        return decimation == Decimation.LTTB || decimation == Decimation.AUTO;
    }

    /** Runs LTTB over {@code a..b} for {@code pixels} columns: {@link Decimator#picked}. */
    protected void pick(long a, long b, float pixels) {
        int target = (int) Math.max(3, pixels * ChartTunables.LTTB_PER_PIXEL.get());
        decimator.lttb(x, y, a, b, target, xAxis, yAxis);
    }

    /** Runs the min-max envelope over {@code a..b} for the plot's columns. */
    protected void envelope(long a, long b, Canvas canvas) {
        decimator.minMax(x, y, pyramid, a, b, xAxis, (int) Math.floor(canvas.left()),
            (int) Math.ceil(canvas.right()));
    }

    /** Draws the markers of samples {@code a..b} (all, LTTB's picks when many; none past the cap). */
    protected void markers(Canvas canvas, long a, long b, com.truesim.ui.style.ResolvedStyle s, long color) {
        Marker m = s.get(ChartStyles.MARKER);
        if (m == Marker.NONE) {
            return;
        }
        float size = s.getFloat(ChartStyles.MARKER_SIZE) * canvas.scale();
        long fill = canvas.fade(com.truesim.ui.core.draw.Rgba.fade(color, s.getFloat(ChartStyles.MARKER_FILL)));
        float outline = s.getFloat(ChartStyles.MARKER_OUTLINE) * canvas.scale();
        long outlineColor = canvas.fade(color);
        float pixels = canvas.right() - canvas.left();
        long count = b - a;
        if (sorted && lttb(count, pixels) || count > ChartTunables.MAX_POINTS.get()) {
            if (count > ChartTunables.LTTB_MAX_INPUT.get()) {
                return; // too many to reduce this frame: the line's envelope shows them
            }
            pick(a, b, pixels);
            for (int k = 0; k < decimator.pickedCount(); k++) {
                long i = decimator.picked(k);
                canvas.marker(m, xAxis.toPixel(x.get(i)), yAxis.toPixel(y.get(i)), size, fill, outline, outlineColor);
            }
            return;
        }
        for (long i = a; i < b; i++) {
            double yv = y.get(i);
            double xv = x.get(i);
            if (finite(xv) && finite(yv)) {
                canvas.marker(m, xAxis.toPixel(xv), yAxis.toPixel(yv), size, fill, outline, outlineColor);
            }
        }
    }

    @Override
    protected void swatch(Canvas canvas, int item, float x0, float y0, float x1, float y1) {
        com.truesim.ui.style.ResolvedStyle s = style();
        long c = color(item);
        float mid = (y0 + y1) * 0.5f;
        Marker m = s.get(ChartStyles.MARKER);
        if (s.getFloat(ChartStyles.LINE_WIDTH) > 0 && !(this instanceof ScatterSeries)) {
            canvas.stroke(Math.max(1, Math.min(y1 - y0, s.getFloat(ChartStyles.LINE_WIDTH) * canvas.scale())), c,
                NO_DASH, false);
            canvas.line(x0, mid, x1, mid);
        }
        if (m != Marker.NONE) {
            float size = Math.min(y1 - y0, s.getFloat(ChartStyles.MARKER_SIZE) * canvas.scale());
            canvas.marker(m, (x0 + x1) * 0.5f, mid, size,
                canvas.fade(com.truesim.ui.core.draw.Rgba.fade(c, s.getFloat(ChartStyles.MARKER_FILL))),
                s.getFloat(ChartStyles.MARKER_OUTLINE) * canvas.scale(), canvas.fade(c));
        }
    }
}
