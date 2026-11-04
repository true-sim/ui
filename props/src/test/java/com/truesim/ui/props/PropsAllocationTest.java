package com.truesim.ui.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.testkit.SyntheticInput;
import org.junit.jupiter.api.Test;

/**
 * A generated panel's steady-state frames allocate nothing (without escape analysis): idle, and the pointer moving
 * over every row (hover styles), with all bindings, nested panels and collection editors polled each frame.
 */
class PropsAllocationTest {

    @Test
    void steadyFramesAllocateNothing() {
        Rig r = new Rig(Rig.DEFAULT, Rig.nativeText());
        PropsGallery g = PropsGallery.build(r.c, r.ui.root());
        r.settle();
        int n = g.panel.rows().size();
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            Element e = g.panel.rows().get(i).element();
            xs[i] = r.cx(e);
            ys[i] = r.cy(e);
        }
        int[] step = {0};
        Runnable frame = () -> {
            int s = step[0]++;
            r.input.beginFrame();
            Pointer mouse = r.input.pointer(SyntheticInput.MOUSE, Pointer.Kind.MOUSE);
            mouse.moveTo(xs[s % n] + (s & 3), ys[s % n]).buttons(0).scroll(0, 0);
            r.ui.update(r.width, r.height, r.input, r.now);
            r.now += 16_000_000L;
        };
        for (int i = 0; i < 3_000; i++) {
            frame.run(); // every hover state seen once
        }
        AllocationAssertions.assertAllocationFree(frame);
        AllocationAssertions.assertAllocationFree(() -> {
            r.ui.update(r.width, r.height, r.input, r.now);
            r.now += 16_000_000L;
        });
        assertThat(r.ui.bindings().size()).isGreaterThan(20);
    }
}
