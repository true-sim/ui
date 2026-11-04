package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.style.ResolvedStyle;

/**
 * A heatmap (style part {@code chart-heatmap}): a grid of values (row-major, the first row at the top) over a data
 * rectangle, each cell coloured by the chart's {@code colormap} between the scale's ends (the values' extent unless
 * {@link #scale(double, double)} fixes them), drawn as one textured image; {@code fill-opacity} fades it. Cells are hit
 * by position.
 */
public class HeatmapSeries extends Series {

    protected final HeatTexture heat = new HeatTexture();
    private Column values;
    protected int rows;
    protected int columns;
    protected double x0;
    protected double y0;
    protected double x1;
    protected double y1;
    private double scaleMin = Double.NaN;
    private double scaleMax = Double.NaN;
    private long seen = Long.MIN_VALUE;
    private long colormapSeen = Long.MIN_VALUE;
    private float opacitySeen = -1;
    private boolean dirty = true;

    @AllowAllocation("series are made when a chart is built")
    HeatmapSeries(Chart chart, String name, Axis xAxis, Axis yAxis, Column values, int rows, int columns, double x0,
        double y0, double x1, double y1) {
        this(chart, "chart-heatmap", name, xAxis, yAxis, values, rows, columns, x0, y0, x1, y1);
    }

    @AllowAllocation("series are made when a chart is built")
    HeatmapSeries(Chart chart, String type, String name, Axis xAxis, Axis yAxis, Column values, int rows, int columns,
        double x0, double y0, double x1, double y1) {
        super(chart, type, name, null, xAxis, yAxis);
        this.values = values;
        this.rows = rows;
        this.columns = columns;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    /** Fixes the colour scale's ends (NaN: the values' extent); returns this. */
    public HeatmapSeries scale(double min, double max) {
        scaleMin = min;
        scaleMax = max;
        dirty = true;
        chart.changed();
        return this;
    }

    /** The colour scale's low end in use. */
    public double scaleMin() {
        return heat.min();
    }

    /** The colour scale's high end in use. */
    public double scaleMax() {
        return heat.max();
    }

    /** Fills the grid from the data: the values column, row-major. Kinds that derive it override. */
    protected void fillGrid() {
        heat.size(rows, columns);
        double[] v = heat.values();
        long first = values.first();
        for (int i = 0; i < rows * columns; i++) {
            long index = first + i;
            v[i] = index < values.end() ? values.get(index) : Double.NaN;
        }
    }

    /** Whether the data changed since the grid was filled. */
    protected boolean dataChanged() {
        if (values.version() != seen) {
            seen = values.version();
            return true;
        }
        return false;
    }

    @Override
    protected boolean poll() {
        boolean data = dataChanged();
        float opacity = style().getFloat(ChartStyles.FILL_OPACITY);
        long cm = chart.colormapStamp();
        if (!data && !dirty && cm == colormapSeen && opacity == opacitySeen) {
            return false;
        }
        if (data || dirty) {
            fillGrid();
        }
        dirty = false;
        colormapSeen = cm;
        opacitySeen = opacity;
        heat.color(chart, scaleMin, scaleMax, opacity);
        return true;
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        bounds.point(x0, y0);
        bounds.point(x1, y1);
    }

    @Override
    protected void paint(Canvas c) {
        if (!itemEnabled(0) || heat.texture() == null) {
            return;
        }
        float px0 = xAxis.toPixel(x0);
        float px1 = xAxis.toPixel(x1);
        float py0 = yAxis.toPixel(y1); // the first row at the top
        float py1 = yAxis.toPixel(y0);
        c.image(Math.min(px0, px1), Math.min(py0, py1), Math.max(px0, px1), Math.max(py0, py1), px0 <= px1 ? 0 : 1,
            py0 <= py1 ? 0 : 1, px0 <= px1 ? 1 : 0, py0 <= py1 ? 1 : 0, heat.texture().id(),
            c.fade(Rgba.WHITE));
    }

    @Override
    protected boolean contains(float px, float py, Hit hit) {
        if (heat.rows() == 0 || heat.columns() == 0) {
            return false;
        }
        double vx = xAxis.fromPixel(px);
        double vy = yAxis.fromPixel(py);
        double fx = (vx - x0) / (x1 - x0);
        double fy = (y1 - vy) / (y1 - y0);
        if (!(fx >= 0 && fx < 1 && fy >= 0 && fy < 1)) {
            return false;
        }
        int c = (int) (fx * heat.columns());
        int r = (int) (fy * heat.rows());
        hit.series = this;
        hit.item = 0;
        hit.index = (long) r * heat.columns() + c;
        hit.x = x0 + (c + 0.5) * (x1 - x0) / heat.columns();
        hit.y = y1 - (r + 0.5) * (y1 - y0) / heat.rows();
        hit.px = xAxis.toPixel(hit.x);
        hit.py = yAxis.toPixel(hit.y);
        hit.distance = 0;
        return true;
    }

    /** The value of cell {@code index} (row-major). */
    public double cell(long index) {
        return heat.values()[(int) index];
    }

    @Override
    protected void describe(Hit hit, StringBuilder out) {
        com.truesim.ui.controls.data.Format.append(out, cell(hit.index), 3);
    }

    @Override
    protected void swatch(Canvas canvas, int item, float sx0, float sy0, float sx1, float sy1) {
        ResolvedStyle s = style();
        float w = (sx1 - sx0) / 3;
        for (int i = 0; i < 3; i++) {
            long col = Rgba.fade(chart.colormap(i / 2.0), s.getFloat(ChartStyles.FILL_OPACITY));
            canvas.box(sx0 + i * w, sy0, sx0 + (i + 1) * w, sy1, canvas.fade(col), 0, 0, 0);
        }
    }
}
