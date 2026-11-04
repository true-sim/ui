package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.ui.core.Element;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.text.TextBlock;
import java.util.List;

/**
 * A pie (style part {@code chart-pie}; a donut when the theme gives it a {@code donut-hole}) of a column's values, a
 * slice and a legend entry each, clockwise from 12 o'clock; hidden slices leave the pie. Its centre is a data point,
 * its radius in units of the X axis (the pie stays round however the axes are scaled). Each slice shows its share as a
 * label (style part {@code chart-pie-label}; {@link #labels(boolean)}). Slices are hit by angle and radius.
 */
public final class PieSeries extends Series {

    private final List<String> names;
    private final Column values;
    private double cx;
    private double cy;
    private double radius;
    private boolean labels = true;
    private long seen = Long.MIN_VALUE;
    private final Element labelPart;
    private TextBlock[] blocks = new TextBlock[0];
    private final StringBuilder text = new StringBuilder(16);

    @AllowAllocation("series are made when a chart is built")
    PieSeries(Chart chart, List<String> names, Axis xAxis, Axis yAxis, Column values, double x, double y,
        double radius) {
        super(chart, "chart-pie", names.isEmpty() ? "" : names.get(0), null, xAxis, yAxis);
        this.names = List.copyOf(names);
        this.values = values;
        this.cx = x;
        this.cy = y;
        this.radius = radius;
        this.labelPart = chart.ui().part(chart.element(), "chart-pie-label", null);
    }

    /** Shows or hides the slices' share labels; returns this. */
    public PieSeries labels(boolean on) {
        labels = on;
        chart.changed();
        return this;
    }

    /** Moves the pie to {@code x, y} with {@code r}; returns this. */
    public PieSeries place(double x, double y, double r) {
        cx = x;
        cy = y;
        radius = r;
        chart.changed();
        return this;
    }

    @Override
    public int items() {
        return names.size();
    }

    @Override
    public String item(int item) {
        return names.get(item);
    }

    private int slices() {
        return (int) Math.min(names.size(), values.size());
    }

    private double value(int i) {
        double v = values.get(values.first() + i);
        return v > 0 && itemEnabled(i) ? v : 0;
    }

    private double total() {
        double t = 0;
        for (int i = 0; i < slices(); i++) {
            t += value(i);
        }
        return t;
    }

    @Override
    protected boolean poll() {
        if (values.version() == seen) {
            return false;
        }
        seen = values.version();
        return true;
    }

    @Override
    protected void bounds(Bounds bounds, boolean visibleX) {
        bounds.point(cx - radius, cy - radius);
        bounds.point(cx + radius, cy + radius);
    }

    private float pixelRadius() {
        return (float) (radius * Math.abs(xAxis.pixelsPerUnit()));
    }

    @Override
    protected void paint(Canvas c) {
        double total = total();
        if (!(total > 0)) {
            return;
        }
        ResolvedStyle s = style();
        float px = xAxis.toPixel(cx);
        float py = yAxis.toPixel(cy);
        float r1 = pixelRadius();
        float r0 = r1 * s.getFloat(ChartStyles.DONUT_HOLE);
        float arc = ChartTunables.ARC_PIXELS.get();
        double angle = 0;
        int n = slices();
        ensureBlocks(n);
        ResolvedStyle ls = chart.styleOf(labelPart);
        c.stroke(s, chart.styleOf(part()).rgba(ChartStyles.STROKE));
        boolean edges = c.stroking();
        for (int i = 0; i < n; i++) {
            double v = value(i);
            if (v <= 0) {
                continue;
            }
            double sweep = 2 * Math.PI * v / total;
            c.wedge(px, py, r0, r1, angle, angle + sweep, c.fade(fill(s, i)), arc);
            if (edges && n > 1) {
                c.line(px + (float) Maths.sin(angle) * r0, py - (float) Maths.cos(angle) * r0,
                    px + (float) Maths.sin(angle) * r1, py - (float) Maths.cos(angle) * r1);
            }
            if (labels && blocks[i] != null) {
                text.setLength(0);
                com.truesim.ui.controls.data.Format.append(text, 100 * v / total, 1).append('%');
                chart.shape(blocks[i], text, ls);
                double mid = angle + sweep * 0.5;
                float lr = (r0 + r1) * 0.5f;
                float lx = px + (float) Maths.sin(mid) * lr - blocks[i].width() * 0.5f;
                float ly = py - (float) Maths.cos(mid) * lr - blocks[i].height() * 0.5f;
                if (sweep * lr > blocks[i].width() * 0.8f) {
                    c.text(blocks[i], lx, ly, c.fade(ls.rgba(Styles.COLOR)));
                }
            }
            angle += sweep;
        }
    }

    @AllowAllocation("label blocks grow with the slices, rarely")
    private void ensureBlocks(int n) {
        if (blocks.length < n) {
            TextBlock[] grown = java.util.Arrays.copyOf(blocks, n);
            for (int i = blocks.length; i < n; i++) {
                grown[i] = chart.newBlock();
            }
            blocks = grown;
        }
    }

    @Override
    protected long textVersion() {
        long sum = 0;
        for (TextBlock b : blocks) {
            if (b != null) {
                sum += b.version();
            }
        }
        return sum;
    }

    @Override
    protected boolean contains(float px, float py, Hit hit) {
        double total = total();
        if (!(total > 0)) {
            return false;
        }
        float ox = xAxis.toPixel(cx);
        float oy = yAxis.toPixel(cy);
        float dx = px - ox;
        float dy = py - oy;
        double d = Math.sqrt(dx * dx + dy * dy);
        float r1 = pixelRadius();
        float r0 = r1 * style().getFloat(ChartStyles.DONUT_HOLE);
        if (d > r1 || d < r0) {
            return false;
        }
        double a = Maths.atan2(dx, -dy); // clockwise from 12 o'clock
        if (a < 0) {
            a += 2 * Math.PI;
        }
        double angle = 0;
        for (int i = 0; i < slices(); i++) {
            double sweep = 2 * Math.PI * value(i) / total;
            if (sweep > 0 && a >= angle && a < angle + sweep) {
                hit.series = this;
                hit.item = i;
                hit.index = i;
                hit.x = cx;
                hit.y = values.get(values.first() + i);
                double mid = angle + sweep * 0.5;
                hit.px = ox + (float) Maths.sin(mid) * (r0 + r1) * 0.5f;
                hit.py = oy - (float) Maths.cos(mid) * (r0 + r1) * 0.5f;
                hit.distance = 0;
                return true;
            }
            angle += sweep;
        }
        return false;
    }

    @Override
    protected void describe(Hit hit, StringBuilder out) {
        com.truesim.ui.controls.data.Format.append(out, hit.y, 3);
    }
}
