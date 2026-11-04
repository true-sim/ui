package com.truesim.ui.profiler;

import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.TunableSource;
import java.util.List;

/**
 * The tunables of the profiler's views (the engine exposes them as settings, group {@code ui}), listed by
 * {@code Ui.tunables()} through the {@link TunableSource} service.
 */
public final class ProfilerTunables implements TunableSource {

    /** Seconds between updates of the frame graph's statistics line and of the zone table while live. */
    public static final Tunable STATS_INTERVAL = Tunable.of("ui.profiler.statsInterval", 0.5f, 0, 10,
        "Seconds between updates of the profiler's statistics while live");
    /** The span the timeline shows while it follows the newest frames, in milliseconds. */
    public static final Tunable LIVE_SPAN = Tunable.of("ui.profiler.liveSpan", 50, 0.01f, 600_000,
        "Milliseconds of the newest frames the profiler's timeline shows while live");
    /** How much one wheel notch zooms the timeline: the share of its span taken off (in) or added (out). */
    public static final Tunable ZOOM_STEP = Tunable.of("ui.profiler.zoomStep", 0.15f, 0.01f, 0.9f,
        "Share of the profiler timeline's span one wheel notch zooms by");
    /** The shortest span the timeline zooms to, in nanoseconds. */
    public static final Tunable MIN_SPAN = Tunable.of("ui.profiler.minSpan", 1_000, 10, 1e9f,
        "Shortest span in nanoseconds the profiler's timeline zooms to");
    /** The most zones the timeline records in a frame; past it, the rest of a lane is drawn as busy. */
    public static final Tunable MAX_ZONES = Tunable.of("ui.profiler.maxZones", 20_000, 100, 1e7f,
        "Most zones the profiler's timeline draws at once");
    /** The most distinct call paths the flame graph keeps. */
    public static final Tunable MAX_NODES = Tunable.of("ui.profiler.maxNodes", 4096, 16, 1e6f,
        "Most call paths the profiler's flame graph shows");
    /** The frame graph's top: at least this many times its slowest budget's milliseconds. */
    public static final Tunable GRAPH_HEADROOM = Tunable.of("ui.profiler.graphHeadroom", 1.25f, 1, 10,
        "How far above the frames' 99th percentile the frame graph reaches");

    /** Made by {@link java.util.ServiceLoader}. */
    public ProfilerTunables() {
        // the tunables are static
    }

    /** Every profiler tunable. */
    public static List<Tunable> all() {
        return List.of(STATS_INTERVAL, LIVE_SPAN, ZOOM_STEP, MIN_SPAN, MAX_ZONES, MAX_NODES, GRAPH_HEADROOM);
    }

    @Override
    public List<Tunable> tunables() {
        return all();
    }
}
