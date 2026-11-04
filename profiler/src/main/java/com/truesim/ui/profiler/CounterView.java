package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.charts.Chart;
import com.truesim.ui.charts.Column;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.data.ScrollPane;
import com.truesim.ui.core.Element;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Recording}'s counters as charts in a scroll pane ({@code profiler-counters}): one {@code chart.profiler-counter}
 * per group, a group being a counter's name up to its last dot ({@code memory.heap} and {@code memory.direct} share
 * {@code memory}; {@code cpu.core.0} to {@code cpu.core.31} share {@code cpu.core}), each counter a line over time in
 * seconds. Counters that appear later get their charts when they do (made then: building allocates, frames do not).
 */
public final class CounterView implements Ui.FrameListener {

    private final Ui ui;
    private final Recording recording;
    private final ScrollPane pane;
    private final List<String> groups = new ArrayList<>();
    private final List<Chart> charts = new ArrayList<>();
    private int counters;

    /** Charts of {@code recording}'s counters in {@code ui}. */
    @AllowAllocation("views are made when a UI is built")
    public CounterView(Ui ui, Recording recording) {
        this.ui = ui;
        this.recording = recording;
        pane = new ScrollPane(ui, "Counters");
        pane.root().style("profiler-counters");
        ui.addFrameListener(this);
    }

    /** The pane: add it where the counters should show. */
    public Element element() {
        return pane.root();
    }

    /** The charts, one per group, in the order their groups appeared. */
    public List<Chart> charts() {
        return charts;
    }

    /** The groups' names, in the order of {@link #charts()}. */
    public List<String> groups() {
        return groups;
    }

    @Override
    public void frame(long nanos) {
        if (recording.counters() != counters) {
            added();
        }
    }

    @AllowAllocation("counters met for the first time")
    private void added() {
        for (int c = counters; c < recording.counters(); c++) {
            String name = recording.counterName(c);
            int dot = name.lastIndexOf('.');
            String group = dot > 0 ? name.substring(0, dot) : name;
            int g = groups.indexOf(group);
            Chart chart;
            if (g < 0) {
                chart = new Chart(ui, group);
                chart.element().style("profiler-counter");
                chart.title(group);
                chart.x().fits(true);
                pane.content().add(chart.element());
                groups.add(group);
                charts.add(chart);
            } else {
                chart = charts.get(g);
            }
            String unit = recording.counterUnit(c);
            if (!unit.isEmpty() && chart.y().title().isEmpty()) {
                chart.y().title(unit);
            }
            chart.line(dot > 0 ? name.substring(dot + 1) : name, new Times(recording, c), new Values(recording, c));
            chart.x().autoFit(true);
            chart.y().autoFit(true);
        }
        counters = recording.counters();
    }

    /** A counter's sample times, in seconds. */
    private static final class Times extends Column {
        private final Recording r;
        private final int c;

        Times(Recording r, int c) {
            this.r = r;
            this.c = c;
        }

        @Override
        public long first() {
            return r.firstSample(c);
        }

        @Override
        public long end() {
            return r.endSample(c);
        }

        @Override
        public double get(long index) {
            return r.sampleTime(c, index) / 1e9;
        }

        @Override
        public long version() {
            return r.endSample(c) + r.firstSample(c);
        }
    }

    /** A counter's sample values. */
    private static final class Values extends Column {
        private final Recording r;
        private final int c;

        Values(Recording r, int c) {
            this.r = r;
            this.c = c;
        }

        @Override
        public long first() {
            return r.firstSample(c);
        }

        @Override
        public long end() {
            return r.endSample(c);
        }

        @Override
        public double get(long index) {
            return r.sampleValue(c, index);
        }

        @Override
        public long version() {
            return r.endSample(c) + r.firstSample(c);
        }
    }
}
