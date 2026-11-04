package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.charts.Chart;
import com.truesim.ui.charts.Columns;
import com.truesim.ui.charts.InfiniteLines;
import com.truesim.ui.charts.LineSeries;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;

/**
 * The frame graph: the last frames' times streaming as lines on a chart (ui-charts), the shippable overlay of the
 * profiler. Lines (series style names, for the theme): {@code cpu} (the frame's start to its submit), {@code wait}
 * (what of it the CPU waited for the GPU or the swapchain), {@code gpu} (the GPU's span of the frame, drawn at its own
 * frame when the GPU finished it), {@code frame} (the time since the previous frame's start); horizontal
 * {@code budget} lines at the frame rates asked for ({@link #budgets}), vertical {@code hitch} lines at frames marked
 * as hitches. Above the chart, a statistics line ({@code label.frame-graph-stats}): minimum, mean and 99th percentile
 * of each line over the frames kept, and the hitches among them, every {@link ProfilerTunables#STATS_INTERVAL}.
 *
 * <p>Elements: {@code frame-graph} (the panel), its stats label and a {@code chart.frame-graph}. A frame with no new
 * data costs a version check; a new frame appends to the chart's rings, and the chart streams its lines (new vertices
 * appended, scrolled by a drawn offset; 7.9 us a frame of CPU). Allocates nothing per frame.
 *
 * <pre>{@code
 * FrameTimes times = new FrameTimes(ui.store().clock(), 600);
 * FrameGraphView graph = new FrameGraphView(ui, times).budgets(60, 120, 144);
 * hud.add(graph.element());
 * }</pre>
 */
public final class FrameGraphView implements Ui.FrameListener {

    private static final int BUDGETS = 4;

    private final Ui ui;
    private final FrameTimes times;
    private final Element root;
    private final Element stats;
    private final Chart chart;
    private final LineSeries cpu;
    private final LineSeries waits;
    private final LineSeries gpu;
    private final LineSeries frame;
    private final InfiniteLines budgetLines;
    private final InfiniteLines hitchLines;
    private final Columns.Values budgetValues;
    private final double[] budgetHz = new double[BUDGETS];
    private int budgetCount;
    private final StringBuilder text = new StringBuilder(160);
    private final FrameTimes.Stats cpuStats = new FrameTimes.Stats();
    private final FrameTimes.Stats gpuStats = new FrameTimes.Stats();
    private final FrameTimes.Stats waitStats = new FrameTimes.Stats();
    private final FrameTimes.Stats frameStats = new FrameTimes.Stats();
    private long lastStats = Long.MIN_VALUE;
    private long seen = -2;
    private boolean statsDirty = true;

    /** A frame graph of {@code times} in {@code ui}: add {@link #element()} where it should show. */
    @AllowAllocation("views are made when a UI is built")
    public FrameGraphView(Ui ui, FrameTimes times) {
        this.ui = ui;
        this.times = times;
        root = ui.element("frame-graph", null);
        root.semantics(Role.GROUP, "Frame times");
        stats = ui.label("frame-graph-stats", "");
        chart = new Chart(ui, "Frame times");
        chart.element().style("frame-graph");
        root.add(stats).add(chart.element());
        var frames = Columns.of(times.frames());
        frame = (LineSeries) chart.line("frame", frames, Columns.of(times.intervals())).styled("frame");
        cpu = (LineSeries) chart.line("CPU", frames, Columns.of(times.cpu())).styled("cpu");
        gpu = (LineSeries) chart.line("GPU", Columns.of(times.gpuFrames()), Columns.of(times.gpu())).styled("gpu");
        waits = (LineSeries) chart.line("wait", frames, Columns.of(times.waits())).styled("wait");
        budgetValues = Columns.of(new double[BUDGETS]);
        java.util.Arrays.fill(budgetValues.values(), Double.NaN);
        budgetLines = (InfiniteLines) chart.infiniteLines("budget", budgetValues, false).styled("budget");
        hitchLines = (InfiniteLines) chart.infiniteLines("hitch", Columns.of(times.hitches()), true).styled("hitch");
        chart.x().range(0, times.capacity()).fits(false).follow(true).enabled(false);
        chart.y().range(0, 20).fits(false).title("ms");
        budgets(60);
        ui.addFrameListener(this);
    }

    /** The panel: add it to a parent to show the graph. */
    public Element element() {
        return root;
    }

    /** The chart (its axes, series and look). */
    public Chart chart() {
        return chart;
    }

    /** The times it shows. */
    public FrameTimes times() {
        return times;
    }

    /** The CPU line. */
    public LineSeries cpuLine() {
        return cpu;
    }

    /** The GPU line. */
    public LineSeries gpuLine() {
        return gpu;
    }

    /** The waiting line. */
    public LineSeries waitLine() {
        return waits;
    }

    /** The frame interval line. */
    public LineSeries frameLine() {
        return frame;
    }

    /** The budget lines. */
    public InfiniteLines budgetLines() {
        return budgetLines;
    }

    /** The hitch lines. */
    public InfiniteLines hitchLines() {
        return hitchLines;
    }

    /**
     * Draws a budget line at each of {@code hz} frames a second (up to four; 0 or less: none), e.g. 60, 120, 144 and
     * the display's rate; returns this. Allocates nothing.
     */
    public FrameGraphView budgets(double... hz) {
        budgetCount = 0;
        double[] v = budgetValues.values();
        for (int i = 0; i < BUDGETS; i++) {
            double h = i < hz.length ? hz[i] : 0;
            if (h > 0) {
                budgetHz[budgetCount] = h;
                v[budgetCount++] = 1000 / h;
            }
        }
        for (int i = budgetCount; i < BUDGETS; i++) {
            v[i] = Double.NaN;
        }
        budgetValues.changed();
        statsDirty = true;
        return this;
    }

    /** How many budget lines there are. */
    public int budgetCount() {
        return budgetCount;
    }

    /** Budget {@code i}'s frames a second. */
    public double budgetHz(int i) {
        return budgetHz[i];
    }

    /** The statistics line's text as last shown. */
    public CharSequence statsText() {
        return text;
    }

    /** CPU statistics as last shown. */
    public FrameTimes.Stats cpuStats() {
        return cpuStats;
    }

    /** GPU statistics as last shown. */
    public FrameTimes.Stats gpuStats() {
        return gpuStats;
    }

    /** Frame interval statistics as last shown. */
    public FrameTimes.Stats frameStats() {
        return frameStats;
    }

    @Override
    public void frame(long nanos) {
        long latest = times.latest();
        if (latest != seen) {
            seen = latest;
            statsDirty = true;
        }
        long every = (long) (ProfilerTunables.STATS_INTERVAL.get() * 1e9);
        if (statsDirty && (lastStats == Long.MIN_VALUE || nanos - lastStats >= every)) {
            lastStats = nanos;
            statsDirty = false;
            updateStats();
        }
    }

    private void updateStats() {
        times.stats(FrameTimes.Kind.CPU, cpuStats);
        times.stats(FrameTimes.Kind.GPU, gpuStats);
        times.stats(FrameTimes.Kind.WAIT, waitStats);
        times.stats(FrameTimes.Kind.INTERVAL, frameStats);
        text.setLength(0);
        line(text, "CPU", cpuStats);
        text.append("   ");
        line(text, "GPU", gpuStats);
        text.append("   ");
        line(text, "frame", frameStats);
        text.append("   wait ");
        Units.millis(text, waitStats.count == 0 ? 0 : waitStats.avg, 2);
        text.append("   hitches ").append(times.hitchesKept());
        ui.text(stats, text);
        double top = 0;
        top = Math.max(top, finiteOr(cpuStats.p99, 0));
        top = Math.max(top, finiteOr(gpuStats.p99, 0));
        top = Math.max(top, finiteOr(frameStats.p99, 0));
        top *= ProfilerTunables.GRAPH_HEADROOM.get();
        double slowest = 0;
        for (int i = 0; i < budgetCount; i++) {
            slowest = Math.max(slowest, 1000 / budgetHz[i]);
        }
        top = Math.max(top, slowest * 1.15);
        if (!(top > 0)) {
            top = 20;
        }
        chart.y().range(0, nice(top));
    }

    private static void line(StringBuilder out, String what, FrameTimes.Stats s) {
        out.append(what).append(' ');
        if (s.count == 0) {
            out.append('-');
            return;
        }
        Units.millis(out, s.min, 2).append(" / ");
        Units.millis(out, s.avg, 2).append(" / ");
        Units.millis(out, s.p99, 2).append(" ms");
    }

    private static double finiteOr(double v, double fallback) {
        return v - v == 0 ? v : fallback;
    }

    /** {@code v} rounded up to 1, 2, 2.5 or 5 times a power of ten. */
    static double nice(double v) {
        double p = com.truesim.common.math.Maths.pow(10, Math.floor(com.truesim.common.math.Maths.log10(v)));
        double m = v / p;
        double n = m <= 1 ? 1 : m <= 2 ? 2 : m <= 2.5 ? 2.5 : m <= 5 ? 5 : 10;
        return n * p;
    }
}
