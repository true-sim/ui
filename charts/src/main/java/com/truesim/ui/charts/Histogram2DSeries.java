package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;

/**
 * A 2D histogram (style part {@code chart-histogram2d}): the points of two columns counted in a grid of bins over
 * their extent (or a rectangle given), counted again when the data changes, shown as a heatmap through the colormap.
 */
public final class Histogram2DSeries extends HeatmapSeries {

    private final Column xs;
    private final Column ys;
    private long seenX = Long.MIN_VALUE;
    private long seenY = Long.MIN_VALUE;
    private boolean fixed;
    private final Bounds extent = new Bounds();

    @AllowAllocation("series are made when a chart is built")
    Histogram2DSeries(Chart chart, String name, Axis xAxis, Axis yAxis, Column xs, Column ys, int binsX, int binsY) {
        super(chart, "chart-histogram2d", name, xAxis, yAxis, Columns.of(new double[0]), Math.max(1, binsY),
            Math.max(1, binsX), 0, 0, 1, 1);
        this.xs = xs;
        this.ys = ys;
    }

    /** Bins over the data rectangle {@code minX, minY} to {@code maxX, maxY} instead of the data's extent. */
    public Histogram2DSeries range(double minX, double minY, double maxX, double maxY) {
        x0 = minX;
        y0 = minY;
        x1 = maxX;
        y1 = maxY;
        fixed = true;
        seenX = Long.MIN_VALUE;
        chart.changed();
        return this;
    }

    @Override
    protected boolean dataChanged() {
        if (xs.version() != seenX || ys.version() != seenY) {
            seenX = xs.version();
            seenY = ys.version();
            return true;
        }
        return false;
    }

    @Override
    protected void fillGrid() {
        heat.size(rows, columns);
        double[] v = heat.values();
        java.util.Arrays.fill(v, 0, rows * columns, 0);
        long a = Math.max(xs.first(), ys.first());
        long b = Math.min(xs.end(), ys.end());
        if (!fixed) {
            Bounds e = extent.clear();
            for (long i = a; i < b; i++) {
                e.point(xs.get(i), ys.get(i));
            }
            if (!e.hasX() || !e.hasY()) {
                return;
            }
            x0 = e.minX;
            x1 = e.maxX > e.minX ? e.maxX : e.minX + 1;
            y0 = e.minY;
            y1 = e.maxY > e.minY ? e.maxY : e.minY + 1;
        }
        for (long i = a; i < b; i++) {
            double fx = (xs.get(i) - x0) / (x1 - x0);
            double fy = (y1 - ys.get(i)) / (y1 - y0);
            if (fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1) {
                int c = (int) Math.min(columns - 1, fx * columns);
                int r = (int) Math.min(rows - 1, fy * rows);
                v[r * columns + c]++;
            }
        }
    }
}
