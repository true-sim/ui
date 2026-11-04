package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.ui.core.Element;
import com.truesim.ui.text.TextBlock;
import java.util.List;

/**
 * One axis of a {@link Chart}: horizontal (an X axis, at the bottom or top) or vertical (a Y axis, left or right), with
 * a {@link Scale}, a versioned {@link AxisRange} (shared with other axes to link them), a title and ticks. A chart has
 * any number of axes; series name the X and Y axis they are drawn against. Axes on the same side stack outwards in the
 * order they were added.
 *
 * <p>The mapping to pixels goes through the scale's transform (the identity, {@code log10}, or the symmetric
 * logarithm), so zooming and panning a log axis behave evenly on screen. Pixels are relative to the chart's content
 * box; a Y axis grows upwards unless {@linkplain #inverted inverted}.
 *
 * <p>Its look is the theme's: the style part {@code chart-axis} (with the element id the axis's id and the fact
 * {@code side}) gives the tick label font and colour, {@code stroke} and {@code line-width} for the axis line and
 * ticks, {@code tick-length}, {@code minor-tick-length}, {@code tick-spacing}, {@code minor-ticks}, {@code gap}
 * (between ticks and labels, and between stacked axes), {@code fit-padding}, and {@code background} under the axis
 * when it is {@code :hover}ed or {@code :selected}; {@code chart-axis-title} the title's font and colour.
 */
public final class Axis {

    /** Where an axis lies. */
    public enum Side {
        /** Below the plot (an X axis). */
        BOTTOM,
        /** Above the plot (an X axis). */
        TOP,
        /** Left of the plot (a Y axis). */
        LEFT,
        /** Right of the plot (a Y axis). */
        RIGHT
    }

    private final Chart chart;
    private final String id;
    private final Side side;
    private final Scale scale;
    private AxisRange range;
    private boolean enabled = true;
    private String title = "";
    private List<String> categories = List.of();
    private double symlogConstant = 1;
    private double timeOffset;
    private boolean inverted;
    private boolean autoFit;
    private boolean fits = true;
    private boolean selected;
    private boolean follow;
    private double followSpan = Double.NaN;

    // the mapping, set when the chart lays out
    private float p0;
    private float p1 = 1;
    private double f0;
    private double f1 = 1;
    private double k = 1;
    private long mappedVersion = -1;

    // what the chart keeps per axis: style part, ticks, text, geometry
    final Element part;
    final Element titlePart;
    final Ticks ticks = new Ticks();
    TextBlock[] labelBlocks = new TextBlock[0];
    StringBuilder[] labelText = new StringBuilder[0]; // what each block shows, to reuse it when panning
    TextBlock[] spareBlocks = new TextBlock[0];
    StringBuilder[] spareText = new StringBuilder[0];
    int[] labelOrder = new int[0];
    boolean[] labelUsed = new boolean[0];
    TextBlock titleBlock;
    float thickness; // pixels the axis takes beside the plot
    float offset; // pixels from the plot edge to the axis line
    long ticksVersion = -1; // the range version the ticks were made for
    float ticksPixels = -1;
    long ticksStyle = -1;
    float maxLabel; // the widest (Y) or tallest (X) label in pixels
    boolean paused; // the user zoomed or panned it: no following or auto-fitting until a reset

    @AllowAllocation("axes are made when a chart is built")
    Axis(Chart chart, String id, Side side, Scale scale, AxisRange range, Element part, Element titlePart) {
        this.chart = chart;
        this.id = id;
        this.side = side;
        this.scale = scale;
        this.range = range;
        this.part = part;
        this.titlePart = titlePart;
    }

    /** The chart. */
    public Chart chart() {
        return chart;
    }

    /** The id series name it by. */
    public String id() {
        return id;
    }

    /** Where it lies. */
    public Side side() {
        return side;
    }

    /** Whether it is vertical (a Y axis). */
    public boolean vertical() {
        return side == Side.LEFT || side == Side.RIGHT;
    }

    /** The scale. */
    public Scale scale() {
        return scale;
    }

    /** The visible range (possibly shared with linked axes). */
    public AxisRange range() {
        return range;
    }

    /** Links this axis to {@code shared}: both show the same range from now on; returns this. */
    public Axis link(AxisRange shared) {
        range = shared;
        mappedVersion = -1;
        ticksVersion = -1;
        chart.changed();
        return this;
    }

    /** Whether it is shown (a hidden axis's series still draw against it). */
    public boolean enabled() {
        return enabled;
    }

    /** Shows or hides the axis; returns this. */
    public Axis enabled(boolean on) {
        if (on != enabled) {
            enabled = on;
            chart.changed();
        }
        return this;
    }

    /** The title. */
    public String title() {
        return title;
    }

    /** Sets the title (empty: none); returns this. */
    public Axis title(String value) {
        title = value == null ? "" : value;
        chart.changed();
        return this;
    }

    /** The category names (a {@link Scale#CATEGORY} axis labels whole numbers by them). */
    public List<String> categories() {
        return categories;
    }

    /** Sets the category names; returns this. */
    public Axis categories(List<String> names) {
        categories = List.copyOf(names);
        ticksVersion = -1;
        chart.changed();
        return this;
    }

    /** The symmetric logarithm's linear half-width ({@link Scale#SYMLOG}). */
    public double symlogConstant() {
        return symlogConstant;
    }

    /** Sets the symmetric logarithm's linear half-width (positive); returns this. */
    public Axis symlogConstant(double value) {
        if (!(value > 0)) {
            throw new IllegalArgumentException("the symlog constant must be positive: " + value);
        }
        symlogConstant = value;
        mappedVersion = -1;
        ticksVersion = -1;
        chart.changed();
        return this;
    }

    /** Seconds added to UTC for a time axis's labels and calendar ticks (a time zone's offset). */
    public double timeOffset() {
        return timeOffset;
    }

    /** Sets the time zone offset in seconds; returns this. */
    public Axis timeOffset(double seconds) {
        timeOffset = seconds;
        ticksVersion = -1;
        chart.changed();
        return this;
    }

    /** Whether values grow the other way (down for a Y axis, left for an X axis). */
    public boolean inverted() {
        return inverted;
    }

    /** Inverts the axis; returns this. */
    public Axis inverted(boolean on) {
        inverted = on;
        mappedVersion = -1;
        chart.changed();
        return this;
    }

    /** Whether the range follows the data whenever it changes (until the user zooms or pans it). */
    public boolean autoFit() {
        return autoFit;
    }

    /** Whether fitting (the first data, a reset, a double click) sets the range; a fixed axis keeps its own. */
    public boolean fits() {
        return fits;
    }

    /** Lets fitting set the range, or keeps the range as code set it; returns this. */
    public Axis fits(boolean on) {
        fits = on;
        return this;
    }

    /** Fits the range to the data whenever it changes; returns this. */
    public Axis autoFit(boolean on) {
        autoFit = on;
        chart.changed();
        return this;
    }

    /**
     * Whether the axis follows streaming data: an X axis keeps its span and ends at the newest sample (plus the lead
     * {@code ui.chart.followLead}); turned off by zooming or panning it, on again by a reset.
     */
    public boolean follow() {
        return follow;
    }

    /** Follows streaming data; returns this. */
    public Axis follow(boolean on) {
        follow = on;
        followSpan = Double.NaN;
        chart.changed();
        return this;
    }

    /** Follows with the span the range has when it next follows. */
    void resetFollow() {
        followSpan = Double.NaN;
    }

    /** The span follow mode keeps (the range's span when following started). */
    double followSpan() {
        if (followSpan != followSpan) {
            followSpan = range.span();
        }
        return followSpan;
    }

    /** Whether the user selected the axis (it alone zooms with the wheel over the plot). */
    public boolean selected() {
        return selected;
    }

    /** Selects or deselects the axis; returns this. */
    public Axis selected(boolean on) {
        if (on != selected) {
            selected = on;
            part.state(com.truesim.ui.core.UiState.SELECTED, on);
            chart.changed();
        }
        return this;
    }

    /** Sets the range; returns this. */
    public Axis range(double min, double max) {
        range.set(min, max);
        return this;
    }

    // ---------------------------------------------------------------- transform

    /** The scale's transform of a data value (log10 for log; the symmetric logarithm; the identity otherwise). */
    public double forward(double v) {
        return switch (scale) {
            case LOG -> v > 0 ? Maths.log10(v) : Double.NaN;
            case SYMLOG -> {
                double a = Math.abs(v) / symlogConstant;
                double t = Maths.log10(1 + a);
                yield v < 0 ? -t : t;
            }
            default -> v;
        };
    }

    /** The data value of a transformed value: {@link #forward}'s inverse. */
    public double inverse(double t) {
        return switch (scale) {
            case LOG -> Maths.pow(10, t);
            case SYMLOG -> {
                double v = symlogConstant * (Maths.pow(10, Math.abs(t)) - 1);
                yield t < 0 ? -v : v;
            }
            default -> t;
        };
    }

    /**
     * Maps the range onto pixels {@code start} (the range's minimum) to {@code end} (its maximum); the chart calls it
     * when it lays out (left to right for an X axis, bottom to top for a Y axis, swapped when inverted).
     */
    void map(float start, float end) {
        float a = inverted ? end : start;
        float b = inverted ? start : end;
        if (a == p0 && b == p1 && mappedVersion == range.version()) {
            return;
        }
        p0 = a;
        p1 = b;
        remap();
    }

    /** Takes the range's current values into the mapping. */
    void remap() {
        double lo = forward(range.min());
        double hi = forward(range.max());
        if (!(hi > lo) || hi != hi || lo != lo) { // a log axis over non-positive values: show a decade
            lo = scale == Scale.LOG ? -1 : 0;
            hi = lo + 1;
        }
        f0 = lo;
        f1 = hi;
        k = (p1 - p0) / (f1 - f0);
        mappedVersion = range.version();
    }

    /** Remaps when the range changed since the last mapping (a linked axis moved it). */
    boolean sync() {
        if (mappedVersion != range.version()) {
            remap();
            return true;
        }
        return false;
    }

    /** The pixel of data value {@code v} (relative to the chart's content box). */
    public float toPixel(double v) {
        if (scale == Scale.LINEAR || scale == Scale.TIME || scale == Scale.CATEGORY) {
            return (float) (p0 + (v - f0) * k);
        }
        return (float) (p0 + (forward(v) - f0) * k);
    }

    /** The data value at pixel {@code p}. */
    public double fromPixel(double p) {
        return inverse(f0 + (p - p0) / k);
    }

    /** Pixels per unit of the transformed scale (negative for a Y axis: pixels grow downwards). */
    public double pixelsPerUnit() {
        return k;
    }

    /** The pixel of the range's minimum. */
    public float startPixel() {
        return p0;
    }

    /** The pixel of the range's maximum. */
    public float endPixel() {
        return p1;
    }

    /** The pixel length of the axis. */
    public float length() {
        return Math.abs(p1 - p0);
    }

    // ---------------------------------------------------------------- navigation

    /** Zooms by {@code factor} (below 1: in) about pixel {@code pixel}, in the scale's transformed units. */
    public void zoom(double pixel, double factor) {
        double t = f0 + (pixel - p0) / k;
        double lo = t - (t - f0) * factor;
        double hi = t + (f1 - t) * factor;
        setTransformed(lo, hi);
    }

    /** Moves the range by {@code pixels} (the content moves with the pointer: dragging right shows lower values). */
    public void pan(double pixels) {
        double d = pixels / k;
        setTransformed(f0 - d, f1 - d);
    }

    /** Sets the range to the data values at pixels {@code a} and {@code b} (box zoom). */
    public void zoomTo(double a, double b) {
        double ta = f0 + (a - p0) / k;
        double tb = f0 + (b - p0) / k;
        setTransformed(Math.min(ta, tb), Math.max(ta, tb));
    }

    private void setTransformed(double lo, double hi) {
        double min = inverse(lo);
        double max = inverse(hi);
        if (range.set(min, max)) {
            remap();
        }
    }

    /**
     * Fits the range to data from {@code lo} to {@code hi} with {@code padding} (a share of the span) on each side, in
     * the scale's transformed units; a single value gets a unit around it. Returns whether the range changed.
     */
    public boolean fit(double lo, double hi, double padding) {
        if (lo != lo || hi != hi || lo > hi) {
            return false;
        }
        double a = forward(lo);
        double b = forward(hi);
        if (a != a || b != b) {
            return false;
        }
        if (scale == Scale.CATEGORY) {
            a -= 0.5;
            b += 0.5;
        }
        double span = b - a;
        if (!(span > 0)) {
            double half = Math.max(Math.abs(a) * 0.05, 0.5);
            a -= half;
            b += half;
            span = b - a;
        }
        a -= span * padding;
        b += span * padding;
        boolean changed = range.set(inverse(a), inverse(b));
        if (changed) {
            remap();
        }
        return changed;
    }

    // ---------------------------------------------------------------- labels

    /** Appends the name of category {@code value} (its whole number when it has none). */
    void categoryName(StringBuilder out, double value) {
        long i = Math.round(value);
        if (i >= 0 && i < categories.size() && Math.abs(value - i) < 1e-9) {
            out.append(categories.get((int) i));
        } else {
            com.truesim.ui.controls.data.Format.append(out, value, 0);
        }
    }

    /** Appends {@code value} as a tooltip shows it: one decimal finer than the ticks. */
    public StringBuilder format(StringBuilder out, double value) {
        double step = ticks.step();
        switch (scale) {
            case LOG -> TickFormat.significant(out, value);
            case TIME -> TickFormat.time(out, value + timeOffset, step > 0 ? Math.max(1e-6, step / 10) : 1);
            case CATEGORY -> categoryName(out, Math.rint(value));
            default -> {
                double s = step > 0 ? step / 10 : Math.max(Math.abs(value) * 1e-3, 1e-12);
                TickFormat.number(out, value, s);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "Axis[" + id + " " + side + " " + scale + " " + range + "]";
    }
}
