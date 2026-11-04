package com.truesim.ui.charts;

import com.truesim.ui.controls.charts.ChartStyles;
import com.truesim.ui.controls.charts.Marker;
import com.truesim.ui.controls.charts.LegendPosition;
import com.truesim.ui.controls.charts.TooltipMode;
import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedDouble;
import com.truesim.ui.controls.Painter;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.style.Facts;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.FontStyle;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextSystem;
import java.util.ArrayList;
import java.util.List;

/**
 * A chart (design section 7): any number of {@link Axis axes} on any side with linear, log, symlog, time or category
 * scales, series of every kind over versioned data, a legend, a crosshair with nearest-point highlights and tooltips,
 * annotations and drag tools, and the interactions of a plotting tool, all styled by the theme and recorded without
 * allocating.
 *
 * <pre>{@code
 * Chart chart = new Chart(ui, "Temperatures");
 * VersionedDoubleRing t = clock.doubleRing(100_000), v = clock.doubleRing(100_000);
 * chart.line("Sensor", Columns.of(t), Columns.of(v));
 * chart.x().scale() ...; chart.x().follow(true);       // streaming: the X axis keeps up with the newest sample
 * ui.root().add(chart.element());
 * }</pre>
 *
 * <p><b>Elements.</b> The chart's element (type {@code chart}) draws the axes, grid, ticks, labels and title around a
 * child {@code chart-plot} (the plot area: its own background, and {@code clip: true} so series stay inside it) whose
 * painter records the series; a child {@code chart-overlay} over everything records the legend, crosshair,
 * highlighted points, annotations, drag tools, the zoom box and the tooltip. The chart lays its children out itself
 * (axes take the room their labels need), after layout, in the same frame.
 *
 * <p><b>Interaction.</b> Hovering shows the crosshair (shared with linked charts through a {@link Crosshair}), the
 * nearest sample of each series and the tooltip ({@code chart-tooltips}: merged or per series). The wheel zooms about
 * the pointer: over the plot every axis (only the selected ones when an axis is selected; Shift: X axes only, Ctrl or
 * Cmd: Y axes only), over an axis that axis alone. Dragging with the primary button pans (an axis alone when pressed
 * on it); with the secondary button draws a zoom box (a thin box zooms one direction only); with Shift and the primary
 * button draws a selection ({@link #selection()}, a query range the game reads). Clicking an axis selects it; clicking
 * a legend entry hides or shows its item, hovering one highlights it. Drag tools ({@link #dragPoint},
 * {@link #dragLine}, {@link #dragRect}) move with the pointer. A double click or {@link UiCommand#CHART_RESET} (when focused) fits every
 * axis to the data again and resumes following and auto-fitting.
 *
 * <p><b>Frames.</b> Once a frame the chart polls its series' data and its axes' ranges (versions only); it records
 * the plot again when data or ranges changed, the axes when ranges, size or styles changed, the overlay when the
 * pointer or crosshair moved. Streaming series append to their min-max pyramids in O(log n) per sample.
 */
public final class Chart implements Ui.FrameListener, UiListener {

    private static final List<Float> SOLID = List.of();

    private final Ui ui;
    private final Element root;
    private final Element plot;
    private final Element overlay;
    // style parts
    private final Element gridPart;
    private final Element gridMinorPart;
    private final Element titlePart;
    private final Element legendPart;
    private final Element legendEntryPart;
    private final Element legendEntryHoverPart;
    private final Element legendEntryHiddenPart;
    private final Element tooltipPart;
    private final Element tooltipTitlePart;
    private final Element crosshairPart;
    private final Element highlightPart;
    private final Element zoomBoxPart;
    private final Element selectBoxPart;
    private final Element[] fixedParts;

    private final List<Axis> axes = new ArrayList<>();
    private final List<Series> series = new ArrayList<>();
    private final List<DragTool> tools = new ArrayList<>();
    private final List<Annotation> annotations = new ArrayList<>();
    private Crosshair crosshair;
    private DragRect selection;
    private String title = "";
    private TextBlock titleBlock;
    private int nextColor;

    // geometry (pixels of the content box)
    private float width;
    private float height;
    private float contentX; // where the content box lies in layout space (for pointer events)
    private float contentY;
    private float plotX0;
    private float plotY0;
    private float plotX1;
    private float plotY1;
    private float scale = 1;
    private long styleStamp;
    private long rangeStamp;
    private boolean layoutDirty = true;
    private boolean changed = true;
    private boolean dataChanged;
    private boolean fitPending = true;
    private final Bounds bounds = new Bounds();

    // legend
    private TextBlock[] legendText = new TextBlock[0];
    private Series[] legendSeries = new Series[0];
    private int[] legendItem = new int[0];
    private float[] legendRect = new float[0]; // x0 y0 x1 y1 per entry
    private int legendCount;
    private float legendX0;
    private float legendY0;
    private float legendX1;
    private float legendY1;
    private int legendHover = -1;

    // hover, highlight and tooltip
    private boolean hovering;
    private float hoverX;
    private float hoverY;
    private Hit[] hits = new Hit[0];
    private int hitCount;
    private final Hit probe = new Hit();
    private boolean crosshairShown;
    private float crosshairX;
    private float crosshairY;
    private boolean crosshairOwn;
    private long crosshairSeen = -1;
    private TextBlock[] tooltipText = new TextBlock[0];
    private float[] placed = new float[0]; // per-series tooltip boxes placed so far
    private final StringBuilder scratch = new StringBuilder(64);
    private int tooltipLines;
    private float tooltipX0;
    private float tooltipY0;
    private float tooltipX1;
    private float tooltipY1;
    private Axis hoverAxis;
    private DragTool hoverTool;

    // pressing and dragging
    private enum Mode { NONE, PRESSED, PAN, AXIS_PAN, ZOOM_BOX, SELECT_BOX, TOOL }

    private Mode mode = Mode.NONE;
    private int pressButtons;
    private int pressModifiers;
    private float pressX;
    private float pressY;
    private float lastX;
    private float lastY;
    private Axis pressAxis;
    private DragTool pressTool;
    private int pressLegend = -1;

    // what was drawn (glyphs that arrive later need a new recording)
    private long frameTextSeen;
    private long overlayTextSeen;
    private long plotTextSeen;

    private final Canvas canvas = new Canvas();
    private final FramePainter framePainter = new FramePainter();
    private final PlotPainter plotPainter = new PlotPainter();
    private final OverlayPainter overlayPainter = new OverlayPainter();

    /**
     * A chart in {@code ui} announced as {@code name}, with one X axis ({@code x}, bottom, linear) and one Y axis
     * ({@code y}, left, linear), both from 0 to 1 until data fits them.
     */
    @AllowAllocation("charts are made when a UI is built")
    public Chart(Ui ui, String name) {
        this.ui = ui;
        root = ui.element("chart", null);
        root.semantics(Role.IMAGE, name);
        Layout l = ui.layout();
        ui.codeLayout(root, Ui.CODE_KIND);
        l.kind(root.slot(), LayoutKind.ANCHORS);
        plot = ui.element("chart-plot", null);
        overlay = ui.element("chart-overlay", null);
        root.add(plot).add(overlay);
        for (Element e : new Element[] {plot, overlay}) {
            ui.codeLayout(e, Ui.CODE_PLACEMENT);
            l.anchors(e.slot(), 0, 0, 1, 1);
            ui.interaction().pickable(e, PointerEvents.NONE);
        }
        ui.interaction().pickable(root, PointerEvents.ALL);
        ui.interaction().focusable(root, true);
        gridPart = ui.part(root, "chart-grid", null);
        gridMinorPart = ui.part(root, "chart-grid-minor", null);
        titlePart = ui.part(root, "chart-title", null);
        legendPart = ui.part(root, "chart-legend", null);
        legendEntryPart = ui.part(root, "chart-legend-entry", null);
        legendEntryHoverPart = ui.part(root, "chart-legend-entry", null);
        legendEntryHoverPart.state(UiState.HOVER, true);
        legendEntryHiddenPart = ui.part(root, "chart-legend-entry", null);
        legendEntryHiddenPart.state(UiState.DISABLED, true);
        tooltipPart = ui.part(root, "chart-tooltip", null);
        tooltipTitlePart = ui.part(root, "chart-tooltip-title", null);
        crosshairPart = ui.part(root, "chart-crosshair", null);
        highlightPart = ui.part(root, "chart-highlight", null);
        zoomBoxPart = ui.part(root, "chart-zoom-box", null);
        selectBoxPart = ui.part(root, "chart-selection", null);
        fixedParts = new Element[] {gridPart, gridMinorPart, titlePart, legendPart, legendEntryPart,
            legendEntryHoverPart, legendEntryHiddenPart, tooltipPart, tooltipTitlePart, crosshairPart, highlightPart,
            zoomBoxPart, selectBoxPart, root, plot, overlay};
        crosshair = new Crosshair(ui.store().clock());
        addAxis("x", Axis.Side.BOTTOM, Scale.LINEAR);
        addAxis("y", Axis.Side.LEFT, Scale.LINEAR);
        ui.paint(root, framePainter);
        ui.paint(plot, plotPainter);
        ui.paint(overlay, overlayPainter);
        ui.interaction().on(root, EventType.ENTER.bit() | EventType.MOVE.bit() | EventType.PRESS.bit()
            | EventType.RELEASE.bit()
            | EventType.WHEEL.bit() | EventType.CLICK.bit() | EventType.EXIT.bit() | EventType.COMMAND.bit(), this);
        ui.addFrameListener(this);
    }

    // ---------------------------------------------------------------- structure

    /** The UI. */
    public Ui ui() {
        return ui;
    }

    /** The chart's element: add it to a parent to show the chart. */
    public Element element() {
        return root;
    }

    /** The plot area's element. */
    public Element plotElement() {
        return plot;
    }

    /** The overlay's element (legend, crosshair, tooltips). */
    public Element overlayElement() {
        return overlay;
    }

    /** The title shown above the plot (empty: none). */
    public String title() {
        return title;
    }

    /** Sets the title; returns this. */
    public Chart title(String value) {
        title = value == null ? "" : value;
        changed();
        return this;
    }

    /**
     * A new axis {@code id} at {@code side} with {@code scale}, its own range from 0 to 1 (1 to 10 for log). Series
     * name it by {@link #axis(String)}.
     */
    @AllowAllocation("axes are made when a chart is built")
    public Axis addAxis(String id, Axis.Side side, Scale scale) {
        if (axis(id) != null) {
            throw new IllegalArgumentException("the chart has an axis " + id);
        }
        Element part = ui.part(root, "chart-axis", null).id(id);
        ui.styles().facts(part.slot(), Facts.of("side", side.name().toLowerCase(java.util.Locale.ROOT)));
        Element titleP = ui.part(root, "chart-axis-title", null).id(id);
        AxisRange range = scale == Scale.LOG ? new AxisRange(ui.store().clock(), 1, 10)
            : new AxisRange(ui.store().clock(), 0, 1);
        Axis a = new Axis(this, id, side, scale, range, part, titleP);
        axes.add(a);
        changed();
        return a;
    }

    /** The axis {@code id}, or null. */
    public Axis axis(String id) {
        for (int i = 0; i < axes.size(); i++) {
            if (axes.get(i).id().equals(id)) {
                return axes.get(i);
            }
        }
        return null;
    }

    /** The first X axis. */
    public Axis x() {
        return firstAxis(false);
    }

    /** The first Y axis. */
    public Axis y() {
        return firstAxis(true);
    }

    private Axis firstAxis(boolean vertical) {
        for (int i = 0; i < axes.size(); i++) {
            if (axes.get(i).vertical() == vertical) {
                return axes.get(i);
            }
        }
        return null;
    }

    /** The axes in the order they were added. */
    public List<Axis> axes() {
        return axes;
    }

    /** The series in drawing order. */
    public List<Series> series() {
        return series;
    }

    /** The crosshair (this chart's own, or one shared with linked charts). */
    public Crosshair crosshair() {
        return crosshair;
    }

    /** Shares {@code shared} with other charts: hovering any of them shows the crosshair in all; returns this. */
    public Chart crosshair(Crosshair shared) {
        crosshair = shared;
        crosshairSeen = -1;
        return this;
    }

    /** Marks the chart's look changed: everything is recorded again this frame. */
    public void changed() {
        changed = true;
        layoutDirty = true;
        ui.repaint(root);
        ui.repaint(plot);
        ui.repaint(overlay);
    }

    @AllowAllocation("series are made when a chart is built")
    private <S extends Series> S add(S s) {
        s.colorBase = nextColor;
        nextColor += s.items();
        series.add(s);
        if (hits.length < series.size()) {
            Hit[] grown = new Hit[series.size() + 4];
            for (int i = 0; i < grown.length; i++) {
                grown[i] = i < hits.length ? hits[i] : new Hit();
            }
            hits = grown;
        }
        fitPending = true;
        changed();
        return s;
    }

    // ---------------------------------------------------------------- series factories

    /** A line through {@code x, y} against the first axes. */
    @AllowAllocation("series are made when a chart is built")
    public LineSeries line(String name, Column x, Column y) {
        return add(new LineSeries(this, "chart-line", name, null, x(), y(), x, y));
    }

    /** Stairs through {@code x, y} (the value holds until the next sample). */
    @AllowAllocation("series are made when a chart is built")
    public LineSeries stairs(String name, Column x, Column y) {
        return add(new LineSeries(this, "chart-stairs", name, null, x(), y(), x, y)).step(LineSeries.Step.POST);
    }

    /** The area between the line through {@code x, y} and {@code baseline}. */
    @AllowAllocation("series are made when a chart is built")
    public LineSeries area(String name, Column x, Column y, double baseline) {
        return add(new LineSeries(this, "chart-area", name, null, x(), y(), x, y)).fill(baseline);
    }

    /** Markers at {@code x, y} (no line). */
    @AllowAllocation("series are made when a chart is built")
    public ScatterSeries scatter(String name, Column x, Column y) {
        return add(new ScatterSeries(this, name, x(), y(), x, y));
    }

    /** Stems from {@code baseline} up (or down) to each sample, with markers. */
    @AllowAllocation("series are made when a chart is built")
    public StemSeries stems(String name, Column x, Column y, double baseline) {
        return add(new StemSeries(this, name, x(), y(), x, y, baseline));
    }

    /**
     * A digital signal: levels (0 low, otherwise high) as stairs of a fixed pixel height ({@code digital-height}) along
     * the bottom of the plot, stacked over other digital series.
     */
    @AllowAllocation("series are made when a chart is built")
    public DigitalSeries digital(String name, Column x, Column y) {
        return add(new DigitalSeries(this, name, x(), y(), x, y));
    }

    /** The band between {@code y1} and {@code y2} over the same {@code x}. */
    @AllowAllocation("series are made when a chart is built")
    public ShadedSeries shaded(String name, Column x, Column y1, Column y2) {
        return add(new ShadedSeries(this, name, x(), y(), x, y1, y2));
    }

    /** Areas stacked on one another: {@code ys} in order over {@code x}, one legend entry per name. */
    @AllowAllocation("series are made when a chart is built")
    public StackedAreaSeries stackedArea(List<String> names, Column x, Column... ys) {
        return add(new StackedAreaSeries(this, names, x(), y(), x, ys));
    }

    /**
     * Bars at {@code x}: one column of values per name, grouped side by side (or {@link BarSeries#stacked stacked},
     * or {@link BarSeries#horizontal horizontal}).
     */
    @AllowAllocation("series are made when a chart is built")
    public BarSeries bars(List<String> names, Column x, Column... ys) {
        return add(new BarSeries(this, names, x(), y(), x, ys));
    }

    /** A histogram of {@code values} in {@code bins} bins (0: chosen by the square-root rule). */
    @AllowAllocation("series are made when a chart is built")
    public HistogramSeries histogram(String name, Column values, int bins) {
        return add(new HistogramSeries(this, name, x(), y(), values, bins));
    }

    /** A 2D histogram of the points {@code x, y} in {@code binsX} by {@code binsY} bins, shown through the colormap. */
    @AllowAllocation("series are made when a chart is built")
    public Histogram2DSeries histogram2d(String name, Column x, Column y, int binsX, int binsY) {
        return add(new Histogram2DSeries(this, name, x(), y(), x, y, binsX, binsY));
    }

    /**
     * A heatmap of {@code rows} by {@code columns} values (row-major, the first row at the top) over the data
     * rectangle {@code x0, y0} to {@code x1, y1}, coloured by the colormap.
     */
    @AllowAllocation("series are made when a chart is built")
    public HeatmapSeries heatmap(String name, Column values, int rows, int columns, double x0, double y0, double x1,
        double y1) {
        return add(new HeatmapSeries(this, name, x(), y(), values, rows, columns, x0, y0, x1, y1));
    }

    /** A pie (a donut when the theme gives it a {@code donut-hole}) of {@code values} at {@code x, y}. */
    @AllowAllocation("series are made when a chart is built")
    public PieSeries pie(List<String> names, Column values, double x, double y, double radius) {
        return add(new PieSeries(this, names, x(), y(), values, x, y, radius));
    }

    /** Candlesticks (or OHLC bars) of open, high, low and close at {@code x}. */
    @AllowAllocation("series are made when a chart is built")
    public CandlestickSeries candlestick(String name, Column x, Column open, Column high, Column low, Column close) {
        return add(new CandlestickSeries(this, name, x(), y(), x, open, high, low, close));
    }

    /** Error bars at {@code x, y} reaching {@code negative} below and {@code positive} above (null: the same). */
    @AllowAllocation("series are made when a chart is built")
    public ErrorBarSeries errorBars(String name, Column x, Column y, Column negative, Column positive) {
        return add(new ErrorBarSeries(this, name, x(), y(), x, y, negative, positive));
    }

    /** Lines across the whole plot at {@code values}: vertical (X values) or horizontal (Y values). */
    @AllowAllocation("series are made when a chart is built")
    public InfiniteLines infiniteLines(String name, Column values, boolean vertical) {
        return add(new InfiniteLines(this, name, x(), y(), values, vertical));
    }

    /** {@code texture} over the data rectangle {@code x0, y0} to {@code x1, y1}. */
    @AllowAllocation("series are made when a chart is built")
    public ImageSeries image(String name, UiTexture texture, double x0, double y0, double x1, double y1) {
        return add(new ImageSeries(this, name, x(), y(), texture, x0, y0, x1, y1));
    }

    /** Text at the data point {@code x, y} (style part {@code chart-text}), centred on it. */
    @AllowAllocation("annotations are made when a chart is built")
    public Annotation text(double x, double y, String text) {
        Annotation a = new Annotation(this, "chart-text", x, y, text, 0, 0, false);
        annotations.add(a);
        changed();
        return a;
    }

    /**
     * A callout at the data point {@code x, y}: {@code text} in a box ({@code chart-annotation}) {@code dx, dy} pixels
     * away (panel units), kept inside the plot.
     */
    @AllowAllocation("annotations are made when a chart is built")
    public Annotation annotate(double x, double y, String text, float dx, float dy) {
        Annotation a = new Annotation(this, "chart-annotation", x, y, text, dx, dy, true);
        annotations.add(a);
        changed();
        return a;
    }

    /** The annotations. */
    public List<Annotation> annotations() {
        return annotations;
    }

    /** A point the user can drag, bound two-way to {@code x} and {@code y}. */
    @AllowAllocation("drag tools are made when a chart is built")
    public DragPoint dragPoint(VersionedDouble x, VersionedDouble y) {
        return addTool(new DragPoint(this, x(), y(), x, y));
    }

    /** A line the user can drag: vertical at X {@code value} or horizontal at Y {@code value}. */
    @AllowAllocation("drag tools are made when a chart is built")
    public DragLine dragLine(VersionedDouble value, boolean vertical) {
        return addTool(new DragLine(this, x(), y(), value, vertical));
    }

    /** A rectangle the user can move and resize: a query range, bound two-way to its four edges. */
    @AllowAllocation("drag tools are made when a chart is built")
    public DragRect dragRect(VersionedDouble x0, VersionedDouble y0, VersionedDouble x1, VersionedDouble y1) {
        return addTool(new DragRect(this, x(), y(), x0, y0, x1, y1, "chart-drag-rect"));
    }

    @AllowAllocation("drag tools are made when a chart is built")
    private <T extends DragTool> T addTool(T tool) {
        tools.add(tool);
        changed();
        return tool;
    }

    /** The drag tools. */
    public List<DragTool> tools() {
        return tools;
    }

    /**
     * The selection a Shift-drag draws over the plot (a query range in the first axes' units), hidden until the user
     * draws one; the game reads its edges, and may hide it again.
     */
    @AllowAllocation("made once, when first asked for")
    public DragRect selection() {
        if (selection == null) {
            var c = ui.store().clock();
            selection = new DragRect(this, x(), y(), c.doubleValue(0), c.doubleValue(0), c.doubleValue(0),
                c.doubleValue(0), "chart-selection");
            selection.enabled(false);
            tools.add(selection);
        }
        return selection;
    }

    // ---------------------------------------------------------------- what series read

    /** The resolved style of {@code part}. */
    ResolvedStyle styleOf(Element part) {
        return ui.styles().styleOf(part.slot());
    }

    /** Palette colour {@code index} (cycling). */
    long palette(int index) {
        List<Color> p = styleOf(root).get(ChartStyles.PALETTE);
        if (p.isEmpty()) {
            return Rgba.WHITE;
        }
        return p.get(Math.floorMod(index, p.size())).rgba();
    }

    /** The colormap's colour at {@code t} (0 to 1), interpolated between its stops in sRGB. */
    long colormap(double t) {
        List<Color> m = styleOf(root).get(ChartStyles.COLORMAP);
        if (m.isEmpty()) {
            return Rgba.WHITE;
        }
        if (m.size() == 1 || !(t > 0)) {
            return m.get(0).rgba();
        }
        if (t >= 1) {
            return m.get(m.size() - 1).rgba();
        }
        double f = t * (m.size() - 1);
        int i = (int) f;
        float u = (float) (f - i);
        Color a = m.get(i);
        Color b = m.get(i + 1);
        // colormaps are made evenly in sRGB: interpolate there, draw in linear light
        return Rgba.pack(Rgba.toLinear(a.r() + (b.r() - a.r()) * u), Rgba.toLinear(a.g() + (b.g() - a.g()) * u),
            Rgba.toLinear(a.b() + (b.b() - a.b()) * u), a.a() + (b.a() - a.a()) * u);
    }

    /** The style version stamp of the colormap (heatmaps recolour when it changes). */
    long colormapStamp() {
        ResolvedStyle s = styleOf(root);
        return (long) s.handle() * 1_000_003L + s.version();
    }

    /** Whether {@code s}'s legend entry is hovered (it draws in its hovered look). */
    boolean highlighted(Series s) {
        return legendHover >= 0 && legendHover < legendCount && legendSeries[legendHover] == s;
    }

    /** The UI's scale (style sizes to pixels). */
    float scale() {
        return scale;
    }

    /** The UI's textures (heatmaps make theirs there). */
    com.truesim.ui.core.draw.UiTextures textures() {
        return ui.textures();
    }

    /** A new text block of the UI's text system (null without one). */
    @AllowAllocation("text blocks are made when first needed")
    TextBlock newBlock() {
        TextSystem t = ui.textSystem();
        return t == null ? null : new TextBlock(t);
    }

    /** Shapes {@code text} into {@code block} in {@code style}'s font. */
    boolean shape(TextBlock block, CharSequence text, ResolvedStyle style) {
        if (block == null) {
            return false;
        }
        return block.set(text, style.get(Styles.FONT_FAMILY), style.getFloat(Styles.FONT_SIZE) * scale,
            style.getInt(Styles.FONT_WEIGHT), style.get(Styles.FONT_STYLE) != FontStyle.NORMAL, TextAlign.START,
            Float.POSITIVE_INFINITY, style.getFloat(Styles.LINE_HEIGHT));
    }

    /** The plot's left edge (content-box pixels). */
    public float plotLeft() {
        return plotX0;
    }

    /** The plot's top edge. */
    public float plotTop() {
        return plotY0;
    }

    /** The plot's right edge. */
    public float plotRight() {
        return plotX1;
    }

    /** The plot's bottom edge. */
    public float plotBottom() {
        return plotY1;
    }

    /** Where the content box lies in UI pixels (pointer coordinates minus this are content-box pixels). */
    public float contentX() {
        return contentX;
    }

    /** Where the content box lies in UI pixels, vertically. */
    public float contentY() {
        return contentY;
    }

    /** The content box's width in pixels. */
    public float width() {
        return width;
    }

    /** The content box's height in pixels. */
    public float height() {
        return height;
    }

    // ---------------------------------------------------------------- the frame

    @Override
    public void frame(long nanos) {
        if (ui.store().rootOf(root.slot()) == com.truesim.ui.core.UiStore.NONE) {
            return; // not shown: no work
        }
        boolean data = false;
        for (int i = 0; i < series.size(); i++) {
            data |= series.get(i).poll();
        }
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).poll()) {
                ui.repaint(overlay);
            }
        }
        for (int i = 0; i < annotations.size(); i++) {
            if (annotations.get(i).poll()) {
                ui.repaint(overlay);
            }
        }
        if (data) {
            dataChanged = true;
            ui.repaint(plot);
            ui.repaint(overlay);
        }
        if (data || fitPending) {
            follow(fitPending);
        }
        long ranges = 0;
        for (int i = 0; i < axes.size(); i++) {
            ranges = ranges * 31 + axes.get(i).range().version();
        }
        if (ranges != rangeStamp) {
            rangeStamp = ranges;
            ui.repaint(root);
            ui.repaint(plot);
            ui.repaint(overlay);
        }
        if (crosshair.version() != crosshairSeen) {
            crosshairSeen = crosshair.version();
            ui.repaint(overlay);
        }
        // glyphs that were still generating when recorded
        if (textVersions(0) != frameTextSeen) {
            ui.repaint(root);
        }
        if (textVersions(1) != overlayTextSeen) {
            ui.repaint(overlay);
        }
        if (textVersions(2) != plotTextSeen) {
            ui.repaint(plot);
        }
    }

    /** The sum of the versions of the text blocks a painter draws (0 the axes, 1 the overlay, 2 the plot). */
    private long textVersions(int which) {
        long sum = 0;
        if (which == 0) {
            for (int i = 0; i < axes.size(); i++) {
                Axis a = axes.get(i);
                for (int t = 0; t < Math.min(a.ticks.count(), a.labelBlocks.length); t++) {
                    if (a.labelBlocks[t] != null) {
                        sum += a.labelBlocks[t].version();
                    }
                }
                if (a.titleBlock != null) {
                    sum += a.titleBlock.version();
                }
            }
            if (titleBlock != null) {
                sum += titleBlock.version();
            }
        } else if (which == 1) {
            for (int i = 0; i < legendCount; i++) {
                if (legendText[i] != null) {
                    sum += legendText[i].version();
                }
            }
            for (int i = 0; i < tooltipLines; i++) {
                if (tooltipText[i] != null) {
                    sum += tooltipText[i].version();
                }
            }
            for (int i = 0; i < annotations.size(); i++) {
                sum += annotations.get(i).textVersion();
            }
        } else {
            for (int i = 0; i < series.size(); i++) {
                sum += series.get(i).textVersion();
            }
        }
        return sum;
    }

    /** Follows streaming X axes and refits auto-fitting axes after a data change ({@code reset}: fit everything). */
    private void follow(boolean reset) {
        if (reset) {
            boolean any = false;
            for (int i = 0; i < series.size(); i++) {
                Series s = series.get(i);
                if (s instanceof XYSeries xy && xy.end() > xy.first() || !(s instanceof XYSeries)) {
                    any = true;
                }
            }
            if (!any) {
                return;
            }
            fitPending = false;
            fitAll();
            return;
        }
        for (int i = 0; i < axes.size(); i++) {
            Axis a = axes.get(i);
            if (a.follow() && !a.paused && !a.vertical()) {
                bounds.clear();
                collect(a, false);
                if (bounds.hasX()) {
                    double span = a.followSpan();
                    double lead = span * ChartTunables.FOLLOW_LEAD.get();
                    a.range().set(bounds.maxX + lead - span, bounds.maxX + lead);
                    a.remap();
                }
            }
        }
        for (int i = 0; i < axes.size(); i++) {
            Axis a = axes.get(i);
            if (a.autoFit() && !a.paused) {
                fit(a, a.vertical());
            }
        }
    }

    /** Takes the data of the series drawn against {@code axis} into {@link #bounds}. */
    private void collect(Axis axis, boolean visibleX) {
        for (int i = 0; i < series.size(); i++) {
            Series s = series.get(i);
            if (s.enabled() && (s.xAxis() == axis || s.yAxis() == axis)) {
                if (s.xAxis() == axis && !axis.vertical() || s.yAxis() == axis && axis.vertical()) {
                    s.bounds(bounds, visibleX);
                }
            }
        }
    }

    /** Fits {@code axis} to its series' data (Y axes to what is visible in X when {@code visibleX}). */
    private boolean fit(Axis axis, boolean visibleX) {
        if (!axis.fits()) {
            return false;
        }
        bounds.clear();
        collect(axis, visibleX);
        float pad = styleOf(axis.part).getFloat(ChartStyles.FIT_PADDING);
        if (axis.vertical()) {
            return bounds.hasY() && axis.fit(bounds.minY, bounds.maxY, pad);
        }
        return bounds.hasX() && axis.fit(bounds.minX, bounds.maxX, pad);
    }

    /** Fits every axis to the data (X axes first, then Y axes to everything), resuming follow and auto-fit. */
    public void fitAll() {
        for (int i = 0; i < axes.size(); i++) {
            Axis a = axes.get(i);
            a.paused = false;
            if (!a.vertical()) {
                fit(a, false);
                if (a.follow()) {
                    a.resetFollow();
                }
            }
        }
        for (int i = 0; i < axes.size(); i++) {
            Axis a = axes.get(i);
            if (a.vertical()) {
                fit(a, false);
            }
        }
        changed();
    }

    // ---------------------------------------------------------------- layout

    @Override
    public void afterLayout() {
        int slot = root.slot();
        if (ui.store().rootOf(slot) == com.truesim.ui.core.UiStore.NONE) {
            return;
        }
        Layout l = ui.layout();
        float s = l.scale();
        ResolvedStyle rs = styleOf(root);
        float bw = rs.get(Styles.BORDER).width() * s;
        Insets pad = rs.get(Styles.PADDING);
        float cx = l.x(slot) + bw + pad.left() * s;
        float cy = l.y(slot) + bw + pad.top() * s;
        float w = Math.max(0, l.width(slot) - 2 * bw - (pad.left() + pad.right()) * s);
        float h = Math.max(0, l.height(slot) - 2 * bw - (pad.top() + pad.bottom()) * s);
        long styles = styleStamp();
        boolean sized = w != width || h != height || s != scale;
        contentX = cx;
        contentY = cy;
        if (sized || styles != styleStamp || layoutDirty || rangesMoved()) {
            boolean restyled = styles != styleStamp || s != scale;
            width = w;
            height = h;
            scale = s;
            styleStamp = styles;
            layoutDirty = false;
            arrange(restyled || sized);
            ui.repaint(root);
            ui.repaint(plot);
            ui.repaint(overlay);
        }
        legend();
        hover();
        changed = false;
    }

    private boolean rangesMoved() {
        boolean moved = false;
        for (int i = 0; i < axes.size(); i++) {
            Axis a = axes.get(i);
            if (a.ticksVersion != a.range().version()) {
                moved = true;
            }
            moved |= a.sync();
        }
        return moved;
    }

    private long styleStamp() {
        long h = 17;
        for (Element e : fixedParts) {
            ResolvedStyle st = styleOf(e);
            h = h * 31 + st.handle();
            h = h * 31 + st.version();
        }
        for (int i = 0; i < axes.size(); i++) {
            ResolvedStyle st = styleOf(axes.get(i).part);
            h = h * 31 + st.handle() + st.version();
            st = styleOf(axes.get(i).titlePart);
            h = h * 31 + st.handle() + st.version();
        }
        for (int i = 0; i < series.size(); i++) {
            ResolvedStyle st = styleOf(series.get(i).part());
            h = h * 31 + st.handle() + st.version();
        }
        return h;
    }

    /**
     * Lays the chart out: the title and the axes' room (their labels' sizes, so ticks first), the plot rectangle,
     * the axes' pixel mappings, then the plot child's offsets.
     */
    private void arrange(boolean restyled) {
        float gapTitle = 0;
        float top = 0;
        if (!title.isEmpty() && titleBlock == null) {
            titleBlock = newBlock();
        }
        if (!title.isEmpty() && titleBlock != null) {
            ResolvedStyle ts = styleOf(titlePart);
            shape(titleBlock, title, ts);
            gapTitle = titleBlock.height() + ts.get(Styles.MARGIN).bottom() * scale;
            top += gapTitle;
        }
        // horizontal axes: their thickness does not depend on their ticks' values
        float bottom = 0;
        float yTitles = 0;
        for (int i = 0; i < axes.size(); i++) {
            Axis a = axes.get(i);
            if (!a.enabled()) {
                a.thickness = 0;
                continue;
            }
            ResolvedStyle as = styleOf(a.part);
            float gap = as.getFloat(Styles.GAP) * scale;
            float tick = as.getFloat(ChartStyles.TICK_LENGTH) * scale;
            float titleH = axisTitle(a);
            if (!a.vertical()) {
                float label = lineHeight(as);
                a.thickness = tick + gap + label + (titleH > 0 ? gap + titleH : 0);
                if (a.side() == Axis.Side.BOTTOM) {
                    a.offset = bottom;
                    bottom += a.thickness + gap;
                } else {
                    a.offset = top - gapTitle;
                    top += a.thickness + gap;
                }
            } else if (titleH > 0) {
                yTitles = Math.max(yTitles, titleH + gap);
            }
        }
        top += yTitles;
        float plotH = Math.max(1, height - top - bottom);
        // vertical axes: ticks for the plot's height, then their labels' width
        float left = 0;
        float right = 0;
        for (int i = 0; i < axes.size(); i++) {
            Axis a = axes.get(i);
            if (!a.vertical() || !a.enabled()) {
                continue;
            }
            ResolvedStyle as = styleOf(a.part);
            float gap = as.getFloat(Styles.GAP) * scale;
            float tick = as.getFloat(ChartStyles.TICK_LENGTH) * scale;
            a.map(top + plotH, top);
            ticks(a, plotH, as, restyled);
            a.thickness = tick + gap + a.maxLabel;
            if (a.side() == Axis.Side.LEFT) {
                a.offset = left;
                left += a.thickness + gap;
            } else {
                a.offset = right;
                right += a.thickness + gap;
            }
        }
        // the plot, and the horizontal axes' ticks for its width
        plotX0 = Math.min(left, width);
        plotX1 = Math.max(plotX0 + 1, width - right);
        plotY0 = Math.min(top, height);
        plotY1 = Math.max(plotY0 + 1, plotY0 + plotH);
        for (int i = 0; i < axes.size(); i++) {
            Axis a = axes.get(i);
            if (a.vertical()) {
                a.map(plotY1, plotY0);
                if (!a.enabled()) {
                    ticks(a, plotY1 - plotY0, styleOf(a.part), restyled);
                }
            } else {
                a.map(plotX0, plotX1);
                ticks(a, plotX1 - plotX0, styleOf(a.part), restyled);
            }
        }
        // the plot child covers the plot rectangle (panel units)
        Layout l = ui.layout();
        float u = 1 / scale;
        l.offsets(plot.slot(), plotX0 * u, plotY0 * u, -(width - plotX1) * u, -(height - plotY1) * u);
    }

    /** Shapes the axis's title; its height (0 without one). */
    private float axisTitle(Axis a) {
        if (a.title().isEmpty()) {
            return 0;
        }
        if (a.titleBlock == null) {
            a.titleBlock = newBlock();
        }
        if (a.titleBlock == null) {
            return 0;
        }
        shape(a.titleBlock, a.title(), styleOf(a.titlePart));
        return a.titleBlock.height();
    }

    private float lineHeight(ResolvedStyle s) {
        if (ui.textSystem() == null) {
            return 0;
        }
        return s.getFloat(Styles.FONT_SIZE) * scale * s.getFloat(Styles.LINE_HEIGHT);
    }

    /** Generates the axis's ticks (when its range, length or style changed) and shapes their labels. */
    private void ticks(Axis a, float pixels, ResolvedStyle as, boolean restyled) {
        long version = a.range().version();
        if (!restyled && a.ticksVersion == version && a.ticksPixels == pixels) {
            return;
        }
        a.ticksVersion = version;
        a.ticksPixels = pixels;
        float spacing = as.getFloat(ChartStyles.TICK_SPACING) * scale;
        int limit = (int) ChartTunables.MAX_TICKS.get();
        boolean minors = as.get(ChartStyles.MINOR_TICKS);
        a.ticks.generate(a, pixels, spacing, limit, minors);
        labels(a, as, restyled);
        if (!a.vertical() && a.ticks.majors() > 1) {
            // labels wider than the room between ticks: fewer ticks
            float gap = as.getFloat(Styles.GAP) * scale * 2;
            float room = pixels / a.ticks.majors();
            if (a.maxLabel + gap > room) {
                a.ticks.generate(a, pixels, Math.max(spacing, (a.maxLabel + gap) * 1.5f), limit, minors);
                labels(a, as, false);
            }
        }
    }

    /**
     * Shapes the axis's major labels, reusing the block that already shows a label's text (panning shifts the ticks,
     * so only the labels coming into view are shaped); every one again when {@code restyled}.
     */
    private void labels(Axis a, ResolvedStyle as, boolean restyled) {
        Ticks t = a.ticks;
        int n = t.count();
        if (a.labelBlocks.length < n) {
            growLabels(a, n);
        }
        int len = a.labelBlocks.length;
        java.util.Arrays.fill(a.labelUsed, 0, len, false);
        // labels already shown keep their blocks
        for (int i = 0; i < n; i++) {
            a.labelOrder[i] = -1;
            if (!t.major(i) || restyled) {
                continue;
            }
            CharSequence label = t.label(i);
            for (int j = 0; j < len; j++) {
                if (!a.labelUsed[j] && same(a.labelText[j], label)) {
                    a.labelOrder[i] = j;
                    a.labelUsed[j] = true;
                    break;
                }
            }
        }
        // the others take free blocks and are shaped
        int free = 0;
        for (int i = 0; i < n; i++) {
            if (!t.major(i) || a.labelOrder[i] >= 0) {
                continue;
            }
            while (a.labelUsed[free]) {
                free++;
            }
            a.labelOrder[i] = free;
            a.labelUsed[free] = true;
            a.labelText[free].setLength(0);
            a.labelText[free].append(t.label(i));
            shape(a.labelBlocks[free], a.labelText[free], as);
        }
        // reorder: tick i's block at i, the unused blocks in the remaining slots
        int slot = 0;
        for (int i = 0; i < n; i++) {
            int j = a.labelOrder[i];
            if (j >= 0) {
                a.spareBlocks[i] = a.labelBlocks[j];
                a.spareText[i] = a.labelText[j];
            }
        }
        for (int j = 0; j < len; j++) {
            if (a.labelUsed[j]) {
                continue;
            }
            while (slot < n && a.labelOrder[slot] >= 0) {
                slot++;
            }
            if (slot >= len) {
                break;
            }
            a.spareBlocks[slot] = a.labelBlocks[j];
            a.spareText[slot] = a.labelText[j];
            slot++;
        }
        TextBlock[] blocks = a.labelBlocks;
        a.labelBlocks = a.spareBlocks;
        a.spareBlocks = blocks;
        StringBuilder[] texts = a.labelText;
        a.labelText = a.spareText;
        a.spareText = texts;
        float max = 0;
        for (int i = 0; i < n; i++) {
            TextBlock b = a.labelBlocks[i];
            if (b != null && t.major(i)) {
                max = Math.max(max, a.vertical() ? b.width() : b.height());
            }
        }
        a.maxLabel = max;
    }

    private static boolean same(CharSequence a, CharSequence b) {
        int n = a.length();
        if (n != b.length()) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    @AllowAllocation("label blocks grow with the tick count, rarely")
    private void growLabels(Axis a, int n) {
        int len = Math.max(n, a.labelBlocks.length * 2);
        TextBlock[] grown = java.util.Arrays.copyOf(a.labelBlocks, len);
        StringBuilder[] texts = java.util.Arrays.copyOf(a.labelText, len);
        for (int i = a.labelBlocks.length; i < len; i++) {
            grown[i] = newBlock();
            texts[i] = new StringBuilder(16);
        }
        a.labelBlocks = grown;
        a.labelText = texts;
        a.spareBlocks = new TextBlock[len];
        a.spareText = new StringBuilder[len];
        a.labelOrder = new int[len];
        a.labelUsed = new boolean[len];
    }

    // ---------------------------------------------------------------- legend

    /** Lays the legend out in the plot: entries, their text, the box. */
    private void legend() {
        ResolvedStyle ls = styleOf(legendPart);
        LegendPosition where = ls.get(ChartStyles.LEGEND_POSITION);
        int n = 0;
        for (int i = 0; i < series.size(); i++) {
            Series s = series.get(i);
            if (s.inLegend()) {
                n += s.items();
            }
        }
        if (where == LegendPosition.NONE || n == 0 || ui.textSystem() == null) {
            legendCount = 0;
            return;
        }
        ensureLegend(n);
        int k = 0;
        ResolvedStyle es = styleOf(legendEntryPart);
        float swatch = ls.getFloat(ChartStyles.SWATCH_SIZE) * scale;
        float gap = ls.getFloat(Styles.GAP) * scale;
        Insets pad = ls.get(Styles.PADDING);
        Insets margin = ls.get(Styles.MARGIN);
        boolean row = ls.get(Styles.DIRECTION) == com.truesim.ui.style.value.Direction.ROW;
        float entryH = 0;
        float widest = 0;
        for (int i = 0; i < series.size(); i++) {
            Series s = series.get(i);
            if (!s.inLegend()) {
                continue;
            }
            for (int it = 0; it < s.items(); it++) {
                legendSeries[k] = s;
                legendItem[k] = it;
                shape(legendText[k], s.item(it), es);
                float ew = swatch + gap + legendText[k].width();
                entryH = Math.max(entryH, Math.max(swatch, legendText[k].height()));
                widest = Math.max(widest, ew);
                k++;
            }
        }
        legendCount = k;
        float px0 = plotX0 + margin.left() * scale;
        float px1 = plotX1 - margin.right() * scale;
        float py0 = plotY0 + margin.top() * scale;
        float py1 = plotY1 - margin.bottom() * scale;
        float padX = (pad.left() + pad.right()) * scale;
        float padY = (pad.top() + pad.bottom()) * scale;
        // entries in rows (wrapping at the plot's width) or in a column, relative to the box's content
        float room = Math.max(widest, px1 - px0 - padX);
        float ex = 0;
        float ey = 0;
        float contentW = 0;
        for (int i = 0; i < k; i++) {
            float ew = swatch + gap + legendText[i].width();
            if (row && ex > 0 && ex + ew > room) {
                ex = 0;
                ey += entryH + gap;
            }
            int o = i * 4;
            legendRect[o] = ex;
            legendRect[o + 1] = ey;
            legendRect[o + 2] = row ? ex + ew : widest;
            legendRect[o + 3] = ey + entryH;
            contentW = Math.max(contentW, legendRect[o + 2]);
            if (row) {
                ex += ew + gap * 2;
            } else {
                ey += entryH + gap;
            }
        }
        float boxW = contentW + padX;
        float boxH = (k == 0 ? 0 : legendRect[(k - 1) * 4 + 3]) + padY;
        float bx = switch (where) {
            case TOP_LEFT, LEFT, BOTTOM_LEFT -> px0;
            case TOP, BOTTOM -> (px0 + px1 - boxW) * 0.5f;
            default -> px1 - boxW;
        };
        float by = switch (where) {
            case TOP_LEFT, TOP, TOP_RIGHT -> py0;
            case LEFT, RIGHT -> (py0 + py1 - boxH) * 0.5f;
            default -> py1 - boxH;
        };
        bx = Math.max(0, Math.min(width - boxW, bx));
        by = Math.max(0, Math.min(height - boxH, by));
        legendX0 = bx;
        legendY0 = by;
        legendX1 = bx + boxW;
        legendY1 = by + boxH;
        float ox = bx + pad.left() * scale;
        float oy = by + pad.top() * scale;
        for (int i = 0; i < k * 4; i += 2) {
            legendRect[i] += ox;
            legendRect[i + 1] += oy;
        }
    }

    @AllowAllocation("the legend grows with the series, rarely")
    private void ensureLegend(int n) {
        if (legendText.length < n) {
            TextBlock[] grown = java.util.Arrays.copyOf(legendText, n);
            for (int i = legendText.length; i < n; i++) {
                grown[i] = newBlock();
            }
            legendText = grown;
            legendSeries = java.util.Arrays.copyOf(legendSeries, n);
            legendItem = java.util.Arrays.copyOf(legendItem, n);
            legendRect = java.util.Arrays.copyOf(legendRect, n * 4);
        }
    }

    /** The legend entry under content-box pixel {@code x, y}, or -1. */
    public int legendEntryAt(float x, float y) {
        for (int i = 0; i < legendCount; i++) {
            int o = i * 4;
            if (x >= legendRect[o] && x <= legendRect[o + 2] && y >= legendRect[o + 1] && y <= legendRect[o + 3]) {
                return i;
            }
        }
        return -1;
    }

    /** How many legend entries show. */
    public int legendCount() {
        return legendCount;
    }

    /** Legend entry {@code i}'s series. */
    public Series legendSeries(int i) {
        return legendSeries[i];
    }

    /** Legend entry {@code i}'s item of its series. */
    public int legendItem(int i) {
        return legendItem[i];
    }

    /** Legend entry {@code i}'s centre, in content-box pixels: x. */
    public float legendX(int i) {
        return (legendRect[i * 4] + legendRect[i * 4 + 2]) * 0.5f;
    }

    /** Legend entry {@code i}'s centre, in content-box pixels: y. */
    public float legendY(int i) {
        return (legendRect[i * 4 + 1] + legendRect[i * 4 + 3]) * 0.5f;
    }

    // ---------------------------------------------------------------- hover

    /** Finds the highlighted points and lays the tooltip out, for the pointer or a linked chart's crosshair. */
    private void hover() {
        hitCount = 0;
        tooltipLines = 0;
        boolean own = hovering && inPlot(hoverX, hoverY) && mode != Mode.PAN && mode != Mode.AXIS_PAN
            && mode != Mode.TOOL;
        Axis xa = x();
        Axis ya = y();
        if (own && xa != null && ya != null) {
            crosshair.set(this, xa.fromPixel(hoverX), ya.fromPixel(hoverY));
            crosshairShown = true;
            crosshairOwn = true;
            crosshairX = hoverX;
            crosshairY = hoverY;
        } else if (crosshair.active() && crosshair.source() != this && xa != null) {
            crosshairShown = true;
            crosshairOwn = false;
            crosshairX = xa.toPixel(crosshair.x());
            crosshairY = Float.NaN;
            if (!(crosshairX >= plotX0 && crosshairX <= plotX1)) {
                crosshairShown = false;
            }
        } else {
            if (crosshairOwn) {
                crosshair.clear(this);
            }
            crosshairShown = false;
            crosshairOwn = false;
        }
        if (!crosshairShown) {
            return;
        }
        float reach = ChartTunables.HOVER_DISTANCE.get();
        boolean merged = styleOf(root).get(ChartStyles.TOOLTIPS) == TooltipMode.MERGED;
        for (int i = 0; i < series.size(); i++) {
            Series s = series.get(i);
            Hit h = hits[hitCount];
            h.clear();
            float py = crosshairOwn ? crosshairY : (plotY0 + plotY1) * 0.5f;
            boolean found = s.enabled() && (s.contains(crosshairX, py, h)
                || s.nearest(crosshairX, py, crosshairOwn && !merged ? reach : Float.POSITIVE_INFINITY,
                    !crosshairOwn || merged, h));
            if (found && (crosshairOwn || h.distance <= reach) && h.px >= plotX0 - 1 && h.px <= plotX1 + 1) {
                hitCount++;
            }
        }
        if (crosshairOwn) {
            tooltip(merged);
        }
    }

    private boolean inPlot(float x, float y) {
        return x >= plotX0 && x <= plotX1 && y >= plotY0 && y <= plotY1;
    }

    /** Lays the tooltip out: a title line (the X) and a line per highlighted series. */
    private void tooltip(boolean merged) {
        TooltipMode mode = styleOf(root).get(ChartStyles.TOOLTIPS);
        if (mode == TooltipMode.NONE || ui.textSystem() == null || hitCount == 0) {
            return;
        }
        ensureTooltip(hitCount + 1);
        ResolvedStyle ts = styleOf(tooltipPart);
        ResolvedStyle tt = styleOf(tooltipTitlePart);
        if (merged) {
            Axis xa = hits[0].series.xAxis();
            scratch.setLength(0);
            xa.format(scratch, hits[0].x);
            shape(tooltipText[0], scratch, tt);
            tooltipLines = 1;
        }
        for (int i = 0; i < hitCount; i++) {
            Hit h = hits[i];
            scratch.setLength(0);
            if (!h.series.name().isEmpty()) {
                scratch.append(h.series.item(h.item)).append(": ");
            }
            h.series.describe(h, scratch);
            shape(tooltipText[tooltipLines++], scratch, ts);
        }
        if (!merged) {
            return;
        }
        Insets pad = ts.get(Styles.PADDING);
        float swatch = styleOf(legendPart).getFloat(ChartStyles.SWATCH_SIZE) * scale;
        float gap = ts.getFloat(Styles.GAP) * scale;
        float w = 0;
        float h = 0;
        for (int i = 0; i < tooltipLines; i++) {
            float lw = tooltipText[i].width() + (i > 0 ? swatch + gap : 0);
            w = Math.max(w, lw);
            h += tooltipText[i].height() + (i > 0 ? gap : 0);
        }
        w += (pad.left() + pad.right()) * scale;
        h += (pad.top() + pad.bottom()) * scale;
        float off = styleOf(root).getFloat(ChartStyles.TOOLTIP_OFFSET) * scale;
        float x0 = crosshairX + off;
        if (x0 + w > width) {
            x0 = crosshairX - off - w;
        }
        float y0 = crosshairY + off;
        if (y0 + h > height) {
            y0 = crosshairY - off - h;
        }
        tooltipX0 = Math.max(0, x0);
        tooltipY0 = Math.max(0, y0);
        tooltipX1 = Math.min(width, tooltipX0 + w);
        tooltipY1 = Math.min(height, tooltipY0 + h);
    }

    @AllowAllocation("the tooltip's lines grow with the series, rarely")
    private void ensureTooltip(int n) {
        if (tooltipText.length < n) {
            placed = new float[(n + 2) * 4];
            TextBlock[] grown = java.util.Arrays.copyOf(tooltipText, n + 2);
            for (int i = tooltipText.length; i < grown.length; i++) {
                grown[i] = newBlock();
            }
            tooltipText = grown;
        }
    }

    /** How many points are highlighted. */
    public int highlights() {
        return hitCount;
    }

    /** Highlighted point {@code i}. */
    public Hit highlight(int i) {
        return hits[i];
    }

    /** Whether the crosshair shows in this chart (hovered, or a linked chart is). */
    public boolean crosshairShown() {
        return crosshairShown;
    }

    // ---------------------------------------------------------------- queries

    /**
     * What lies under content-box pixel {@code x, y}: a bar, slice or cell containing it, else the nearest sample
     * within {@code ui.chart.hoverDistance}; into {@code hit}. Allocation-free.
     */
    public boolean hitTest(float x, float y, Hit hit) {
        hit.clear();
        for (int i = series.size() - 1; i >= 0; i--) {
            Series s = series.get(i);
            if (s.enabled() && s.contains(x, y, probe.clear())) {
                hit.set(probe);
                return true;
            }
        }
        float reach = ChartTunables.HOVER_DISTANCE.get();
        for (int i = 0; i < series.size(); i++) {
            Series s = series.get(i);
            if (s.enabled() && s.nearest(x, y, reach, false, probe.clear()) && probe.distance < hit.distance) {
                hit.set(probe);
            }
        }
        return hit.found();
    }

    /** The axis whose area lies under content-box pixel {@code x, y}, or null. */
    public Axis axisAt(float x, float y) {
        for (int i = 0; i < axes.size(); i++) {
            Axis a = axes.get(i);
            if (!a.enabled() || a.thickness <= 0) {
                continue;
            }
            float near = a.offset;
            float far = a.offset + a.thickness;
            boolean in = switch (a.side()) {
                case BOTTOM -> x >= plotX0 && x <= plotX1 && y >= plotY1 + near && y <= plotY1 + far;
                case TOP -> x >= plotX0 && x <= plotX1 && y <= plotY0 - near && y >= plotY0 - far;
                case LEFT -> y >= plotY0 && y <= plotY1 && x <= plotX0 - near && x >= plotX0 - far;
                case RIGHT -> y >= plotY0 && y <= plotY1 && x >= plotX1 + near && x <= plotX1 + far;
            };
            if (in) {
                return a;
            }
        }
        return null;
    }

    private DragTool toolAt(float x, float y) {
        float grab = ChartTunables.GRAB_PIXELS.get();
        DragTool best = null;
        float bestD = Float.POSITIVE_INFINITY;
        for (int i = tools.size() - 1; i >= 0; i--) {
            DragTool t = tools.get(i);
            if (!t.enabled()) {
                continue;
            }
            float d = t.distance(x, y);
            if (d <= grab && d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- events

    @Override
    public void handle(UiEvent event) {
        float x = event.x() - contentX;
        float y = event.y() - contentY;
        switch (event.type()) {
            case ENTER, MOVE -> move(event, x, y); // a pointer appearing over the chart hovers it as a move does
            case PRESS -> press(event, x, y);
            case RELEASE -> release(event, x, y);
            case WHEEL -> wheel(event, x, y);
            case CLICK -> {
                if (event.clickCount() == 2 && event.button() == Pointer.PRIMARY && mode == Mode.NONE
                    && axisAt(x, y) == null && legendEntryAt(x, y) < 0 && toolAt(x, y) == null) {
                    event.consume();
                    fitAll();
                }
            }
            case EXIT -> {
                if (mode == Mode.NONE) {
                    hovering = false;
                    hoverAt(null, null, -1);
                    ui.repaint(overlay);
                }
            }
            case COMMAND -> {
                if (event.command() == UiCommand.CHART_RESET) {
                    event.consume();
                    fitAll();
                }
            }
            default -> {
                // nothing else
            }
        }
    }

    private void move(UiEvent event, float x, float y) {
        hovering = true;
        hoverX = x;
        hoverY = y;
        ui.repaint(overlay);
        if (mode == Mode.NONE) {
            hoverAt(axisAt(x, y), toolAt(x, y), legendEntryAt(x, y));
            return;
        }
        float dx = x - lastX;
        float dy = y - lastY;
        if (mode == Mode.PRESSED) {
            float moved = Math.abs(x - pressX) + Math.abs(y - pressY);
            if (moved < ChartTunables.DRAG_PIXELS.get()) {
                return;
            }
            if (pressTool != null) {
                mode = Mode.TOOL;
            } else if (pressLegend >= 0) {
                return; // a press on the legend only clicks
            } else if (pressAxis != null) {
                mode = Mode.AXIS_PAN;
            } else if ((pressButtons & Pointer.SECONDARY) != 0) {
                mode = Mode.ZOOM_BOX;
            } else if ((pressModifiers & Pointer.SHIFT) != 0) {
                mode = Mode.SELECT_BOX;
            } else {
                mode = Mode.PAN;
            }
            dx = x - pressX;
            dy = y - pressY;
        }
        switch (mode) {
            case PAN -> {
                for (int i = 0; i < axes.size(); i++) {
                    Axis a = axes.get(i);
                    a.pan(a.vertical() ? dy : dx);
                    a.paused = true;
                }
            }
            case AXIS_PAN -> {
                pressAxis.pan(pressAxis.vertical() ? dy : dx);
                pressAxis.paused = true;
            }
            case TOOL -> pressTool.drag(x, y);
            default -> {
                // boxes draw from the press to here
            }
        }
        lastX = x;
        lastY = y;
    }

    private void hoverAt(Axis axis, DragTool tool, int legendEntry) {
        if (axis != hoverAxis) {
            if (hoverAxis != null) {
                hoverAxis.part.state(UiState.HOVER, false);
            }
            if (axis != null) {
                axis.part.state(UiState.HOVER, true);
            }
            hoverAxis = axis;
            ui.repaint(root);
        }
        if (tool != hoverTool) {
            if (hoverTool != null) {
                hoverTool.hovered(false);
            }
            if (tool != null) {
                tool.hovered(true);
            }
            hoverTool = tool;
            ui.repaint(overlay);
        }
        if (legendEntry != legendHover) {
            legendHover = legendEntry;
            ui.repaint(plot);
            ui.repaint(overlay);
        }
    }

    private void press(UiEvent event, float x, float y) {
        event.consume();
        ui.interaction().focus(root, event.player());
        mode = Mode.PRESSED;
        pressButtons = event.buttons();
        pressModifiers = event.modifiers();
        pressX = x;
        pressY = y;
        lastX = x;
        lastY = y;
        pressLegend = legendEntryAt(x, y);
        pressTool = pressLegend >= 0 || (pressButtons & Pointer.PRIMARY) == 0 ? null : toolAt(x, y);
        pressAxis = pressLegend >= 0 || pressTool != null ? null : axisAt(x, y);
        if (pressTool != null) {
            pressTool.grab(x, y);
            pressTool.pressed(true);
        }
    }

    private void release(UiEvent event, float x, float y) {
        if (mode == Mode.NONE) {
            return;
        }
        event.consume();
        switch (mode) {
            case PRESSED -> click(x, y);
            case ZOOM_BOX -> zoomBox(x, y);
            case SELECT_BOX -> selectBox(x, y);
            default -> {
                // pans and drags are done
            }
        }
        if (pressTool != null) {
            pressTool.pressed(false);
            pressTool.release();
        }
        mode = Mode.NONE;
        pressTool = null;
        pressAxis = null;
        pressLegend = -1;
        ui.repaint(overlay);
    }

    private void click(float x, float y) {
        if (pressLegend >= 0 && pressLegend == legendEntryAt(x, y)) {
            Series s = legendSeries[pressLegend];
            int item = legendItem[pressLegend];
            s.itemEnabled(item, !s.itemEnabled(item));
            return;
        }
        if (pressAxis != null && pressAxis == axisAt(x, y)) {
            pressAxis.selected(!pressAxis.selected());
        }
    }

    private void zoomBox(float x, float y) {
        float min = ChartTunables.DRAG_PIXELS.get();
        boolean horizontal = Math.abs(x - pressX) >= min;
        boolean vertical = Math.abs(y - pressY) >= min;
        for (int i = 0; i < axes.size(); i++) {
            Axis a = axes.get(i);
            if (a.vertical() && vertical) {
                a.zoomTo(pressY, y);
                a.paused = true;
            } else if (!a.vertical() && horizontal) {
                a.zoomTo(pressX, x);
                a.paused = true;
            }
        }
    }

    private void selectBox(float x, float y) {
        DragRect s = selection();
        Axis xa = s.xAxis();
        Axis ya = s.yAxis();
        s.set(xa.fromPixel(Math.min(pressX, x)), ya.fromPixel(Math.max(pressY, y)), xa.fromPixel(Math.max(pressX, x)),
            ya.fromPixel(Math.min(pressY, y)));
        s.enabled(true);
    }

    private void wheel(UiEvent event, float x, float y) {
        float notches = event.scrollY() != 0 ? event.scrollY() : event.scrollX();
        if (notches == 0) {
            return;
        }
        event.consume();
        double step = ChartTunables.ZOOM_STEP.get();
        double factor = com.truesim.common.math.Maths.pow(1 - step, notches);
        Axis over = axisAt(x, y);
        if (over != null) {
            over.zoom(over.vertical() ? y : x, factor);
            over.paused = true;
            return;
        }
        if (!inPlot(x, y)) {
            return;
        }
        boolean anySelected = false;
        for (int i = 0; i < axes.size(); i++) {
            anySelected |= axes.get(i).selected();
        }
        int mods = event.modifiers();
        boolean xOnly = (mods & Pointer.SHIFT) != 0;
        boolean yOnly = (mods & (Pointer.CTRL | Pointer.SHORTCUT)) != 0;
        for (int i = 0; i < axes.size(); i++) {
            Axis a = axes.get(i);
            if (anySelected && !a.selected() || xOnly && a.vertical() || yOnly && !a.vertical()) {
                continue;
            }
            a.zoom(a.vertical() ? y : x, factor);
            a.paused = true;
        }
    }

    // ---------------------------------------------------------------- painters

    /** The chart's own content: grid, axes, ticks, labels, titles. */
    private final class FramePainter implements Painter {

        @Override
        public long measure(int slot, float w, int widthMode, float h, int heightMode) {
            return Measure.size(0, 0);
        }

        @Override
        public int spare(int recorded) {
            return recorded / 2 + 64;
        }

        @Override
        public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
            frameTextSeen = textVersions(0); // before recording: a glyph arriving meanwhile records again
            Canvas c = canvas;
            c.begin(list, x0, y0, -1e6f, -1e6f, 1e6f, 1e6f, opacity, scale);
            grid(c, gridMinorPart, false);
            grid(c, gridPart, true);
            for (int i = 0; i < axes.size(); i++) {
                Axis a = axes.get(i);
                if (a.enabled()) {
                    axis(c, a);
                }
            }
            if (!title.isEmpty() && titleBlock != null) {
                ResolvedStyle ts = styleOf(titlePart);
                float centred = (plotX0 + plotX1 - titleBlock.width()) * 0.5f;
                float tx = Math.max(0, Math.min(width - titleBlock.width(), centred));
                c.text(titleBlock, tx, 0, c.fade(ts.rgba(Styles.COLOR)));
            }
        }

        private void grid(Canvas c, Element part, boolean majors) {
            ResolvedStyle gs = styleOf(part);
            c.cull(plotX0, plotY0, plotX1, plotY1);
            c.stroke(gs, gs.rgba(ChartStyles.STROKE));
            if (!c.stroking()) {
                return;
            }
            Axis xa = firstShown(false);
            Axis ya = firstShown(true);
            for (Axis a = xa; a != null; a = a == xa ? ya : null) {
                Ticks t = a.ticks;
                for (int i = 0; i < t.count(); i++) {
                    if (t.major(i) != majors) {
                        continue;
                    }
                    float p = a.toPixel(t.value(i));
                    if (a.vertical()) {
                        if (p >= plotY0 && p <= plotY1) {
                            c.line(plotX0, p, plotX1, p);
                        }
                    } else if (p >= plotX0 && p <= plotX1) {
                        c.line(p, plotY0, p, plotY1);
                    }
                }
            }
            c.cull(-1e6f, -1e6f, 1e6f, 1e6f);
        }

        private Axis firstShown(boolean vertical) {
            for (int i = 0; i < axes.size(); i++) {
                Axis a = axes.get(i);
                if (a.vertical() == vertical && a.enabled()) {
                    return a;
                }
            }
            return null;
        }

        private void axis(Canvas c, Axis a) {
            ResolvedStyle as = styleOf(a.part);
            float tick = as.getFloat(ChartStyles.TICK_LENGTH) * scale;
            float minor = as.getFloat(ChartStyles.MINOR_TICK_LENGTH) * scale;
            float gap = as.getFloat(Styles.GAP) * scale;
            long stroke = as.rgba(ChartStyles.STROKE);
            long text = c.fade(as.rgba(Styles.COLOR));
            // where the axis line lies, and which way is outwards
            float base;
            float out;
            switch (a.side()) {
                case BOTTOM -> {
                    base = plotY1 + a.offset;
                    out = 1;
                }
                case TOP -> {
                    base = plotY0 - a.offset;
                    out = -1;
                }
                case LEFT -> {
                    base = plotX0 - a.offset;
                    out = -1;
                }
                default -> {
                    base = plotX1 + a.offset;
                    out = 1;
                }
            }
            Paint bg = as.get(Styles.BACKGROUND);
            if (bg instanceof Paint.Box box && Rgba.a(box.color().rgba()) > 0) {
                long fill = c.fade(box.color().rgba());
                if (a.vertical()) {
                    c.box(base, plotY0, base + out * a.thickness, plotY1, fill, 0, 0, 0);
                } else {
                    c.box(plotX0, base, plotX1, base + out * a.thickness, fill, 0, 0, 0);
                }
            }
            c.stroke(as, stroke);
            boolean lines = c.stroking();
            if (lines) {
                if (a.vertical()) {
                    c.line(base, plotY0, base, plotY1);
                } else {
                    c.line(plotX0, base, plotX1, base);
                }
            }
            Ticks t = a.ticks;
            for (int i = 0; i < t.count(); i++) {
                float p = a.toPixel(t.value(i));
                boolean major = t.major(i);
                if (a.vertical() ? p < plotY0 - 0.5f || p > plotY1 + 0.5f : p < plotX0 - 0.5f || p > plotX1 + 0.5f) {
                    continue;
                }
                float len = major ? tick : minor;
                if (lines && len > 0) {
                    if (a.vertical()) {
                        c.line(base, p, base + out * len, p);
                    } else {
                        c.line(p, base, p, base + out * len);
                    }
                }
                if (!major || i >= a.labelBlocks.length || a.labelBlocks[i] == null) {
                    continue;
                }
                TextBlock b = a.labelBlocks[i];
                float lx;
                float ly;
                if (a.vertical()) {
                    lx = out < 0 ? base - tick - gap - b.width() : base + tick + gap;
                    ly = p - b.height() * 0.5f;
                    ly = Math.max(0, Math.min(height - b.height(), ly));
                } else {
                    lx = p - b.width() * 0.5f;
                    lx = Math.max(0, Math.min(width - b.width(), lx));
                    ly = out > 0 ? base + tick + gap : base - tick - gap - b.height();
                }
                c.text(b, lx, ly, text);
            }
            if (a.titleBlock != null && !a.title().isEmpty()) {
                TextBlock b = a.titleBlock;
                long tc = c.fade(styleOf(a.titlePart).rgba(Styles.COLOR));
                if (a.vertical()) {
                    // above the axis, beside the plot's top edge
                    float tx = out < 0 ? Math.max(0, base - b.width()) : Math.min(width - b.width(), base);
                    c.text(b, tx, Math.max(0, plotY0 - b.height() - gap), tc);
                } else {
                    float tx = Math.max(0, Math.min(width - b.width(), (plotX0 + plotX1 - b.width()) * 0.5f));
                    float ty = out > 0 ? base + tick + gap + a.maxLabel + gap : base - tick - gap - a.maxLabel - gap
                        - b.height();
                    c.text(b, tx, ty, tc);
                }
            }
        }
    }

    /** The plot's content: the series. */
    private final class PlotPainter implements Painter {

        @Override
        public long measure(int slot, float w, int widthMode, float h, int heightMode) {
            return Measure.size(0, 0);
        }

        @Override
        public int spare(int recorded) {
            return recorded / 2 + 256;
        }

        @Override
        public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
            // the plot element's box is the plot rectangle: content-box pixels plus this offset
            plotTextSeen = textVersions(2);
            Canvas c = canvas;
            c.begin(list, x0 - plotX0, y0 - plotY0, plotX0, plotY0, plotX1, plotY1, opacity, scale);
            digitalSlot = 0;
            for (int i = 0; i < series.size(); i++) {
                Series s = series.get(i);
                if (s.enabled()) {
                    s.paint(c);
                }
            }
            dataChanged = false;
        }
    }

    int digitalSlot; // the next digital series' level from the bottom, while painting

    /** Over everything: drag tools, annotations, crosshair, highlights, boxes, legend, tooltip. */
    private final class OverlayPainter implements Painter {

        @Override
        public long measure(int slot, float w, int widthMode, float h, int heightMode) {
            return Measure.size(0, 0);
        }

        @Override
        public int spare(int recorded) {
            return recorded / 2 + 64;
        }

        @Override
        public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
            overlayTextSeen = textVersions(1);
            Canvas c = canvas;
            c.begin(list, x0, y0, plotX0, plotY0, plotX1, plotY1, opacity, scale);
            for (int i = 0; i < tools.size(); i++) {
                DragTool t = tools.get(i);
                if (t.enabled()) {
                    t.paint(c);
                }
            }
            for (int i = 0; i < annotations.size(); i++) {
                Annotation a = annotations.get(i);
                if (a.enabled()) {
                    a.paint(c);
                }
            }
            if (crosshairShown) {
                ResolvedStyle cs = styleOf(crosshairPart);
                c.stroke(cs, cs.rgba(ChartStyles.STROKE));
                if (c.stroking()) {
                    c.line(crosshairX, plotY0, crosshairX, plotY1);
                    if (crosshairOwn) {
                        c.line(plotX0, crosshairY, plotX1, crosshairY);
                    }
                }
                ResolvedStyle hs = styleOf(highlightPart);
                Marker m = hs.get(ChartStyles.MARKER);
                float size = hs.getFloat(ChartStyles.MARKER_SIZE) * scale;
                float outline = hs.getFloat(ChartStyles.MARKER_OUTLINE) * scale;
                long ring = c.fade(hs.rgba(ChartStyles.STROKE));
                for (int i = 0; i < hitCount; i++) {
                    Hit h = hits[i];
                    long col = c.fade(Rgba.fade(h.series.color(h.item), hs.getFloat(ChartStyles.MARKER_FILL)));
                    c.marker(m, h.px, h.py, size, col, outline, ring);
                }
            }
            if (mode == Mode.ZOOM_BOX || mode == Mode.SELECT_BOX) {
                ResolvedStyle bs = styleOf(mode == Mode.ZOOM_BOX ? zoomBoxPart : selectBoxPart);
                boxStyled(c, bs, Math.max(plotX0, Math.min(pressX, lastX)), Math.max(plotY0, Math.min(pressY, lastY)),
                    Math.min(plotX1, Math.max(pressX, lastX)), Math.min(plotY1, Math.max(pressY, lastY)));
            }
            c.cull(-1e6f, -1e6f, 1e6f, 1e6f);
            legendPaint(c);
            tooltipPaint(c);
        }

        private void legendPaint(Canvas c) {
            if (legendCount == 0) {
                return;
            }
            ResolvedStyle ls = styleOf(legendPart);
            boxStyled(c, ls, legendX0, legendY0, legendX1, legendY1);
            float swatch = ls.getFloat(ChartStyles.SWATCH_SIZE) * scale;
            float gap = ls.getFloat(Styles.GAP) * scale;
            for (int i = 0; i < legendCount; i++) {
                int o = i * 4;
                Series s = legendSeries[i];
                int item = legendItem[i];
                ResolvedStyle es = styleOf(!s.itemEnabled(item) ? legendEntryHiddenPart
                    : i == legendHover ? legendEntryHoverPart : legendEntryPart);
                float ex = legendRect[o];
                float ey = legendRect[o + 1];
                float eh = legendRect[o + 3] - ey;
                Paint bg = es.get(Styles.BACKGROUND);
                if (bg instanceof Paint.Box box && Rgba.a(box.color().rgba()) > 0) {
                    c.box(ex, ey, legendRect[o + 2], legendRect[o + 3], c.fade(box.color().rgba()), 0, 0, 0);
                }
                float fade = es.getFloat(Styles.OPACITY);
                float sy = ey + (eh - swatch) * 0.5f;
                float before = c.opacity();
                c.opacity(before * fade);
                s.swatch(c, item, ex, sy, ex + swatch, sy + swatch);
                TextBlock b = legendText[i];
                c.text(b, ex + swatch + gap, ey + (eh - b.height()) * 0.5f, c.fade(es.rgba(Styles.COLOR)));
                c.opacity(before);
            }
        }

        private void tooltipPaint(Canvas c) {
            if (tooltipLines == 0) {
                return;
            }
            ResolvedStyle ts = styleOf(tooltipPart);
            Insets pad = ts.get(Styles.PADDING);
            float gap = ts.getFloat(Styles.GAP) * scale;
            float swatch = styleOf(legendPart).getFloat(ChartStyles.SWATCH_SIZE) * scale;
            long text = c.fade(ts.rgba(Styles.COLOR));
            boolean merged = ts != null && styleOf(root).get(ChartStyles.TOOLTIPS) == TooltipMode.MERGED;
            if (merged) {
                boxStyled(c, ts, tooltipX0, tooltipY0, tooltipX1, tooltipY1);
                float ty = tooltipY0 + pad.top() * scale;
                float tx = tooltipX0 + pad.left() * scale;
                ResolvedStyle tt = styleOf(tooltipTitlePart);
                c.text(tooltipText[0], tx, ty, c.fade(tt.rgba(Styles.COLOR)));
                ty += tooltipText[0].height() + gap;
                for (int i = 1; i < tooltipLines; i++) {
                    Hit h = hits[i - 1];
                    TextBlock b = tooltipText[i];
                    float sy = ty + (b.height() - swatch) * 0.5f;
                    h.series.swatch(c, h.item, tx, sy, tx + swatch, sy + swatch);
                    c.text(b, tx + swatch + gap, ty, text);
                    ty += b.height() + gap;
                }
                return;
            }
            // one box beside each highlighted point
            float off = styleOf(root).getFloat(ChartStyles.TOOLTIP_OFFSET) * scale * 0.5f;
            for (int i = 0; i < tooltipLines; i++) {
                Hit h = hits[i];
                TextBlock b = tooltipText[i];
                float w = b.width() + (pad.left() + pad.right()) * scale;
                float hh = b.height() + (pad.top() + pad.bottom()) * scale;
                float bx = h.px + off;
                if (bx + w > width) {
                    bx = h.px - off - w;
                }
                float by = Math.max(0, Math.min(height - hh, h.py - off - hh));
                bx = Math.max(0, bx);
                // below any earlier box it would cover (points close together)
                for (int k = 0; k < i; k++) {
                    int o = k * 4;
                    if (bx < placed[o + 2] && bx + w > placed[o] && by < placed[o + 3] && by + hh > placed[o + 1]) {
                        by = placed[o + 3] + gap;
                        k = -1;
                    }
                }
                if (i * 4 + 4 <= placed.length) {
                    placed[i * 4] = bx;
                    placed[i * 4 + 1] = by;
                    placed[i * 4 + 2] = bx + w;
                    placed[i * 4 + 3] = by + hh;
                }
                boxStyled(c, ts, bx, by, bx + w, by + hh);
                c.text(b, bx + pad.left() * scale, by + pad.top() * scale, text);
            }
        }
    }

    /** A box drawn with {@code s}'s background, border, radius and first shadow. */
    void boxStyled(Canvas c, ResolvedStyle s, float x0, float y0, float x1, float y1) {
        com.truesim.ui.style.value.Radii r = s.get(Styles.RADIUS);
        float radius = r.topLeft() * scale;
        List<com.truesim.ui.style.value.Shadow> shadows = s.get(Styles.SHADOW);
        if (!shadows.isEmpty()) {
            com.truesim.ui.style.value.Shadow sh = shadows.get(0);
            float spread = sh.spread() * scale;
            c.list().shadow(x0 + sh.x() * scale - spread + c.offsetX(), y0 + sh.y() * scale - spread + c.offsetY(),
                x1 + sh.x() * scale + spread + c.offsetX(), y1 + sh.y() * scale + spread + c.offsetY(),
                c.fade(sh.color().rgba()), radius + spread, radius + spread, radius + spread, radius + spread,
                sh.blur() * scale * 0.5f);
        }
        Paint bg = s.get(Styles.BACKGROUND);
        long fill = bg instanceof Paint.Box box ? c.fade(box.color().rgba()) : Rgba.TRANSPARENT;
        com.truesim.ui.style.value.Border border = s.get(Styles.BORDER);
        c.list().box(x0 + c.offsetX(), y0 + c.offsetY(), x1 + c.offsetX(), y1 + c.offsetY(), fill, radius, radius,
            radius, radius, border.width() * scale, c.fade(border.color().rgba()));
    }

    /** No dash. */
    static List<Float> solid() {
        return SOLID;
    }
}
