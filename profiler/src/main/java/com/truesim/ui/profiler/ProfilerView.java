package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.VersionedInt;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Button;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.SplitPane;
import com.truesim.ui.controls.basic.TabPane;
import com.truesim.ui.controls.basic.ToggleButton;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;

/**
 * The detailed profiler: a toolbar, the {@link TimelineView} of every lane, and under it, in tabs, the
 * {@link FlameGraphView} and {@link ZoneTableView} of the selected span (the visible span without one; the selected
 * zone's lane, else the first thread's) and the {@link CounterView}. While the timeline is live the flame graph and the
 * table follow every {@link ProfilerTunables#STATS_INTERVAL}; a selection updates them at once.
 *
 * <p>The toolbar's state is the application's to act on (the engine does): {@link #frozen()} (stop taking new frames
 * into the recording), {@link #freezeOnHitch()} (freeze shortly after a hitch), {@link #onStep} (take one frame while
 * frozen), {@link #onExport} (save the recording). Elements: {@code profiler} (the panel), {@code profiler-toolbar},
 * a {@code label.profiler-status}, the split pane and tab pane of ui-controls.
 */
public final class ProfilerView implements Ui.FrameListener {

    private final Ui ui;
    private final Recording recording;
    private final Element root;
    private final Element toolbar;
    private final Element status;
    private final TimelineView timeline;
    private final FlameGraphView flame;
    private final ZoneTableView table;
    private final CounterView counters;
    private final TabPane tabs;
    private final SplitPane split;
    private final VersionedBoolean frozen;
    private final VersionedBoolean freezeOnHitch;
    private final ToggleButton freezeButton;
    private final ToggleButton hitchButton;
    private final Button stepButton;
    private final Button liveButton;
    private final Button exportButton;
    private Runnable step;
    private Runnable export;
    private long seenRecording = -1;
    private long seenSelection = -1;
    private long lastUpdate = Long.MIN_VALUE;
    private final StringBuilder text = new StringBuilder(160);

    /** The profiler over {@code recording}, built with {@code controls}: add {@link #element()} where it should show. */
    @AllowAllocation("views are made when a UI is built")
    public ProfilerView(Controls controls, Recording recording) {
        this.ui = controls.ui();
        this.recording = recording;
        root = ui.element("profiler", null);
        root.semantics(Role.GROUP, "Profiler");
        toolbar = ui.element("profiler-toolbar", null);
        toolbar.semantics(Role.TOOLBAR, "Profiler tools");
        frozen = controls.clock().booleanValue(false);
        freezeOnHitch = controls.clock().booleanValue(false);
        freezeButton = controls.toggle("Freeze", frozen);
        stepButton = controls.button("Step").onClick(this::stepClicked);
        hitchButton = controls.toggle("Freeze on hitch", freezeOnHitch);
        liveButton = controls.button("Live").onClick(this::goLive);
        exportButton = controls.button("Export").onClick(this::exportClicked);
        status = ui.label("profiler-status", "");
        toolbar.add(freezeButton.element()).add(stepButton.element()).add(hitchButton.element())
            .add(liveButton.element()).add(exportButton.element()).add(status);
        timeline = new TimelineView(ui, recording);
        flame = new FlameGraphView(ui, recording);
        table = new ZoneTableView(ui, recording);
        counters = new CounterView(ui, recording);
        VersionedInt tab = controls.clock().intValue(0);
        tabs = controls.tabs("Profiler views", TabPane.Side.TOP, tab);
        tabs.add("Flame graph", flame.element(), false);
        tabs.add("Zones", table.element(), false);
        tabs.add("Counters", counters.element(), false);
        split = controls.split("Profiler", true, controls.clock().doubleValue(0.55));
        split.first().add(timeline.element());
        split.second().add(tabs.element());
        root.add(toolbar).add(split.element());
        ui.addFrameListener(this);
    }

    private void stepClicked() {
        if (step != null) {
            step.run();
        }
    }

    private void exportClicked() {
        if (export != null) {
            export.run();
        }
    }

    private void goLive() {
        timeline.selectSpan(0, 0);
        timeline.live(true);
    }

    /** The panel. */
    public Element element() {
        return root;
    }

    /** The recording shown. */
    public Recording recording() {
        return recording;
    }

    /** The timeline. */
    public TimelineView timeline() {
        return timeline;
    }

    /** The flame graph. */
    public FlameGraphView flameGraph() {
        return flame;
    }

    /** The zone table. */
    public ZoneTableView zoneTable() {
        return table;
    }

    /** The counters. */
    public CounterView counters() {
        return counters;
    }

    /** The tabs under the timeline (flame graph, zones, counters). */
    public TabPane tabs() {
        return tabs;
    }

    /** The split between the timeline and the tabs. */
    public SplitPane split() {
        return split;
    }

    /** Whether the user froze the recording (the Freeze toggle). */
    public VersionedBoolean frozen() {
        return frozen;
    }

    /** Whether to freeze shortly after a hitch (the toggle). */
    public VersionedBoolean freezeOnHitch() {
        return freezeOnHitch;
    }

    /** The Freeze toggle. */
    public ToggleButton freezeButton() {
        return freezeButton;
    }

    /** The Step button. */
    public Button stepButton() {
        return stepButton;
    }

    /** The Export button. */
    public Button exportButton() {
        return exportButton;
    }

    /** Runs {@code action} when Step is clicked (null: nothing); returns this. */
    public ProfilerView onStep(Runnable action) {
        step = action;
        return this;
    }

    /** Runs {@code action} when Export is clicked (null: nothing); returns this. */
    public ProfilerView onExport(Runnable action) {
        export = action;
        return this;
    }

    /** The status line's text as last shown. */
    public CharSequence statusText() {
        return text;
    }

    @Override
    public void frame(long nanos) {
        long sel = timeline.selectionVersion();
        long rec = recording.version();
        boolean selected = sel != seenSelection;
        long every = (long) (ProfilerTunables.STATS_INTERVAL.get() * 1e9);
        boolean due = rec != seenRecording && (lastUpdate == Long.MIN_VALUE || nanos - lastUpdate >= every);
        if (!selected && !due) {
            return;
        }
        seenSelection = sel;
        seenRecording = rec;
        lastUpdate = nanos;
        int lane = timeline.selectedLane();
        if (lane < 0 || lane >= recording.lanes() || recording.laneKind(lane) == LaneKind.MARKERS) {
            lane = firstThread();
        }
        long from = timeline.spanFrom();
        long to = timeline.spanTo();
        if (!timeline.hasSpan() && timeline.live() && recording.endFrame() - recording.firstFrame() >= 2) {
            // live without a selection: the newest whole frame
            long f = recording.endFrame() - 2;
            from = recording.frameStart(f);
            to = recording.frameStart(f + 1);
        }
        flame.show(lane, from, to);
        table.show(timeline.selectedLane() >= 0 ? lane : -1, from, to);
        status(from, to);
    }

    private int firstThread() {
        for (int l = 0; l < recording.lanes(); l++) {
            if (recording.laneKind(l) == LaneKind.THREAD) {
                return l;
            }
        }
        return recording.lanes() > 0 ? 0 : -1;
    }

    private void status(long from, long to) {
        text.setLength(0);
        text.append(frozen.get() ? "frozen" : timeline.live() ? "live" : "paused");
        text.append("   frames ").append(recording.endFrame() - recording.firstFrame());
        text.append("   span ");
        Units.duration(text, Math.max(0, to - from));
        int lane = timeline.selectedLane();
        long zone = timeline.selectedZone();
        if (lane >= 0 && zone >= recording.firstZone(lane) && zone < recording.endZone(lane)) {
            long s = recording.zoneStart(lane, zone);
            long e = recording.zoneEnd(lane, zone);
            text.append("   ").append(recording.nameOf(recording.zoneName(lane, zone))).append(' ');
            if (e == Recording.OPEN) {
                text.append("(open)");
            } else {
                Units.duration(text, e - s);
            }
        }
        ui.text(status, text);
    }
}
