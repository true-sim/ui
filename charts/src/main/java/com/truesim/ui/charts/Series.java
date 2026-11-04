package com.truesim.ui.charts;

import com.truesim.ui.controls.charts.ChartStyles;
import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import java.util.Arrays;

/**
 * One series of a {@link Chart}: data drawn against an X and a Y axis in one of the chart's ways (lines, bars, a
 * heatmap...). A series has one or more items, each a legend entry that can be hidden (a bar group's columns, a pie's
 * slices); its look comes from its style part (type by kind, such as {@code chart-line}, with the style name given
 * when it was made), its colours from the part's {@code color} or else the chart's {@code palette} in turn.
 *
 * <p>Once a frame the chart {@linkplain #poll polls} each series' data (versions only, unless something changed), and
 * records it again only when the data, an axis range or a style changed. Polling, drawing and hit queries allocate
 * nothing in steady state.
 */
public abstract class Series {

    protected final Chart chart;
    private final String name;
    private final Element part;
    private final Element hoverPart;
    protected Axis xAxis;
    protected Axis yAxis;
    private boolean enabled = true;
    private boolean[] hidden = new boolean[1];
    int colorBase;
    protected Decimation decimation = Decimation.AUTO;

    /** A series of {@code chart} named {@code name}, its style part of {@code type} named {@code style} (or null). */
    @AllowAllocation("series are made when a chart is built")
    protected Series(Chart chart, String type, String name, String style, Axis x, Axis y) {
        this.chart = chart;
        this.name = name == null ? "" : name;
        this.part = chart.ui().part(chart.element(), type, style);
        this.hoverPart = chart.ui().part(chart.element(), type, style);
        hoverPart.state(UiState.HOVER, true);
        this.xAxis = x;
        this.yAxis = y;
    }

    /** The chart. */
    public Chart chart() {
        return chart;
    }

    /** The name the legend and tooltips show. */
    public String name() {
        return name;
    }

    /** The style part (its type is the series' kind, its style name the one given). */
    public Element part() {
        return part;
    }

    /** The X axis it is drawn against. */
    public Axis xAxis() {
        return xAxis;
    }

    /** The Y axis it is drawn against. */
    public Axis yAxis() {
        return yAxis;
    }

    /** Draws against {@code x} and {@code y} from now on (axes of the same chart); returns this. */
    public Series axes(Axis x, Axis y) {
        if (x.chart() != chart || y.chart() != chart || x.vertical() || !y.vertical()) {
            throw new IllegalArgumentException("axes of this chart, a horizontal and a vertical one");
        }
        xAxis = x;
        yAxis = y;
        chart.changed();
        return this;
    }

    /** Whether it is shown. */
    public boolean enabled() {
        return enabled;
    }

    /** Shows or hides the whole series; returns this. */
    public Series enabled(boolean on) {
        if (on != enabled) {
            enabled = on;
            chart.changed();
        }
        return this;
    }

    /** How a large series is reduced before drawing; returns this. */
    public Series decimation(Decimation value) {
        decimation = value;
        chart.changed();
        return this;
    }

    /** How a large series is reduced. */
    public Decimation decimation() {
        return decimation;
    }

    // ---------------------------------------------------------------- items (legend entries)

    /** How many legend entries it has. */
    public int items() {
        return 1;
    }

    /** Legend entry {@code item}'s name. */
    public String item(int item) {
        return name;
    }

    /** Whether item {@code item} is shown (the legend toggles it). */
    public boolean itemEnabled(int item) {
        return enabled && (item >= hidden.length || !hidden[item]);
    }

    /** Shows or hides item {@code item}; returns this. */
    @AllowAllocation("the flags grow with the items, rarely")
    public Series itemEnabled(int item, boolean on) {
        if (item >= hidden.length) {
            hidden = Arrays.copyOf(hidden, Math.max(item + 1, items()));
        }
        if (hidden[item] == on) {
            hidden[item] = !on;
            chart.changed();
        }
        return this;
    }

    /** Whether the series shows in the legend. */
    public boolean inLegend() {
        return !name.isEmpty();
    }

    /** The style the series draws with now: its part's, or its hovered look while the legend entry is hovered. */
    protected final ResolvedStyle style() {
        return chart.styleOf(chart.highlighted(this) ? hoverPart : part);
    }

    /** Item {@code item}'s colour (unfaded): the part's {@code color} when the theme sets one, else the palette's. */
    public long color(int item) {
        ResolvedStyle s = style();
        if (s.isSet(Styles.COLOR) && items() == 1) {
            return s.rgba(Styles.COLOR);
        }
        return chart.palette(colorBase + item);
    }

    /** Item {@code item}'s fill (faded by {@code fill-opacity}; the part's {@code fill} when set). */
    protected final long fill(ResolvedStyle s, int item) {
        long base = s.isSet(ChartStyles.FILL) ? s.rgba(ChartStyles.FILL) : color(item);
        return Rgba.fade(base, s.getFloat(ChartStyles.FILL_OPACITY));
    }

    // ---------------------------------------------------------------- what a kind provides

    /** Whether the chart draws it in its stream this frame (set by the chart). */
    boolean streamed;

    /**
     * Whether it can be drawn in the chart's stream (scrolled by an offset, new samples appended) as it is now; false
     * by default.
     */
    protected boolean streams() {
        return false;
    }

    /** Records it into the stream at the anchor's mapping ({@link Chart#streamX}); only when {@link #streams}. */
    protected void paintStream(Canvas canvas) {
        // nothing by default
    }

    /** Polls the data once a frame; true when it changed since the last poll (the chart records it again). */
    protected abstract boolean poll();

    /**
     * Takes the data's extent into {@code bounds} (X along the series' X axis, Y along its Y axis); with
     * {@code visibleX}, only of the data within the X axis's visible range (for fitting Y to what shows).
     */
    protected abstract void bounds(Bounds bounds, boolean visibleX);

    /** Records the series into the plot. */
    protected abstract void paint(Canvas canvas);

    /**
     * The sample nearest pixel {@code px, py} (content-box pixels) into {@code hit}: by X alone when {@code byX} (the
     * crosshair's tooltip), else in two dimensions, within {@code maxPixels}. False if none.
     */
    protected boolean nearest(float px, float py, float maxPixels, boolean byX, Hit hit) {
        return false;
    }

    /** Whether a drawn shape (a bar, a slice, a cell) lies under pixel {@code px, py}; fills {@code hit}. */
    protected boolean contains(float px, float py, Hit hit) {
        return false;
    }

    /** Appends what a tooltip shows for {@code hit}'s sample (its Y; kinds add more). */
    protected void describe(Hit hit, StringBuilder out) {
        yAxis.format(out, hit.y);
    }

    /** Draws legend entry {@code item}'s swatch in the box (content-box pixels). */
    protected void swatch(Canvas canvas, int item, float x0, float y0, float x1, float y1) {
        long c = canvas.fade(color(item));
        float r = Math.min(x1 - x0, y1 - y0) * 0.2f;
        canvas.box(x0, y0, x1, y1, c, r, 0, 0);
    }

    /** The sum of the versions of the text blocks it draws (labels whose glyphs arrive later are drawn again). */
    protected long textVersion() {
        return 0;
    }

    /** Sets the style name of its parts (themes style series by it: {@code chart-line.temperature}); returns this. */
    public Series styled(String styleName) {
        part.style(styleName);
        hoverPart.style(styleName);
        chart.changed();
        return this;
    }

    /** Forgets what was derived from the data (pyramids, grids, bins): the next poll rebuilds it. */
    protected void invalidate() {
        // nothing derived
    }

    /** Whether {@code v} is a number that can be drawn. */
    protected static boolean finite(double v) {
        return v - v == 0;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + "]";
    }
}
