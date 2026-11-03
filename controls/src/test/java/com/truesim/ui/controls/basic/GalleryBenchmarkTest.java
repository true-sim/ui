package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.testkit.SyntheticInput;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * CPU cost of the control gallery's frames (all basic and container controls, about 520 elements, through the real
 * text path): idle with its spinner and sweeping progress bar animating, and the pointer moving over a control every
 * frame (hover styles and transitions). Runs with {@code TRUESIM_BENCHMARK=true} (JaCoCo off); prints median and p99.
 */
class GalleryBenchmarkTest {

    private static double[] measure(Runnable frame, int n) {
        double[] us = new double[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            frame.run();
            us[i] = (System.nanoTime() - t0) / 1000.0;
        }
        Arrays.sort(us);
        return new double[] {us[n / 2], us[(int) (n * 0.99)]};
    }

    @Test
    void galleryFrames() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")), "benchmark");
        Fixture f = new Fixture(Fixture.DEFAULT, Fixture.nativeText());
        ControlsGallery.Gallery g = ControlsGallery.build(f.c, f.ui.root());
        f.settle();
        Element[] targets = {g.button.element(), g.checked.element(), g.slider.element(), g.card.element(),
            g.tabs.tab(1).button().element(), g.date.cell(12), g.menuBar.title(1).element(), g.toggle.element()};
        Runnable idle = () -> {
            f.input.beginFrame();
            f.ui.update(f.width, f.height, f.input, f.now);
            f.now += 16_000_000L;
        };
        int[] step = {0};
        Runnable hover = () -> {
            Element t = targets[step[0]++ % targets.length];
            f.input.beginFrame();
            f.input.pointer(SyntheticInput.MOUSE, Pointer.Kind.MOUSE).moveTo(f.cx(t), f.cy(t)).buttons(0);
            f.ui.update(f.width, f.height, f.input, f.now);
            f.now += 16_000_000L;
        };
        for (int i = 0; i < 20_000; i++) {
            idle.run();
            hover.run();
        }
        double[] a = measure(idle, 20_000);
        double[] b = measure(hover, 20_000);
        System.out.printf("Gallery: %d elements; idle frame %.1f us median, %.1f us p99; hover frame %.1f us median,"
            + " %.1f us p99%n", f.ui.store().count(), a[0], a[1], b[0], b[1]);
        assertThat(f.ui.store().count()).isGreaterThan(500);
    }
}
