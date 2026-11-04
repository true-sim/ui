package com.truesim.ui.controls.profiler;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeyHolder;

/**
 * The style keys of the profiler's views (ui-profiler: the frame graph overlay and the profiler window) beyond
 * {@link com.truesim.ui.style.Styles}' and {@link com.truesim.ui.controls.charts.ChartStyles}' ({@code palette},
 * {@code stroke}, {@code line-width} are reused), registered as an installed {@link StyleKeyHolder} so every module
 * compiling a theme knows them; the shipped themes style the views in {@code _profiler.tss} and
 * {@code _profiler-test.tss}. Sizes are in panel units.
 *
 * <p>The parts that read them: {@code profiler-timeline} and {@code profiler-flame} ({@code palette}: zones take its
 * colours by name), {@code profiler-zone}, {@code profiler-flame-node}, {@code profiler-table-row} and
 * {@code profiler-table-header} ({@code row-height}), {@code profiler-lane} ({@code gutter-width},
 * {@code lane-gap}), {@code profiler-zone} ({@code label-min-width}).
 */
@AllowAllocation("keys are declared once")
public final class ProfilerStyles implements StyleKeyHolder {

    /** The height of one row: a depth of zones in a lane, a level of the flame graph, a row of the zone table. */
    public static final StyleKey<Float> ROW_HEIGHT = StyleKey.number("row-height", 18, 2, 512);
    /** The room between two lanes of the timeline. */
    public static final StyleKey<Float> LANE_GAP = StyleKey.number("lane-gap", 6, 0, 512);
    /** The width of the timeline's column of lane names (and of the zone table's name column). */
    public static final StyleKey<Float> GUTTER_WIDTH = StyleKey.number("gutter-width", 140, 0, 4096);
    /** The narrowest zone (in pixels at the UI's scale) that shows its name; narrower zones show their colour only. */
    public static final StyleKey<Float> LABEL_MIN_WIDTH = StyleKey.number("label-min-width", 24, 0, 4096);

    /** Made by {@link java.util.ServiceLoader}: initialises the keys. */
    public ProfilerStyles() {
        // the keys are static
    }
}
