package com.truesim.ui.charts;

import com.truesim.ui.controls.charts.ChartStyles;
import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.text.TextBlock;

/**
 * Text at a data point: plain text centred on it (style part {@code chart-text}), or a callout (part
 * {@code chart-annotation}: background, border, radius, padding, font, colour, and a leader line in {@code stroke})
 * offset from it and kept inside the plot. Drawn over the series; moves with the axes.
 */
public final class Annotation {

    private final Chart chart;
    private final Element part;
    private Axis xAxis;
    private Axis yAxis;
    private double x;
    private double y;
    private String text;
    private float dx;
    private float dy;
    private final boolean clamp;
    private boolean enabled = true;
    private TextBlock block;
    private long version = 1;
    private long seen;

    @AllowAllocation("annotations are made when a chart is built")
    Annotation(Chart chart, String type, double x, double y, String text, float dx, float dy, boolean clamp) {
        this.chart = chart;
        this.part = chart.ui().part(chart.element(), type, null);
        this.xAxis = chart.x();
        this.yAxis = chart.y();
        this.x = x;
        this.y = y;
        this.text = text == null ? "" : text;
        this.dx = dx;
        this.dy = dy;
        this.clamp = clamp;
    }

    /** The style part. */
    public Element part() {
        return part;
    }

    /** Whether it shows. */
    public boolean enabled() {
        return enabled;
    }

    /** Shows or hides it; returns this. */
    public Annotation enabled(boolean on) {
        enabled = on;
        version++;
        return this;
    }

    /** The text. */
    public String text() {
        return text;
    }

    /** Sets the text; returns this. */
    public Annotation text(String value) {
        text = value == null ? "" : value;
        version++;
        return this;
    }

    /** Moves it to the data point {@code newX, newY}; returns this. */
    public Annotation at(double newX, double newY) {
        x = newX;
        y = newY;
        version++;
        return this;
    }

    /** Places it against {@code newX} and {@code newY}; returns this. */
    public Annotation axes(Axis newX, Axis newY) {
        xAxis = newX;
        yAxis = newY;
        version++;
        return this;
    }

    boolean poll() {
        if (version == seen) {
            return false;
        }
        seen = version;
        return true;
    }

    long textVersion() {
        return block == null ? 0 : block.version();
    }

    void paint(Canvas c) {
        if (block == null) {
            block = chart.newBlock();
            if (block == null) {
                return;
            }
        }
        ResolvedStyle s = chart.styleOf(part);
        chart.shape(block, text, s);
        float px = xAxis.toPixel(x);
        float py = yAxis.toPixel(y);
        if (!(px >= chart.plotLeft() - 1 && px <= chart.plotRight() + 1 && py >= chart.plotTop() - 1
            && py <= chart.plotBottom() + 1)) {
            return;
        }
        float scale = chart.scale();
        Insets pad = s.get(Styles.PADDING);
        float w = block.width() + (pad.left() + pad.right()) * scale;
        float h = block.height() + (pad.top() + pad.bottom()) * scale;
        float ox = dx * scale;
        float oy = dy * scale;
        float bx;
        float by;
        if (ox == 0 && oy == 0) {
            bx = px - w * 0.5f;
            by = py - h * 0.5f;
        } else {
            bx = ox >= 0 ? px + ox : px + ox - w;
            by = oy >= 0 ? py + oy : py + oy - h;
        }
        if (clamp) {
            bx = Math.max(chart.plotLeft(), Math.min(chart.plotRight() - w, bx));
            by = Math.max(chart.plotTop(), Math.min(chart.plotBottom() - h, by));
        }
        if (ox != 0 || oy != 0) {
            c.stroke(s, s.rgba(ChartStyles.STROKE));
            if (c.stroking()) {
                float ex = Math.max(bx, Math.min(bx + w, px));
                float ey = Math.max(by, Math.min(by + h, py));
                c.line(px, py, ex, ey);
            }
        }
        chart.boxStyled(c, s, bx, by, bx + w, by + h);
        c.text(block, bx + pad.left() * scale, by + pad.top() * scale, c.fade(s.rgba(Styles.COLOR)));
    }
}
