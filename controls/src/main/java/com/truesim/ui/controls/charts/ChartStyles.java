package com.truesim.ui.controls.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeyHolder;
import com.truesim.ui.style.StyleTypes;
import com.truesim.ui.style.value.Color;
import java.util.List;

/**
 * The style keys of charts (beyond {@link com.truesim.ui.style.Styles}' colours, fonts, boxes and borders), registered
 * for {@code .tss} compilation as an installed {@link StyleKeyHolder}; the shipped themes style charts in the partials
 * {@code _charts.tss} and {@code _charts-test.tss}. Sizes are in panel units (scaled with the UI).
 *
 * <p>The parts that read them: {@code chart} (the palette, colormap and tooltip mode), {@code chart-axis},
 * {@code chart-grid} and {@code chart-grid-minor}, series parts ({@code chart-line}, {@code chart-scatter},
 * {@code chart-area}, {@code chart-bars} and the others, with the series' style name), {@code chart-legend},
 * {@code chart-crosshair}, {@code chart-highlight} and the drag tools.
 */
@AllowAllocation("keys are declared once")
public final class ChartStyles implements StyleKeyHolder {

    /** A stroke's colour: axis lines and ticks, grid lines, the crosshair, drag lines, outlines. */
    public static final StyleKey<Color> STROKE = StyleKey.of("stroke", StyleTypes.color(), Color.TRANSPARENT);
    /** A line's width: series lines, axis lines, grid lines, the crosshair, drag tools (0: none). */
    public static final StyleKey<Float> LINE_WIDTH = StyleKey.number("line-width", 1, 0, 64);
    /** A line's dash pattern: lengths of dashes and gaps in turn ({@code 6px 4px}); {@code none}: solid. */
    public static final StyleKey<List<Float>> LINE_DASH =
        StyleKey.of("line-dash", StyleTypes.list(StyleTypes.number(0, 10_000)), List.of());
    /** How a series' line segments meet. */
    public static final StyleKey<LineJoin> LINE_JOIN = StyleKey.of("line-join", LineJoin.class, LineJoin.NONE);
    /** The fill of areas, bars, slices and bands; transparent: the series' colour. */
    public static final StyleKey<Color> FILL = StyleKey.of("fill", StyleTypes.color(), Color.TRANSPARENT);
    /** The opacity of fills (areas, bands, bars, slices, heatmaps) over the series' colour. */
    public static final StyleKey<Float> FILL_OPACITY = StyleKey.of("fill-opacity", StyleTypes.fraction(), 1f);
    /** The marker drawn at each sample. */
    public static final StyleKey<Marker> MARKER = StyleKey.of("marker", Marker.class, Marker.NONE);
    /** A marker's size (its box's side). */
    public static final StyleKey<Float> MARKER_SIZE = StyleKey.number("marker-size", 6, 0, 256);
    /** The opacity of a marker's fill (0: an outline only). */
    public static final StyleKey<Float> MARKER_FILL = StyleKey.of("marker-fill", StyleTypes.fraction(), 1f);
    /** A marker's outline width (in the series' colour; 0: none). */
    public static final StyleKey<Float> MARKER_OUTLINE = StyleKey.number("marker-outline", 0, 0, 64);
    /** The colours series and items take in turn when their part sets no {@code color}. */
    public static final StyleKey<List<Color>> PALETTE = StyleKey.of("palette", StyleTypes.list(StyleTypes.color()),
        List.of(Color.hex("#4c72b0"), Color.hex("#dd8452"), Color.hex("#55a868"), Color.hex("#c44e52"),
            Color.hex("#8172b3"), Color.hex("#937860"), Color.hex("#da8bc3"), Color.hex("#8c8c8c"),
            Color.hex("#ccb974"), Color.hex("#64b5cd")));
    /** The colormap of heatmaps and 2D histograms: colours evenly spaced from the lowest value to the highest. */
    public static final StyleKey<List<Color>> COLORMAP = StyleKey.of("colormap", StyleTypes.list(StyleTypes.color()),
        List.of(Color.hex("#440154"), Color.hex("#482878"), Color.hex("#3e4989"), Color.hex("#31688e"),
            Color.hex("#26828e"), Color.hex("#1f9e89"), Color.hex("#35b779"), Color.hex("#6ece58"),
            Color.hex("#b5de2b"), Color.hex("#fde725")));
    /** A bar's width as a share of the space between bars (grouped bars share it). */
    public static final StyleKey<Float> BAR_WIDTH = StyleKey.of("bar-width", StyleTypes.fraction(), 0.67f);
    /** The width of error bars' caps and of candlesticks' bodies' minimum. */
    public static final StyleKey<Float> CAP_WIDTH = StyleKey.number("cap-width", 6, 0, 256);
    /** A pie's hole as a share of its radius (0: a pie, above: a donut). */
    public static final StyleKey<Float> DONUT_HOLE = StyleKey.of("donut-hole", StyleTypes.fraction(), 0f);
    /** The height of a digital series' high level, and the gap between digital series. */
    public static final StyleKey<Float> DIGITAL_HEIGHT = StyleKey.number("digital-height", 12, 1, 512);
    /** The colour of rising candles (close above open); transparent: the series' colour. */
    public static final StyleKey<Color> UP_COLOR = StyleKey.of("up-color", StyleTypes.color(), Color.TRANSPARENT);
    /** The colour of falling candles. */
    public static final StyleKey<Color> DOWN_COLOR = StyleKey.of("down-color", StyleTypes.color(), Color.TRANSPARENT);
    /** A major tick's length (outwards from the axis line). */
    public static final StyleKey<Float> TICK_LENGTH = StyleKey.number("tick-length", 5, 0, 128);
    /** A minor tick's length. */
    public static final StyleKey<Float> MINOR_TICK_LENGTH = StyleKey.number("minor-tick-length", 3, 0, 128);
    /** The least distance between major ticks (their number follows from it and the axis's length). */
    public static final StyleKey<Float> TICK_SPACING = StyleKey.number("tick-spacing", 80, 8, 4096);
    /** Whether an axis shows minor ticks (and the grid minor lines). */
    public static final StyleKey<Boolean> MINOR_TICKS = StyleKey.of("minor-ticks", StyleTypes.bool(), true);
    /** Empty room around fitted data, as a share of its span on each side. */
    public static final StyleKey<Float> FIT_PADDING = StyleKey.of("fit-padding", StyleTypes.fraction(), 0.05f);
    /** Where the legend sits in the plot. */
    public static final StyleKey<LegendPosition> LEGEND_POSITION =
        StyleKey.of("legend-position", LegendPosition.class, LegendPosition.TOP_RIGHT);
    /** The size of a legend entry's colour swatch. */
    public static final StyleKey<Float> SWATCH_SIZE = StyleKey.number("swatch-size", 10, 0, 256);
    /** How the chart shows values at the hovered X. */
    public static final StyleKey<TooltipMode> TOOLTIPS =
        StyleKey.of("chart-tooltips", TooltipMode.class, TooltipMode.MERGED);
    /** How far a tooltip keeps from the pointer. */
    public static final StyleKey<Float> TOOLTIP_OFFSET = StyleKey.number("tooltip-offset", 14, 0, 512);

    /** Made by {@link java.util.ServiceLoader}: initialises the keys. */
    public ChartStyles() {
        // the keys are static
    }
}
