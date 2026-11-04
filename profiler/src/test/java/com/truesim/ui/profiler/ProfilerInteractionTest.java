package com.truesim.ui.profiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.testkit.SyntheticInput;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The rest of the views' behaviour: toolbar actions, scrolling, hovering, merged runs, counters, the edges. */
class ProfilerInteractionTest {

    private static final class Rig {
        final Ui ui = ProfilerViewTest.ui("default");
        final ProfilerGallery g = ProfilerGallery.build(ui);
        final SyntheticInput in = new SyntheticInput();
        final ProfilerViewTest.Runner run = new ProfilerViewTest.Runner(ui, in, new UiInput(16));

        Rig() {
            run.settle();
        }

        void click(PaintedView v, float x, float y) {
            in.mouse(v.pointerX(x), v.pointerY(y), Pointer.PRIMARY);
            run.frame();
            in.mouse(v.pointerX(x), v.pointerY(y), 0);
            run.frame();
        }

        void wheel(PaintedView v, float x, float y, float notches, int modifiers) {
            in.mouse(v.pointerX(x), v.pointerY(y), 0);
            in.modifiers(SyntheticInput.MOUSE, modifiers);
            run.frame();
            in.scroll(SyntheticInput.MOUSE, 0, notches);
            run.frame();
            in.modifiers(SyntheticInput.MOUSE, 0);
        }
    }

    @Test
    void theToolbarRunsItsActionsAndShowsTheState() {
        Rig r = new Rig();
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger exports = new AtomicInteger();
        r.g.profiler.onStep(steps::incrementAndGet).onExport(exports::incrementAndGet);
        r.g.profiler.stepButton().click();
        r.g.profiler.exportButton().click();
        assertThat(steps.get()).isEqualTo(1);
        assertThat(exports.get()).isEqualTo(1);
        r.g.profiler.frozen().set(true);
        r.g.recording.commit();
        r.run.settle();
        assertThat(r.g.profiler.statusText().toString()).startsWith("frozen");
        r.g.profiler.onStep(null).onExport(null);
        r.g.profiler.stepButton().click(); // nothing to run
        r.g.profiler.timeline().live(true);
        r.g.recording.commit();
        r.run.settle();
        assertThat(r.g.profiler.timeline().viewEnd()).isEqualTo(r.g.recording.endTime());
    }

    @Test
    void shiftWheelScrollsTheLanesAndTheZoomStopsAtItsLeast() {
        Rig r = new Rig();
        r.g.profiler.split().share(0.2); // lanes taller than the timeline: they scroll
        r.run.settle();
        TimelineView tl = r.g.profiler.timeline();
        float before = tl.laneTopOf(1);
        float x = (tl.left() + tl.right()) / 2;
        float y = tl.top() + 30;
        r.wheel(tl, x, y, -3, Pointer.SHIFT);
        assertThat(tl.laneTopOf(1)).isLessThan(before);
        r.wheel(tl, x, y, 3, Pointer.SHIFT);
        assertThat(tl.laneTopOf(1)).isEqualTo(before);
        for (int i = 0; i < 40; i++) {
            r.wheel(tl, x, y, 5, 0);
        }
        assertThat(tl.viewEnd() - tl.viewStart()).isEqualTo((long) ProfilerTunables.MIN_SPAN.get());
        r.wheel(tl, tl.left() - 50, y, 1, 0); // outside: nothing
        r.click(tl, x, tl.bottom() - 2); // empty space: nothing selected
        assertThat(tl.selectedZone()).isEqualTo(-1);
        assertThat(tl.laneAt(-1000)).isEqualTo(-1);
        assertThat(tl.zoneAt(-1, x, y)).isEqualTo(-1);
    }

    @Test
    void shiftDraggingSelectsASpanAndADoubleClickGoesLive() {
        Rig r = new Rig();
        TimelineView tl = r.g.profiler.timeline();
        float y = tl.top() + 60;
        float a = tl.left() + 300;
        r.in.modifiers(SyntheticInput.MOUSE, Pointer.SHIFT);
        r.in.mouse(tl.pointerX(a), tl.pointerY(y), Pointer.PRIMARY);
        r.run.frame();
        r.in.mouse(tl.pointerX(a + 200), tl.pointerY(y), Pointer.PRIMARY);
        r.run.frame();
        r.in.mouse(tl.pointerX(a + 200), tl.pointerY(y), 0);
        r.run.frame();
        r.in.modifiers(SyntheticInput.MOUSE, 0);
        assertThat(tl.hasSpan()).isTrue();
        assertThat(tl.spanTo()).isGreaterThan(tl.spanFrom());
        r.run.settle();
        assertThat(r.g.profiler.flameGraph().tree().time(0)).isEqualTo(tl.spanTo() - tl.spanFrom());
        // a double click
        for (int i = 0; i < 2; i++) {
            r.in.mouse(tl.pointerX(a), tl.pointerY(y), Pointer.PRIMARY);
            r.run.frame();
            r.in.mouse(tl.pointerX(a), tl.pointerY(y), 0);
            r.run.frame();
        }
        assertThat(tl.live()).isTrue();
        assertThat(tl.hasSpan()).isFalse();
    }

    @Test
    void zonesBeyondTheCapMergeIntoRuns() {
        Rig r = new Rig();
        float max = ProfilerTunables.MAX_ZONES.get();
        try {
            ProfilerTunables.MAX_ZONES.set(100);
            TimelineView tl = r.g.profiler.timeline();
            tl.show(r.g.recording.startTime(), r.g.recording.endTime()); // everything: thousands of zones
            r.run.settle();
            assertThat(tl.drawnZones()).isGreaterThan(100).isLessThan(5000);
        } finally {
            ProfilerTunables.MAX_ZONES.set(max);
        }
    }

    @Test
    void theTableScrollsSelectsAndSortsEveryColumn() {
        Rig r = new Rig();
        r.g.profiler.tabs().select(1);
        r.run.settle();
        ZoneTableView t = r.g.profiler.zoneTable();
        float y = t.top() + 60;
        r.click(t, t.left() + 20, y);
        assertThat(t.selectedName()).isGreaterThanOrEqualTo(0);
        r.wheel(t, t.left() + 20, y, -1, 0);
        r.wheel(t, t.left() + 20, y, 1, 0);
        for (ZoneStats.Column c : ZoneStats.Column.values()) {
            t.sort(c, true);
            r.run.frame();
            assertThat(t.stats().sortedBy()).isEqualTo(c);
        }
        // a header click on the sorted column reverses it
        float hx = t.right() - 4;
        r.click(t, hx, t.top() + 4);
        assertThat(t.stats().sortedBy()).isEqualTo(ZoneStats.Column.MAX);
        assertThat(t.stats().descending()).isFalse();
        assertThat(t.stats().rowOf(-5)).isEqualTo(-1);
        r.click(t, t.left() + 20, t.bottom() - 2);
    }

    @Test
    void theFlameGraphHoversAndShowsNothingForNoLane() {
        Rig r = new Rig();
        FlameGraphView f = r.g.profiler.flameGraph();
        float y = f.top() + f.rowPixels() * 1.5f;
        r.in.mouse(f.pointerX(f.left() + 10), f.pointerY(y), 0);
        r.run.frame();
        assertThat(f.hovered()).isPositive();
        r.in.mouse(f.pointerX(f.left() - 400), f.pointerY(y - 600), 0);
        r.run.frame();
        f.focus(2);
        r.run.settle();
        assertThat(f.nodeAt(f.left() + 5, f.top() + 3)).isZero();
        r.click(f, f.left() + 5, f.top() + 3); // the top row: out to the parent
        assertThat(f.focus()).isEqualTo(f.tree().parent(2));
        f.show(-1, 0, 0);
        assertThat(f.tree().count()).isEqualTo(1);
        f.focus(99);
        assertThat(f.focus()).isZero();
        assertThat(f.lane()).isEqualTo(-1);
    }

    @Test
    void countersGetAChartPerGroup() {
        Rig r = new Rig();
        r.g.profiler.tabs().select(2);
        r.run.settle();
        CounterView c = r.g.profiler.counters();
        assertThat(c.groups()).containsExactly("memory", "gpu.memory", "cpu", "gpu");
        assertThat(c.charts().get(0).series()).hasSize(2);
        assertThat(c.charts().get(0).y().title()).isEqualTo("bytes");
        int added = r.g.recording.counter("memory.native", "bytes");
        r.g.recording.sample(added, r.g.recording.endTime(), 1e6);
        r.run.settle();
        assertThat(c.charts().get(0).series()).hasSize(3);
    }

    @Test
    void anEmptyRecordingShowsNothingAndLiveFramesFillIt() {
        Ui ui = ProfilerViewTest.ui("default");
        Recording r = new Recording();
        ProfilerView view = new ProfilerView(new com.truesim.ui.controls.basic.Controls(ui), r);
        ui.root().add(view.element());
        ProfilerViewTest.Runner run = new ProfilerViewTest.Runner(ui, new SyntheticInput(), new UiInput(4));
        view.timeline().live(true);
        run.settle();
        assertThat(view.flameGraph().tree().count()).isEqualTo(1);
        int main = r.lane("main", LaneKind.THREAD);
        for (int f = 0; f < 10; f++) {
            r.zone(main, f * 16_000_000L, f * 16_000_000L + 5_000_000, r.name("frame"), 0);
            r.frame(f, f * 16_000_000L, f * 16_000_000L + 5_000_000, 0, false);
            r.commit();
            run.frame();
        }
        run.settle();
        assertThat(view.flameGraph().tree().count()).isEqualTo(2);
    }

    @Test
    void recordingEdges() {
        Recording r = new Recording(16, 16, 16);
        assertThat(r.startTime()).isEqualTo(Long.MAX_VALUE);
        r.retain(10);
        assertThat(r.nameOf(-1)).isEqualTo("?");
        int c = r.counter("a", "");
        assertThat(r.counter("a", "")).isEqualTo(c);
        assertThat(r.latestValue(c)).isNaN();
        for (int i = 0; i < 100; i++) {
            r.sample(c, i, i);
        }
        assertThat(r.endSample(c) - r.firstSample(c)).isEqualTo(16);
        assertThat(r.counterUnit(c)).isEmpty();
        r.frame(5, 10, 20, 1, false);
        r.frame(2, 10, 20, 1, false); // before the kept ones: starts over
        assertThat(r.firstFrame()).isEqualTo(2);
        r.gpuFrame(99, 1, 2); // not kept: ignored
        int lane = r.lane("x", LaneKind.MARKERS);
        r.zone(lane, 1, 2, r.name("m"), 0);
        assertThat(r.startTime()).isEqualTo(1);
        r.clear();
        assertThat(r.endZone(lane)).isEqualTo(r.firstZone(lane));
        assertThat(r.endTime()).isEqualTo(Long.MIN_VALUE);
        assertThat(LaneKind.of(2)).isEqualTo(LaneKind.MARKERS);
        assertThatThrownBy(() -> ProfileFile.read(new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5, 6, 7, 8})))
            .isInstanceOf(IOException.class);
        assertThat(Units.value(new StringBuilder(), 512, "bytes").toString()).isEqualTo("512 B");
        assertThat(Units.value(new StringBuilder(), 2048, "bytes").toString()).isEqualTo("2 KiB");
        assertThat(Units.value(new StringBuilder(), 3.0 * (1L << 30), "bytes").toString()).isEqualTo("3 GiB");
        assertThat(Units.value(new StringBuilder(), 12.5, "ms").toString()).isEqualTo("12.5 ms");
        assertThat(Units.value(new StringBuilder(), 4, "").toString()).isEqualTo("4");
        assertThat(Units.time(new StringBuilder(), 3_500_000_000L, 1e9).toString()).isEqualTo("4 s");
        assertThat(Units.time(new StringBuilder(), 3_922_350_000L, 5e4).toString()).isEqualTo("3922.35 ms");
        assertThat(Units.duration(new StringBuilder(), 250_000).toString()).isEqualTo("250 us");
        StringBuilder json = new StringBuilder();
        ChromeTrace.string(json, "a" + (char) 1 + (char) 13 + (char) 9 + (char) 92 + (char) 34 + "b");
        assertThat(json.toString()).hasSize(18).startsWith("" + (char) 34 + "a" + (char) 92 + "u0001");
        assertThat(TimelineView.niceStep(3)).isEqualTo(5);
        assertThat(FrameGraphView.nice(23)).isEqualTo(25);
    }
}
