package com.truesim.ui.charts;

import com.truesim.ui.controls.charts.ChartStyles;
import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedDouble;
import com.truesim.ui.style.ResolvedStyle;

/**
 * A line across the plot the user drags (style part {@code chart-drag-line}: {@code stroke}, {@code line-width},
 * {@code line-dash}): vertical at an X value or horizontal at a Y value, bound two-way to it.
 */
public final class DragLine extends DragTool {

    private final VersionedDouble value;
    private final boolean vertical;
    private float grabD;

    @AllowAllocation("drag tools are made when a chart is built")
    DragLine(Chart chart, Axis xAxis, Axis yAxis, VersionedDouble value, boolean vertical) {
        super(chart, "chart-drag-line", xAxis, yAxis);
        this.value = value;
        this.vertical = vertical;
    }

    /** The value. */
    public VersionedDouble value() {
        return value;
    }

    /** Whether it is vertical (an X value). */
    public boolean vertical() {
        return vertical;
    }

    private Axis axis() {
        return vertical ? xAxis : yAxis;
    }

    @Override
    protected long versions() {
        return value.version();
    }

    @Override
    protected float distance(float px, float py) {
        if (px < chart.plotLeft() || px > chart.plotRight() || py < chart.plotTop() || py > chart.plotBottom()) {
            return Float.POSITIVE_INFINITY;
        }
        return Math.abs(axis().toPixel(value.get()) - (vertical ? px : py));
    }

    @Override
    protected void grab(float px, float py) {
        grabD = axis().toPixel(value.get()) - (vertical ? px : py);
    }

    @Override
    protected void drag(float px, float py) {
        value.set(axis().fromPixel((vertical ? px : py) + grabD));
    }

    @Override
    protected void paint(Canvas c) {
        ResolvedStyle s = style();
        c.stroke(s, s.rgba(ChartStyles.STROKE));
        if (!c.stroking()) {
            return;
        }
        float p = axis().toPixel(value.get());
        if (vertical) {
            c.line(p, c.top(), p, c.bottom());
        } else {
            c.line(c.left(), p, c.right(), p);
        }
    }
}
