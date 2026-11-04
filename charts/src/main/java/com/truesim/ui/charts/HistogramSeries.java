package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import java.util.List;

/**
 * A histogram of a column's values (style part {@code chart-histogram}): counts in equal bins over the data's range
 * (or a range given), binned again when the data changes, drawn as adjacent bars; optionally as densities (the bars'
 * area is 1) or cumulative. Bins with {@code 0} bins asked for follow the square-root rule.
 */
public final class HistogramSeries extends BarSeries {

    private final Column source;
    private int bins;
    private double rangeMin = Double.NaN;
    private double rangeMax = Double.NaN;
    private boolean density;
    private boolean cumulative;
    private long seenSource = Long.MIN_VALUE;
    private final Columns.Values centers = Columns.of(new double[0]);
    private final Columns.Values counts = Columns.of(new double[0]);
    private boolean dirty = true;

    @AllowAllocation("series are made when a chart is built")
    HistogramSeries(Chart chart, String name, Axis xAxis, Axis yAxis, Column values, int bins) {
        super(chart, "chart-histogram", List.of(name == null ? "" : name), xAxis, yAxis, Columns.of(new double[0]),
            Columns.of(new double[0]));
        this.source = values;
        this.bins = Math.max(0, bins);
        this.x = centers;
        this.ys[0] = counts;
    }

    /** Bins over {@code min..max} instead of the data's range (NaN: the data's); returns this. */
    public HistogramSeries range(double min, double max) {
        rangeMin = min;
        rangeMax = max;
        dirty = true;
        chart.changed();
        return this;
    }

    /** The number of bins (0: the square-root rule); returns this. */
    public HistogramSeries bins(int count) {
        bins = Math.max(0, count);
        dirty = true;
        chart.changed();
        return this;
    }

    /** Shows densities (counts over total times bin width) instead of counts; returns this. */
    public HistogramSeries density(boolean on) {
        density = on;
        dirty = true;
        chart.changed();
        return this;
    }

    /** Shows cumulative counts; returns this. */
    public HistogramSeries cumulative(boolean on) {
        cumulative = on;
        dirty = true;
        chart.changed();
        return this;
    }

    /** How many bins the histogram has now. */
    public int binCount() {
        return (int) counts.size();
    }

    /** Bin {@code i}'s value (count, density or cumulative). */
    public double bin(int i) {
        return counts.get(i);
    }

    /** Bin {@code i}'s centre. */
    public double center(int i) {
        return centers.get(i);
    }

    @Override
    protected boolean poll() {
        if (source.version() != seenSource || dirty) {
            seenSource = source.version();
            dirty = false;
            bin();
        }
        return super.poll();
    }

    @AllowAllocation("binned when the data changes")
    private void bin() {
        long a = source.first();
        long b = source.end();
        double lo = rangeMin;
        double hi = rangeMax;
        long n = 0;
        if (!(lo == lo && hi == hi && hi > lo)) {
            lo = Double.POSITIVE_INFINITY;
            hi = Double.NEGATIVE_INFINITY;
            for (long i = a; i < b; i++) {
                double v = source.get(i);
                if (finite(v)) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                    n++;
                }
            }
            if (!(hi >= lo)) {
                centers.replace(new double[0], 0);
                counts.replace(new double[0], 0);
                return;
            }
            if (hi == lo) {
                lo -= 0.5;
                hi += 0.5;
            }
        } else {
            n = b - a;
        }
        int count = bins > 0 ? bins : (int) Math.max(1, Math.ceil(Math.sqrt(n)));
        double width = (hi - lo) / count;
        double[] c = centers.values().length == count ? centers.values() : new double[count];
        double[] k = counts.values().length == count ? counts.values() : new double[count];
        java.util.Arrays.fill(k, 0);
        long total = 0;
        for (long i = a; i < b; i++) {
            double v = source.get(i);
            if (!(v >= lo && v <= hi)) {
                continue;
            }
            int bin = (int) Math.min(count - 1, Math.floor((v - lo) / width));
            k[bin]++;
            total++;
        }
        for (int i = 0; i < count; i++) {
            c[i] = lo + width * (i + 0.5);
            if (density && total > 0) {
                k[i] /= total * width;
            }
            if (cumulative && i > 0) {
                k[i] += k[i - 1];
            }
        }
        centers.replace(c, count);
        counts.replace(k, count);
        spacing(width);
    }
}
