package com.truesim.ui.charts;

import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.TunableSource;
import java.util.List;

/**
 * The tunables of charts (the engine exposes them as settings, group {@code ui}): decimation thresholds, pyramid and
 * grid sizes, zoom and follow behaviour, hover reach and limits. Listed by {@code Ui.tunables()} through the
 * {@link TunableSource} service.
 */
public final class ChartTunables implements TunableSource {

    /** How much one wheel notch zooms: the share of the range taken off (in) or added (out). */
    public static final Tunable ZOOM_STEP = Tunable.of("ui.chart.zoomStep", 0.1f, 0.01f, 0.9f,
        "Share of a chart's range one wheel notch zooms by");
    /** Above this many visible samples per pixel column, lines are decimated. */
    public static final Tunable DECIMATE_ABOVE = Tunable.of("ui.chart.decimateAbove", 1.5f, 0.1f, 1000,
        "Samples per pixel above which chart lines draw their min-max envelope");
    /** Values per leaf block of a min-max pyramid (a power of two: memory against query time). */
    public static final Tunable PYRAMID_BLOCK = Tunable.of("ui.chart.pyramidBlock", 8, 2, 4096,
        "Samples per block of a chart's min-max pyramid");
    /** LTTB's target: picked samples per pixel of the plot's width. */
    public static final Tunable LTTB_PER_PIXEL = Tunable.of("ui.chart.lttbPerPixel", 0.5f, 0.05f, 8,
        "Samples LTTB keeps per pixel of a chart's width");
    /** The most visible samples LTTB reduces (it reads all of them); more are reduced by min-max instead. */
    public static final Tunable LTTB_MAX_INPUT = Tunable.of("ui.chart.lttbMaxInput", 2_000_000, 1000, 1e9f,
        "Most samples LTTB reduces in a frame; more use the min-max envelope");
    /** The most samples a series draws without decimation (markers, unsorted lines). */
    public static final Tunable MAX_POINTS = Tunable.of("ui.chart.maxPoints", 200_000, 100, 1e8f,
        "Most samples a chart series draws one by one");
    /** Points per cell of a scatter series' nearest-point grid. */
    public static final Tunable GRID_PER_CELL = Tunable.of("ui.chart.gridPerCell", 4, 1, 256,
        "Points per cell of a scatter series' hit grid");
    /** How near (in pixels) the pointer must be for a point to be highlighted and shown in tooltips. */
    public static final Tunable HOVER_DISTANCE = Tunable.of("ui.chart.hoverDistance", 32, 1, 4096,
        "Pixels within which a chart highlights the point nearest the pointer");
    /** How many majors an axis shows at most. */
    public static final Tunable MAX_TICKS = Tunable.of("ui.chart.maxTicks", 40, 2, 1000,
        "Most major ticks a chart axis shows");
    /** Share of a following X axis's span kept empty ahead of the newest sample. */
    public static final Tunable FOLLOW_LEAD = Tunable.of("ui.chart.followLead", 0.02f, 0, 0.9f,
        "Share of a following chart axis kept ahead of the newest sample");
    /** Pixels the pointer must move with a button held before a press becomes a pan, box or drag. */
    public static final Tunable DRAG_PIXELS = Tunable.of("ui.chart.dragPixels", 3, 0, 64,
        "Pixels a press moves before it pans or draws a box in a chart");
    /** Pixels around a drag tool (point, line, rectangle edge) that grab it. */
    public static final Tunable GRAB_PIXELS = Tunable.of("ui.chart.grabPixels", 6, 1, 64,
        "Pixels around a chart's drag tools that grab them");
    /** The longest arc (in pixels) one segment of a pie slice spans. */
    public static final Tunable ARC_PIXELS = Tunable.of("ui.chart.arcPixels", 4, 0.5f, 64,
        "Pixels of a pie's arc one triangle spans");
    /** The most texels a heatmap cell takes in its texture (cells stay sharp under filtering). */
    public static final Tunable HEATMAP_TEXELS = Tunable.of("ui.chart.heatmapTexels", 8, 1, 64,
        "Texels per heatmap cell side");

    /** Made by {@link java.util.ServiceLoader}. */
    public ChartTunables() {
        // the tunables are static
    }

    /** Every chart tunable. */
    public static List<Tunable> all() {
        return List.of(ZOOM_STEP, DECIMATE_ABOVE, PYRAMID_BLOCK, LTTB_PER_PIXEL, LTTB_MAX_INPUT, MAX_POINTS,
            GRID_PER_CELL, HOVER_DISTANCE, MAX_TICKS, FOLLOW_LEAD, DRAG_PIXELS, GRAB_PIXELS, ARC_PIXELS,
            HEATMAP_TEXELS);
    }

    @Override
    public List<Tunable> tunables() {
        return all();
    }
}
