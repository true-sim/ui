package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedDouble;
import com.truesim.ui.style.ResolvedStyle;

/**
 * A point the user drags (style part {@code chart-drag-point}: {@code marker}, {@code marker-size}, {@code color},
 * {@code marker-outline} and {@code stroke}), bound two-way to an X and a Y value.
 */
public final class DragPoint extends DragTool {

    private final VersionedDouble x;
    private final VersionedDouble y;
    private float grabDx;
    private float grabDy;

    @AllowAllocation("drag tools are made when a chart is built")
    DragPoint(Chart chart, Axis xAxis, Axis yAxis, VersionedDouble x, VersionedDouble y) {
        super(chart, "chart-drag-point", xAxis, yAxis);
        this.x = x;
        this.y = y;
    }

    /** The X value. */
    public VersionedDouble x() {
        return x;
    }

    /** The Y value. */
    public VersionedDouble y() {
        return y;
    }

    @Override
    protected long versions() {
        return x.version() * 31 + y.version();
    }

    @Override
    protected float distance(float px, float py) {
        float dx = xAxis.toPixel(x.get()) - px;
        float dy = yAxis.toPixel(y.get()) - py;
        float r = style().getFloat(ChartStyles.MARKER_SIZE) * chart.scale() * 0.5f;
        return Math.max(0, (float) Math.sqrt(dx * dx + dy * dy) - r);
    }

    @Override
    protected void grab(float px, float py) {
        grabDx = xAxis.toPixel(x.get()) - px;
        grabDy = yAxis.toPixel(y.get()) - py;
    }

    @Override
    protected void drag(float px, float py) {
        x.set(xAxis.fromPixel(px + grabDx));
        y.set(yAxis.fromPixel(py + grabDy));
    }

    @Override
    protected void paint(Canvas c) {
        ResolvedStyle s = style();
        float size = s.getFloat(ChartStyles.MARKER_SIZE) * c.scale();
        Marker m = s.get(ChartStyles.MARKER);
        c.marker(m == Marker.NONE ? Marker.CIRCLE : m, xAxis.toPixel(x.get()), yAxis.toPixel(y.get()), size,
            c.fade(color(s)), s.getFloat(ChartStyles.MARKER_OUTLINE) * c.scale(), c.fade(s.rgba(ChartStyles.STROKE)));
    }
}
