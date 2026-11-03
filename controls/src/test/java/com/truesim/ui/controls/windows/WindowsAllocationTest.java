package com.truesim.ui.controls.windows;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.Pointer;
import org.junit.jupiter.api.Test;

/**
 * Steady-state frames with overlays, windows and docking allocate nothing (without escape analysis): idle, the pointer
 * moving over window chrome, tabs and splitters (hover states, cursors, tooltip timers, hover intent), dragging a
 * window (snapping), a splitter, and a tab across panes (drop targets, the preview and zone guides showing and
 * moving).
 */
class WindowsAllocationTest {

    private static final int WARMUP = 4_000;
    private static final int MEASURED = 1_000;
    private static final int ROUNDS = 6;

    private static WindowsScene released() {
        WindowsScene s = WindowsScene.build(new Ui(Fx.theme(), Fx.nativeText()), 1280, 720);
        s.mouse(1270, 710, 0); // drops the dragged tab on nothing that takes it: it floats
        s.frames(3);
        return s;
    }

    private static float cx(WindowsScene s, Element e) {
        return s.ui.layout().x(e.slot()) + s.ui.layout().width(e.slot()) / 2;
    }

    private static float cy(WindowsScene s, Element e) {
        return s.ui.layout().y(e.slot()) + s.ui.layout().height(e.slot()) / 2;
    }

    @Test
    void idleFramesWithWindowsATooltipAPopoverAndADockSpaceAllocateNothing() {
        WindowsScene s = released();
        s.popover.open();
        s.tooltips.show(s.ui.store().element(s.ui.store().firstChild(s.toolbar.slot())));
        s.frames(2);
        assertThat(s.popover.isOpen()).isTrue();
        AllocationAssertions.assertAllocationFree(() -> s.frames(1), WARMUP, MEASURED, ROUNDS);
    }

    @Test
    void thePointerMovingOverWindowChromeTabsSplittersAndTooltipTargetsAllocatesNothing() {
        WindowsScene s = released();
        FloatingWindow w = s.inspectorWindow;
        DockPanel scene = s.dock.find("scene");
        Element[] targets = {w.titleBar(), w.closeButton(), w.maximiseButton(), w.minimiseButton(), w.handle("e"),
            w.handle("se"), w.handle("n"), s.statsWindow.titleBar(), scene.tab(), scene.closeButton(),
            s.dock.find("hierarchy").tab(), s.dock.splittersBeside(s.dock.find("hierarchy"))[0],
            s.ui.store().element(s.ui.store().firstChild(s.toolbar.slot())), s.dock.root(), w.content()};
        float[] xs = new float[targets.length];
        float[] ys = new float[targets.length];
        for (int i = 0; i < targets.length; i++) {
            xs[i] = cx(s, targets[i]);
            ys[i] = cy(s, targets[i]);
        }
        int[] step = {0};
        Runnable frame = () -> {
            int i = step[0]++;
            int t = i % targets.length;
            // most frames move a little within a target; every few frames the pointer rests (tooltip and hover timers)
            float jitter = (i / targets.length) % 4 == 0 ? 0 : (i % 3) - 1;
            s.mouse(xs[t] + jitter, ys[t], 0);
        };
        AllocationAssertions.assertAllocationFree(frame, WARMUP, MEASURED, ROUNDS);
    }

    @Test
    void draggingAWindowAroundSnappingToEdgesAndWindowsAllocatesNothing() {
        WindowsScene s = released();
        FloatingWindow w = s.statsWindow;
        float x0 = s.ui.layout().x(w.titleBar().slot()) + 20;
        float y0 = cy(s, w.titleBar());
        s.mouse(x0, y0, Pointer.PRIMARY).mouse(x0 + 20, y0 + 10, Pointer.PRIMARY);
        int[] step = {0};
        Runnable frame = () -> {
            int i = step[0]++ % 120;
            float t = i < 60 ? i : 120 - i;
            s.mouse(x0 + t * 9 - 200, y0 + t * 4 - 100, Pointer.PRIMARY);
        };
        AllocationAssertions.assertAllocationFree(frame, WARMUP, MEASURED, ROUNDS);
        assertThat(w.x()).isNotEqualTo(420);
    }

    @Test
    void draggingASplitterAllocatesNothing() {
        WindowsScene s = released();
        Element splitter = s.dock.splittersBeside(s.dock.find("hierarchy"))[0];
        float x0 = cx(s, splitter);
        float y0 = cy(s, splitter);
        s.mouse(x0, y0, Pointer.PRIMARY).mouse(x0 + 10, y0, Pointer.PRIMARY);
        int[] step = {0};
        Runnable frame = () -> {
            int i = step[0]++ % 80;
            float t = i < 40 ? i : 80 - i;
            s.mouse(x0 + t * 6 - 100, y0, Pointer.PRIMARY);
        };
        AllocationAssertions.assertAllocationFree(frame, WARMUP, MEASURED, ROUNDS);
    }

    @Test
    void draggingATabAcrossPanesAndTheirZonesAllocatesNothing() {
        WindowsScene s = released();
        DockPanel hierarchy = s.dock.find("hierarchy");
        float x0 = cx(s, hierarchy.tabLabel);
        float y0 = cy(s, hierarchy.tabLabel);
        s.mouse(x0, y0, Pointer.PRIMARY).mouse(x0 + 20, y0 + 20, Pointer.PRIMARY);
        int[] step = {0};
        Runnable frame = () -> {
            int i = step[0]++ % 100;
            float t = i < 50 ? i : 100 - i;
            s.mouse(40 + t * 24, 160 + t * 11, Pointer.PRIMARY); // across panes, their edges and centres
        };
        AllocationAssertions.assertAllocationFree(frame, WARMUP, MEASURED, ROUNDS);
        assertThat(s.dock.previewShowing()).isTrue();
    }
}
