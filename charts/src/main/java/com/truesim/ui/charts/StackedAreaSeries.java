package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.ResolvedStyle;
import java.util.List;

/**
 * Areas stacked on one another (style part {@code chart-stacked}): each Y column a layer from the sum of the ones
 * below it to that sum plus its own values, a legend entry each (a hidden layer leaves the stack). The sums are derived
 * when the data or the hidden layers change, with a min-max pyramid each, so many samples draw per pixel column.
 */
public final class StackedAreaSeries extends Series {

    private final List<String> names;
    private final Column x;
    private final Column[] ys;
    private final long[] seen;
    private long seenX = Long.MIN_VALUE;
    private int seenHidden = -1;
    private Columns.Values xs = Columns.of(new double[0]);
    private Columns.Values[] sums;
    private Tracked[] tracked;
    private int count;
    private final Decimator decimator = new Decimator();

    @AllowAllocation("series are made when a chart is built")
    StackedAreaSeries(Chart chart, List<String> names, Axis xAxis, Axis yAxis, Column x, Column... ys) {
        super(chart, "chart-stacked", names.isEmpty() ? "" : names.get(0), null, xAxis, yAxis);
        if (names.size() != ys.length || ys.length == 0) {
            throw new IllegalArgumentException("a name for each of the " + ys.length + " columns: " + names);
        }
        this.names = List.copyOf(names);
        this.x = x;
        this.ys = ys.clone();
        this.seen = new long[ys.length];
        java.util.Arrays.fill(seen, Long.MIN_VALUE);
        sums = new Columns.Values[ys.length];
        tracked = new Tracked[ys.length];
        for (int i = 0; i < ys.length; i++) {
            sums[i] = Columns.of(new double[0]);
            tracked[i] = new Tracked(sums[i]);
        }
    }

    @Override
    public int items() {
        return ys.length;
    }

    @Override
    public String item(int item) {
        return names.get(item);
    }

    private int hiddenMask() {
        int m = 0;
        for (int i = 0; i < ys.length && i < 31; i++) {
            if (!itemEnabled(i)) {
                m |= 1 << i;
            }
        }
        return m;
    }

    @Override
    protected boolean poll() {
        boolean changed = x.version() != seenX || hiddenMask() != seenHidden;
        for (int i = 0; i < ys.length; i++) {
            changed |= ys[i].version() != seen[i];
        }
        if (!changed) {
            return false;
        }
        seenX = x.version();
        seenHidden = hiddenMask();
        for (int i = 0; i < ys.length; i++) {
            seen[i] = ys[i].version();
        }
        derive();
        return true;
    }

    @AllowAllocation("the sums are derived when the data changes")
    private void derive() {
        long a = x.first();
        long b = x.end();
        for (Column y : ys) {
            a = Math.max(a, y.first());
            b = Math.min(b, y.end());
        }
        count = (int) Math.max(0, b - a);
        double[] xv = xs.values().length >= count ? xs.values() : new double[count];
        for (int j = 0; j < count; j++) {
            xv[j] = x.get(a + j);
        }
        xs.replace(xv, count);
        double[] below = null;
        for (int i = 0; i < ys.length; i++) {
            double[] s = sums[i].values().length >= count ? sums[i].values() : new double[count];
            boolean on = itemEnabled(i);
            for (int j = 0; j < count; j++) {
                double v = on ? ys[i].get(a + j) : 0;
                s[j] = (below == null ? 0 : below[j]) + (v == v ? v : 0);
            }
            sums[i].replace(s, count);
            tracked[i].invalidate();
            tracked[i].poll();
            below = s;
        }
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        if (count == 0) {
            return;
        }
        bounds.x(xs.get(0));
        bounds.x(xs.get(count - 1));
        bounds.y(0);
        for (int i = 0; i < ys.length; i++) {
            if (tracked[i].pyramid.query(0, count)) {
                bounds.y(tracked[i].pyramid.min());
                bounds.y(tracked[i].pyramid.max());
            }
        }
    }

    private long from() {
        double lo = Math.min(xAxis.range().min(), xAxis.range().max());
        return Math.max(0, Decimator.lowerBound(xs, lo, 0, count) - 1);
    }

    private long to() {
        double hi = Math.max(xAxis.range().min(), xAxis.range().max());
        return Math.min(count, Decimator.lowerBound(xs, hi, 0, count) + 1);
    }

    @Override
    protected void paint(Canvas c) {
        if (count == 0) {
            return;
        }
        ResolvedStyle s = style();
        long a = from();
        long b = to();
        boolean dense = b - a > (c.right() - c.left()) * ChartTunables.DECIMATE_ABOVE.get();
        for (int i = 0; i < ys.length; i++) {
            if (!itemEnabled(i)) {
                continue;
            }
            long fill = c.fade(fill(s, i));
            Columns.Values upper = sums[i];
            Columns.Values lower = i == 0 ? null : sums[i - 1];
            if (dense) {
                decimator.minMax(xs, upper, tracked[i].pyramid, a, b, xAxis, (int) Math.floor(c.left()),
                    (int) Math.ceil(c.right()));
                for (int k = 0; k < decimator.columns(); k++) {
                    double hi = decimator.maxY(k);
                    double lo = 0;
                    if (lower != null && tracked[i - 1].pyramid.query(decimator.from(k), decimator.to(k))) {
                        lo = tracked[i - 1].pyramid.min();
                    }
                    float cx = decimator.pixel(k);
                    c.box(cx - 0.5f, yAxis.toPixel(hi), cx + 0.5f, yAxis.toPixel(lo), fill, 0, 0, 0);
                }
                continue;
            }
            float px = Float.NaN;
            float pu = 0;
            float pl = 0;
            for (long j = a; j < b; j++) {
                float sx = xAxis.toPixel(xs.get(j));
                float su = yAxis.toPixel(upper.get(j));
                float sl = yAxis.toPixel(lower == null ? 0 : lower.get(j));
                if (px == px) {
                    ShadedSeries.band(c, px, pu, pl, sx, su, sl, fill);
                }
                px = sx;
                pu = su;
                pl = sl;
            }
            c.stroke(s, color(i));
            if (c.stroking()) {
                c.moveTo(Float.NaN, Float.NaN);
                for (long j = a; j < b; j++) {
                    c.lineTo(xAxis.toPixel(xs.get(j)), yAxis.toPixel(upper.get(j)));
                }
            }
        }
    }

    @Override
    protected boolean nearest(float px, float py, float maxPixels, boolean byX, Hit hit) {
        if (count == 0) {
            return false;
        }
        double vx = xAxis.fromPixel(px);
        long i = Decimator.lowerBound(xs, vx, 0, count);
        long best = i >= count ? count - 1 : i;
        if (i > 0 && i < count && Math.abs(xAxis.toPixel(xs.get(i - 1)) - px) < Math.abs(xAxis.toPixel(xs.get(i)) - px)) {
            best = i - 1;
        }
        int layer = -1;
        double value = yAxis.fromPixel(py);
        for (int k = 0; k < ys.length; k++) {
            if (itemEnabled(k)) {
                layer = k;
                if (value <= sums[k].get(best)) {
                    break;
                }
            }
        }
        if (layer < 0) {
            return false;
        }
        hit.series = this;
        hit.item = layer;
        hit.index = best;
        hit.x = xs.get(best);
        hit.y = sums[layer].get(best) - (layer == 0 ? 0 : sums[layer - 1].get(best));
        hit.px = xAxis.toPixel(hit.x);
        hit.py = yAxis.toPixel(sums[layer].get(best));
        hit.distance = byX ? Math.abs(hit.px - px) : Math.sqrt((hit.px - px) * (hit.px - px)
            + (hit.py - py) * (hit.py - py));
        return hit.distance <= maxPixels;
    }

    @Override
    protected void swatch(Canvas canvas, int item, float x0, float y0, float x1, float y1) {
        canvas.box(x0, y0, x1, y1, canvas.fade(fill(style(), item)), 0, 0, 0);
    }
}
