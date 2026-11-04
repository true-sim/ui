package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import java.util.List;

/**
 * Bars (style part {@code chart-bars}): one column of values per item at shared positions, grouped side by side or
 * stacked (positive values upwards, negative downwards), vertical or horizontal. A bar's width is {@code bar-width} of
 * the space between positions (the smallest gap between them, or {@link #spacing(double)}); corners round by the
 * part's {@code radius}, outlines by its {@code border}. Bars are hit by their rectangles.
 */
public class BarSeries extends Series {

    private final List<String> names;
    protected Column x;
    protected Column[] ys;
    private final long[] seen;
    private long seenX = Long.MIN_VALUE;
    private boolean stacked;
    private boolean horizontal;
    private double spacing = Double.NaN;
    private double foundSpacing = 1;
    private boolean sorted;

    @AllowAllocation("series are made when a chart is built")
    BarSeries(Chart chart, List<String> names, Axis xAxis, Axis yAxis, Column x, Column... ys) {
        this(chart, "chart-bars", names, xAxis, yAxis, x, ys);
    }

    @AllowAllocation("series are made when a chart is built")
    BarSeries(Chart chart, String type, List<String> names, Axis xAxis, Axis yAxis, Column x, Column... ys) {
        super(chart, type, names.isEmpty() ? "" : names.get(0), null, xAxis, yAxis);
        if (names.size() != ys.length || ys.length == 0) {
            throw new IllegalArgumentException("a name for each of the " + ys.length + " columns: " + names);
        }
        this.names = List.copyOf(names);
        this.x = x;
        this.ys = ys.clone();
        this.seen = new long[ys.length];
        java.util.Arrays.fill(seen, Long.MIN_VALUE);
    }

    /** Stacks the items instead of grouping them; returns this. */
    public BarSeries stacked(boolean on) {
        stacked = on;
        chart.changed();
        return this;
    }

    /** Whether the items stack. */
    public boolean stacked() {
        return stacked;
    }

    /** Lays the bars along the Y axis, their values along the X axis; returns this. */
    public BarSeries horizontal(boolean on) {
        horizontal = on;
        chart.changed();
        return this;
    }

    /** Whether the bars are horizontal. */
    public boolean horizontal() {
        return horizontal;
    }

    /** The space between positions a bar's width is a share of (NaN: the smallest gap in the data); returns this. */
    public BarSeries spacing(double value) {
        spacing = value;
        chart.changed();
        return this;
    }

    @Override
    public int items() {
        return ys.length;
    }

    @Override
    public String item(int item) {
        return names.get(item);
    }

    protected long first() {
        long a = x.first();
        for (Column y : ys) {
            a = Math.max(a, y.first());
        }
        return a;
    }

    protected long end() {
        long b = x.end();
        for (Column y : ys) {
            b = Math.min(b, y.end());
        }
        return b;
    }

    @Override
    protected boolean poll() {
        boolean changed = x.version() != seenX;
        for (int i = 0; i < ys.length; i++) {
            changed |= ys[i].version() != seen[i];
        }
        if (!changed) {
            return false;
        }
        for (int i = 0; i < ys.length; i++) {
            seen[i] = ys[i].version();
        }
        if (x.version() != seenX) {
            seenX = x.version();
            gaps();
        }
        return true;
    }

    /** Finds whether positions are sorted and their smallest gap. */
    private void gaps() {
        double min = Double.POSITIVE_INFINITY;
        boolean ok = true;
        double previous = Double.NaN;
        for (long i = first(), end = end(); i < end; i++) {
            double v = x.get(i);
            if (previous == previous && v == v) {
                double d = v - previous;
                if (d < 0) {
                    ok = false;
                } else if (d > 0 && d < min) {
                    min = d;
                }
            }
            if (v == v) {
                previous = v;
            }
        }
        sorted = ok;
        foundSpacing = min < Double.POSITIVE_INFINITY ? min : 1;
    }

    /** The space between positions in use. */
    public double space() {
        return spacing == spacing && spacing > 0 ? spacing : foundSpacing;
    }

    private Axis positionAxis() {
        return horizontal ? yAxis : xAxis;
    }

    private Axis valueAxis() {
        return horizontal ? xAxis : yAxis;
    }

    private int shownItems() {
        int n = 0;
        for (int k = 0; k < ys.length; k++) {
            if (itemEnabled(k)) {
                n++;
            }
        }
        return n;
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        double half = space() * 0.5;
        for (long i = first(), end = end(); i < end; i++) {
            double p = x.get(i);
            double pos = 0;
            double neg = 0;
            for (int k = 0; k < ys.length; k++) {
                if (!itemEnabled(k)) {
                    continue;
                }
                double v = ys[k].get(i);
                if (stacked) {
                    if (v > 0) {
                        pos += v;
                    } else if (v < 0) {
                        neg += v;
                    }
                } else {
                    value(bounds, v);
                }
            }
            value(bounds, stacked ? pos : 0);
            value(bounds, stacked ? neg : 0);
            position(bounds, p - half);
            position(bounds, p + half);
        }
    }

    private void value(Bounds b, double v) {
        if (horizontal) {
            b.x(v);
        } else {
            b.y(v);
        }
    }

    private void position(Bounds b, double v) {
        if (horizontal) {
            b.y(v);
        } else {
            b.x(v);
        }
    }

    /** The visible index range along the position axis. */
    private long from() {
        Axis p = positionAxis();
        if (!sorted) {
            return first();
        }
        double lo = Math.min(p.range().min(), p.range().max()) - space();
        return Decimator.lowerBound(x, lo, first(), end());
    }

    private long to() {
        Axis p = positionAxis();
        if (!sorted) {
            return end();
        }
        double hi = Math.max(p.range().min(), p.range().max()) + space();
        return Decimator.lowerBound(x, hi, first(), end());
    }

    @Override
    protected void paint(Canvas c) {
        ResolvedStyle s = style();
        float r = s.get(Styles.RADIUS).topLeft() * c.scale();
        com.truesim.ui.style.value.Border border = s.get(Styles.BORDER);
        float bw = border.width() * c.scale();
        long a = from();
        long b = Math.min(to(), from() + (long) ChartTunables.MAX_POINTS.get());
        for (long i = a; i < b; i++) {
            bar(c, i, s, r, bw, border.color().rgba(), -1, 0, null);
        }
    }

    /**
     * Draws (or with a {@code hit} to fill, hit-tests) the bars at index {@code i}: true when one contains
     * {@code hitX, hitY}, filling {@code hit}.
     */
    private boolean bar(Canvas c, long i, ResolvedStyle s, float r, float bw, long borderColor, float hitX, float hitY,
        Hit hit) {
        double p = x.get(i);
        if (!finite(p)) {
            return false;
        }
        int shown = shownItems();
        if (shown == 0) {
            return false;
        }
        double width = space() * s.getFloat(ChartStyles.BAR_WIDTH);
        double slot = stacked ? width : width / shown;
        double pos = 0;
        double neg = 0;
        int k2 = 0;
        Axis pa = positionAxis();
        Axis va = valueAxis();
        for (int k = 0; k < ys.length; k++) {
            if (!itemEnabled(k)) {
                continue;
            }
            double v = ys[k].get(i);
            if (!finite(v)) {
                k2++;
                continue;
            }
            double lo;
            double hi;
            if (stacked) {
                if (v >= 0) {
                    lo = pos;
                    pos += v;
                    hi = pos;
                } else {
                    hi = neg;
                    neg += v;
                    lo = neg;
                }
            } else {
                lo = Math.min(0, v);
                hi = Math.max(0, v);
            }
            double start = stacked ? p - width * 0.5 : p - width * 0.5 + slot * k2;
            double end = start + slot;
            float p0 = pa.toPixel(start);
            float p1 = pa.toPixel(end);
            float v0 = va.toPixel(lo);
            float v1 = va.toPixel(hi);
            float x0 = horizontal ? v0 : p0;
            float x1 = horizontal ? v1 : p1;
            float y0 = horizontal ? p0 : v0;
            float y1 = horizontal ? p1 : v1;
            if (hit != null) {
                if (hitX >= Math.min(x0, x1) && hitX <= Math.max(x0, x1) && hitY >= Math.min(y0, y1)
                    && hitY <= Math.max(y0, y1)) {
                    hit.series = this;
                    hit.item = k;
                    hit.index = i;
                    hit.x = p;
                    hit.y = v;
                    hit.px = horizontal ? va.toPixel(stacked ? (v >= 0 ? hi : lo) : v) : (p0 + p1) * 0.5f;
                    hit.py = horizontal ? (p0 + p1) * 0.5f : va.toPixel(stacked ? (v >= 0 ? hi : lo) : v);
                    hit.distance = 0;
                    return true;
                }
            } else {
                long fill = c.fade(fill(s, k));
                c.box(x0, y0, x1, y1, fill, Math.min(r, Math.abs(p1 - p0) * 0.5f), bw, c.fade(borderColor));
            }
            k2++;
        }
        return false;
    }

    @Override
    protected boolean contains(float px, float py, Hit hit) {
        ResolvedStyle s = style();
        Axis pa = positionAxis();
        if (sorted) {
            double v = pa.fromPixel(horizontal ? py : px);
            long i = Decimator.lowerBound(x, v, first(), end());
            for (long j = Math.max(first(), i - 1); j <= Math.min(end() - 1, i); j++) {
                if (bar(null, j, s, 0, 0, 0, px, py, hit)) {
                    return true;
                }
            }
            return false;
        }
        for (long j = first(), end = end(); j < end; j++) {
            if (bar(null, j, s, 0, 0, 0, px, py, hit)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean nearest(float px, float py, float maxPixels, boolean byX, Hit hit) {
        if (!sorted || end() <= first()) {
            return false;
        }
        Axis pa = positionAxis();
        double v = pa.fromPixel(horizontal ? py : px);
        long i = Decimator.lowerBound(x, v, first(), end());
        long best = i >= end() ? end() - 1 : i;
        if (i > first() && i < end() && Math.abs(pa.toPixel(x.get(i - 1)) - (horizontal ? py : px))
            < Math.abs(pa.toPixel(x.get(i)) - (horizontal ? py : px))) {
            best = i - 1;
        }
        int item = 0;
        while (item < ys.length - 1 && !itemEnabled(item)) {
            item++;
        }
        double value = ys[item].get(best);
        hit.series = this;
        hit.item = item;
        hit.index = best;
        hit.x = x.get(best);
        hit.y = value;
        float pp = pa.toPixel(hit.x);
        float vp = valueAxis().toPixel(value);
        hit.px = horizontal ? vp : pp;
        hit.py = horizontal ? pp : vp;
        hit.distance = Math.abs(pp - (horizontal ? py : px));
        return hit.distance <= maxPixels;
    }

    @Override
    protected void describe(Hit hit, StringBuilder out) {
        valueAxis().format(out, hit.y);
    }

    @Override
    protected void swatch(Canvas canvas, int item, float x0, float y0, float x1, float y1) {
        ResolvedStyle s = style();
        canvas.box(x0, y0, x1, y1, canvas.fade(fill(s, item)), Math.min(x1 - x0, y1 - y0) * 0.2f, 0, Rgba.TRANSPARENT);
    }
}
