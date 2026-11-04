package com.truesim.ui.charts;

import com.truesim.ui.controls.charts.ChartStyles;
import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedDouble;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.style.ResolvedStyle;

/**
 * A rectangle the user moves (grabbed inside) and resizes (grabbed at an edge or corner): a query range, bound two-way
 * to its four edges (style part {@code chart-drag-rect}, or {@code chart-selection} for the chart's box selection:
 * {@code fill} and {@code fill-opacity}, {@code stroke}, {@code line-width}, {@code line-dash}).
 */
public final class DragRect extends DragTool {

    private final VersionedDouble x0;
    private final VersionedDouble y0;
    private final VersionedDouble x1;
    private final VersionedDouble y1;
    private int edges; // grabbed: 1 left, 2 right, 4 bottom, 8 top; 15 the whole
    private double grabX;
    private double grabY;

    @AllowAllocation("drag tools are made when a chart is built")
    DragRect(Chart chart, Axis xAxis, Axis yAxis, VersionedDouble x0, VersionedDouble y0, VersionedDouble x1,
        VersionedDouble y1, String type) {
        super(chart, type, xAxis, yAxis);
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    /** The left edge (the smaller X). */
    public VersionedDouble x0() {
        return x0;
    }

    /** The bottom edge (the smaller Y). */
    public VersionedDouble y0() {
        return y0;
    }

    /** The right edge. */
    public VersionedDouble x1() {
        return x1;
    }

    /** The top edge. */
    public VersionedDouble y1() {
        return y1;
    }

    /** Sets the edges (sorted). */
    public void set(double ax, double ay, double bx, double by) {
        x0.set(Math.min(ax, bx));
        x1.set(Math.max(ax, bx));
        y0.set(Math.min(ay, by));
        y1.set(Math.max(ay, by));
    }

    @Override
    protected long versions() {
        return ((x0.version() * 31 + y0.version()) * 31 + x1.version()) * 31 + y1.version();
    }

    private float left() {
        return Math.min(xAxis.toPixel(x0.get()), xAxis.toPixel(x1.get()));
    }

    private float right() {
        return Math.max(xAxis.toPixel(x0.get()), xAxis.toPixel(x1.get()));
    }

    private float top() {
        return Math.min(yAxis.toPixel(y0.get()), yAxis.toPixel(y1.get()));
    }

    private float bottom() {
        return Math.max(yAxis.toPixel(y0.get()), yAxis.toPixel(y1.get()));
    }

    @Override
    protected float distance(float px, float py) {
        float dx = Math.max(0, Math.max(left() - px, px - right()));
        float dy = Math.max(0, Math.max(top() - py, py - bottom()));
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    protected void grab(float px, float py) {
        float g = ChartTunables.GRAB_PIXELS.get();
        edges = 0;
        boolean leftToRight = xAxis.toPixel(x0.get()) <= xAxis.toPixel(x1.get());
        boolean bottomUp = yAxis.toPixel(y0.get()) >= yAxis.toPixel(y1.get());
        if (Math.abs(px - left()) <= g) {
            edges |= leftToRight ? 1 : 2;
        } else if (Math.abs(px - right()) <= g) {
            edges |= leftToRight ? 2 : 1;
        }
        if (Math.abs(py - bottom()) <= g) {
            edges |= bottomUp ? 4 : 8;
        } else if (Math.abs(py - top()) <= g) {
            edges |= bottomUp ? 8 : 4;
        }
        if (edges == 0) {
            edges = 15;
        }
        grabX = xAxis.fromPixel(px);
        grabY = yAxis.fromPixel(py);
    }

    @Override
    protected void drag(float px, float py) {
        double vx = xAxis.fromPixel(px);
        double vy = yAxis.fromPixel(py);
        if (edges == 15) {
            double dx = vx - grabX;
            double dy = vy - grabY;
            x0.set(x0.get() + dx);
            x1.set(x1.get() + dx);
            y0.set(y0.get() + dy);
            y1.set(y1.get() + dy);
            grabX = vx;
            grabY = vy;
            return;
        }
        if ((edges & 1) != 0) {
            x0.set(vx);
        }
        if ((edges & 2) != 0) {
            x1.set(vx);
        }
        if ((edges & 4) != 0) {
            y0.set(vy);
        }
        if ((edges & 8) != 0) {
            y1.set(vy);
        }
    }

    @Override
    protected void release() {
        set(x0.get(), y0.get(), x1.get(), y1.get()); // edges dragged past each other swap back
    }

    @Override
    protected void paint(Canvas c) {
        ResolvedStyle s = style();
        float l = left();
        float r = right();
        float t = top();
        float b = bottom();
        long fill = Rgba.fade(s.rgba(ChartStyles.FILL), s.getFloat(ChartStyles.FILL_OPACITY));
        if (Rgba.a(fill) > 0) {
            c.box(l, t, r, b, c.fade(fill), 0, 0, 0);
        }
        c.stroke(s, s.rgba(ChartStyles.STROKE));
        if (c.stroking()) {
            c.moveTo(l, t);
            c.lineTo(r, t);
            c.lineTo(r, b);
            c.lineTo(l, b);
            c.lineTo(l, t);
        }
    }
}
