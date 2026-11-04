package com.truesim.ui.controls.windows;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.input.Pointer;
import org.junit.jupiter.api.Test;

/**
 * Steady-state frames of docking into OS windows allocate nothing (without escape analysis): idle with windows open,
 * the mouse moving over the main window and the windows (mapped from the screen as the engine maps it), and a window
 * following a tab's drag over the dock spaces (drop previews showing and moving).
 */
class OsDockAllocationTest {

    private static final int WARMUP = 3_000;
    private static final int MEASURED = 1_000;
    private static final int ROUNDS = 8;

    private static OsDockTest.Rig withWindows() {
        OsDockTest.Rig r = new OsDockTest.Rig();
        r.tearOut(r.console, 1300, 500);
        r.tearOut(r.game, 2200, 100); // clear of the first window
        r.f.frames(OsDockHost.SAVE_AFTER + 2);
        return r;
    }

    @Test
    void idleFramesWithWindowsOpenAllocateNothing() {
        OsDockTest.Rig r = withWindows();
        AllocationAssertions.assertAllocationFree(() -> r.f.run(), WARMUP, MEASURED, ROUNDS);
    }

    @Test
    void theMouseMovingOverTheMainWindowAndTheOsWindowsAllocatesNothing() {
        OsDockTest.Rig r = withWindows();
        FakeOsWindows.Win a = r.os.win(r.host.window(r.dock.areaOf(r.console)).id());
        float[] xs = {300, 600, a.x + 30, a.x + 100, 900, a.x + 10};
        float[] ys = {300, 150, a.y + 15, a.y + 200, 400, a.y + 100};
        int[] step = {0};
        Runnable frame = () -> {
            int i = step[0]++;
            r.screen(xs[i % xs.length] + (i / xs.length) % 3, ys[i % ys.length], 0);
        };
        AllocationAssertions.assertAllocationFree(frame, WARMUP, MEASURED, ROUNDS);
    }

    @Test
    void aWindowFollowingATabDraggedOverDockSpacesAllocatesNothing() {
        OsDockTest.Rig r = withWindows();
        FakeOsWindows.Win a = r.os.win(r.host.window(r.dock.areaOf(r.console)).id());
        float[] from = r.onScreen(r.f.cx(r.console.tab()), r.f.cy(r.console.tab()));
        r.screen(from[0], from[1], Pointer.PRIMARY).screen(from[0] + 30, from[1] + 30, Pointer.PRIMARY);
        assertThat(r.host.followingWindow()).isNotNull();
        int ax = a.x;
        float[] path = {300, 500, 700, 900, 1250, 1500};
        int[] step = {0};
        Runnable frame = () -> {
            int i = step[0]++;
            float x = path[i % path.length] + (i / path.length) % 7;
            r.screen(x, 250 + (i % 5) * 20, Pointer.PRIMARY);
        };
        AllocationAssertions.assertAllocationFree(frame, WARMUP, MEASURED, ROUNDS);
        assertThat(a.x).isNotEqualTo(ax);
        r.release();
    }
}
