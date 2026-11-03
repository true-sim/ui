package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.testkit.SyntheticInput;
import org.junit.jupiter.api.Test;

/**
 * Steady-state frames of the whole gallery allocate nothing (without escape analysis): idle (spinner and sweeping
 * progress animating), the pointer moving over every control (hover styles and their transitions), dragging a
 * slider and the colour picker, wheel and arrow keys on a slider, and a model changing every frame.
 */
class ControlsAllocationTest {

    @Test
    void galleryFramesAllocateNothing() {
        Fixture f = new Fixture(Fixture.DEFAULT, Fixture.nativeText());
        ControlsGallery.Gallery g = ControlsGallery.build(f.c, f.ui.root());
        f.settle();
        Element[] targets = {g.button.element(), g.toggle.element(), g.checked.element(), g.radios.option(0).element(),
            g.onSwitch.element(), g.segmented.option(2).element(), g.slider.element(), g.color.area(),
            g.date.cell(10), g.time.field(0), g.binding.element(), g.dropDown.element(), g.menuBar.title(0).element(),
            g.toolbar.element(), g.crumbs.crumb(0).element(), g.tabs.tab(1).button().element(), g.card.element(),
            g.split.splitter(), g.carousel.element(), g.stepper.stepButton(0).element()};
        float[] xs = new float[targets.length];
        float[] ys = new float[targets.length];
        for (int i = 0; i < targets.length; i++) {
            xs[i] = f.cx(targets[i]);
            ys[i] = f.cy(targets[i]);
        }
        float sx = f.cx(g.slider.track());
        float sy = f.cy(g.slider.track());
        float sw = f.ui.layout().width(g.slider.track().slot());
        float ax = f.cx(g.color.area());
        float ay = f.cy(g.color.area());
        f.ui.interaction().focus(g.slider.element());
        int[] step = {0};
        Runnable frame = () -> {
            int s = step[0]++;
            int phase = s % 64;
            f.input.beginFrame();
            Pointer mouse = f.input.pointer(SyntheticInput.MOUSE, Pointer.Kind.MOUSE);
            if (phase < 40) {
                int t = s % targets.length;
                mouse.moveTo(xs[t] + (s & 3), ys[t]).buttons(0).scroll(0, 0);
            } else if (phase < 50) {
                mouse.moveTo(sx - sw / 2 + (phase - 40) * sw / 10, sy).buttons(Pointer.PRIMARY);
            } else if (phase < 60) {
                mouse.moveTo(ax + (phase - 50) * 3, ay - (phase - 50) * 2).buttons(phase < 59 ? Pointer.PRIMARY : 0);
            } else {
                mouse.moveTo(sx, sy).buttons(0).scroll(0, phase % 2 == 0 ? 1 : -1);
                f.input.command(phase % 2 == 0 ? UiCommand.LEFT : UiCommand.RIGHT, 0, 0);
            }
            g.progress.model().set((s % 100) / 100.0);
            f.ui.update(f.width, f.height, f.input, f.now);
            f.now += 16_000_000L;
        };
        for (int i = 0; i < 4_000; i++) {
            frame.run(); // every state and style variant seen once
        }
        AllocationAssertions.assertAllocationFree(frame);
        AllocationAssertions.assertAllocationFree(() -> {
            f.ui.update(f.width, f.height, f.input, f.now);
            f.now += 16_000_000L;
        });
        assertThat(g.slider.value()).isBetween(0.0, 1.0);
    }
}
