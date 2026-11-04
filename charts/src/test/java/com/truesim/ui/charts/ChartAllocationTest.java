package com.truesim.ui.charts;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedDoubleRing;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.testkit.SyntheticInput;
import org.junit.jupiter.api.Test;

/**
 * Steady-state chart frames allocate nothing (without escape analysis, the real text path): streaming 1,000 samples a
 * frame into a ring the X axis follows (min-max pyramid appended, envelope drawn, ticks and labels moving), the pointer
 * hovering (crosshair, highlights, the merged tooltip's text changing every frame), the wheel zooming, a pan by drag,
 * and the whole demo page idle and hovered.
 */
class ChartAllocationTest {

    @Test
    void streamingHoveringZoomingAndPanningAllocateNothing() {
        Rig rig = new Rig(Rig.DEFAULT, Rig.nativeText());
        rig.width = 1600;
        rig.height = 600;
        ChangeClock clock = rig.ui.store().clock();
        VersionedDoubleRing ring = clock.doubleRing(200_000);
        Chart chart = rig.chart("stream");
        chart.line("signal", Columns.index(0, 0.001), Columns.of(ring));
        chart.scatter("picked", Columns.index(0, 0.001), Columns.of(ring)).decimation(Decimation.LTTB);
        chart.x().range(0, 20).fits(false).follow(true);
        chart.y().autoFit(true);
        double[] chunk = new double[1000];
        int[] step = {0};
        Runnable frame = () -> {
            int s = step[0]++;
            long base = ring.appended();
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = Math.sin((base + i) * 0.003) * (2 + (s % 50) * 0.01);
            }
            ring.append(chunk, 0, chunk.length);
            int phase = s % 40;
            rig.input.beginFrame();
            Pointer mouse = rig.input.pointer(SyntheticInput.MOUSE, Pointer.Kind.MOUSE);
            float cx = chart.contentX() + (chart.plotLeft() + chart.plotRight()) * 0.5f;
            float cy = chart.contentY() + (chart.plotTop() + chart.plotBottom()) * 0.5f;
            if (phase < 20) {
                mouse.moveTo(cx + (s % 7) * 9, cy + (s % 3) * 4).buttons(0).scroll(0, 0);
            } else if (phase < 25) {
                mouse.moveTo(cx, cy).buttons(0).scroll(0, phase % 2 == 0 ? 1 : -1);
            } else if (phase < 35) {
                mouse.moveTo(cx + (phase - 25) * 6, cy).buttons(Pointer.PRIMARY).scroll(0, 0);
            } else {
                mouse.moveTo(cx, cy).buttons(0).scroll(0, 0);
                if (phase == 39) {
                    chart.fitAll(); // and follow again
                }
            }
            rig.ui.update(rig.width, rig.height, rig.input, rig.now);
            rig.now += 16_000_000L;
        };
        for (int i = 0; i < 1500; i++) {
            frame.run(); // the ring wraps, every label and look seen once
        }
        AllocationAssertions.assertAllocationFree(frame);
    }

    @Test
    void theDemoPageIdleAndHoveredAllocatesNothing() {
        Rig rig = new Rig(Rig.DEFAULT, Rig.nativeText());
        rig.width = ChartsGallery.WIDTH;
        rig.height = ChartsGallery.HEIGHT;
        ChartsGallery g = ChartsGallery.build(rig.ui);
        rig.settle();
        Chart[] targets = g.charts().toArray(new Chart[0]);
        int[] step = {0};
        Runnable frame = () -> {
            int s = step[0]++;
            Chart c = targets[s / 8 % targets.length];
            rig.input.beginFrame();
            rig.input.pointer(SyntheticInput.MOUSE, Pointer.Kind.MOUSE).moveTo(
                c.contentX() + c.plotLeft() + (c.plotRight() - c.plotLeft()) * (s % 8) / 8f,
                c.contentY() + (c.plotTop() + c.plotBottom()) * 0.5f).buttons(0).scroll(0, 0);
            rig.ui.update(rig.width, rig.height, rig.input, rig.now);
            rig.now += 16_000_000L;
        };
        for (int i = 0; i < 2000; i++) {
            frame.run();
        }
        AllocationAssertions.assertAllocationFree(frame);
        AllocationAssertions.assertAllocationFree(() -> {
            rig.ui.update(rig.width, rig.height, rig.input, rig.now);
            rig.now += 16_000_000L;
        });
    }
}
