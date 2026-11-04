package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;

/**
 * An image over a data rectangle (style part {@code chart-image}: {@code fill-opacity} fades it): a texture of the
 * UI's (a map, a camera frame, a spectrogram computed elsewhere), stretched from {@code x0, y0} (bottom left) to
 * {@code x1, y1}, moving and scaling with the axes.
 */
public final class ImageSeries extends Series {

    private UiTexture texture;
    private double x0;
    private double y0;
    private double x1;
    private double y1;
    private long seen = Long.MIN_VALUE;

    @AllowAllocation("series are made when a chart is built")
    ImageSeries(Chart chart, String name, Axis xAxis, Axis yAxis, UiTexture texture, double x0, double y0, double x1,
        double y1) {
        super(chart, "chart-image", name, null, xAxis, yAxis);
        this.texture = texture;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    /** Shows {@code value} over the rectangle {@code ax, ay} to {@code bx, by}; returns this. */
    public ImageSeries set(UiTexture value, double ax, double ay, double bx, double by) {
        texture = value;
        x0 = ax;
        y0 = ay;
        x1 = bx;
        y1 = by;
        chart.changed();
        return this;
    }

    @Override
    protected boolean poll() {
        long v = texture == null ? 0 : texture.version();
        if (v == seen) {
            return false;
        }
        seen = v;
        return true;
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        bounds.point(x0, y0);
        bounds.point(x1, y1);
    }

    @Override
    protected void paint(Canvas c) {
        if (!itemEnabled(0) || texture == null) {
            return;
        }
        float px0 = xAxis.toPixel(x0);
        float px1 = xAxis.toPixel(x1);
        float py0 = yAxis.toPixel(y1);
        float py1 = yAxis.toPixel(y0);
        long tint = c.fade(Rgba.fade(Rgba.WHITE, style().getFloat(ChartStyles.FILL_OPACITY)));
        c.image(Math.min(px0, px1), Math.min(py0, py1), Math.max(px0, px1), Math.max(py0, py1), px0 <= px1 ? 0 : 1,
            py0 <= py1 ? 0 : 1, px0 <= px1 ? 1 : 0, py0 <= py1 ? 1 : 0, texture.id(), tint);
    }

    @Override
    protected boolean contains(float px, float py, Hit hit) {
        double vx = xAxis.fromPixel(px);
        double vy = yAxis.fromPixel(py);
        if (vx < Math.min(x0, x1) || vx > Math.max(x0, x1) || vy < Math.min(y0, y1) || vy > Math.max(y0, y1)) {
            return false;
        }
        hit.series = this;
        hit.item = 0;
        hit.index = 0;
        hit.x = vx;
        hit.y = vy;
        hit.px = px;
        hit.py = py;
        hit.distance = 0;
        return true;
    }

    @Override
    protected void describe(Hit hit, StringBuilder out) {
        xAxis.format(out, hit.x).append(", ");
        yAxis.format(out, hit.y);
    }
}
