package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.VersionedDouble;
import com.truesim.ui.controls.text.NumberField;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import org.junit.jupiter.api.Test;

/** Repeat buttons and the steppers built on them; slider scales: mapping, round trips, ticks, keys, drags. */
class RepeatAndScaleTest {

    // ---------------------------------------------------------------- repeating

    @Test
    void aHeldRepeatButtonRepeatsAfterTheDelayAtTheRateAndPausesOffIt() {
        Fixture f = new Fixture();
        int[] runs = {0};
        RepeatButton b = f.c.repeatButton("More");
        b.onClick(() -> runs[0]++);
        f.add(b.element()).settle();
        float x = f.cx(b.element());
        float y = f.cy(b.element());
        f.mouse(x, y, Pointer.PRIMARY);
        assertThat(runs[0]).as("the press runs it at once").isEqualTo(1);
        assertThat(b.repeater().held()).isTrue();
        f.advance(Repeater.DELAY.get() - 0.05f);
        assertThat(runs[0]).as("nothing more before the delay").isEqualTo(1);
        f.advance(0.55f); // ~0.5 s of repeats at 20 a second
        int repeats = runs[0] - 1;
        assertThat(repeats).isBetween(8, 12);
        f.mouse(x + 400, y + 400, Pointer.PRIMARY);
        int off = runs[0];
        f.advance(0.3f);
        assertThat(runs[0]).as("paused off the button").isEqualTo(off);
        f.mouse(x, y, Pointer.PRIMARY);
        f.advance(0.2f);
        assertThat(runs[0]).as("and goes on back on it").isGreaterThan(off + 1);
        f.mouse(x, y, 0);
        int released = runs[0];
        f.advance(0.5f);
        assertThat(runs[0]).as("the release stops it, and its click adds nothing").isEqualTo(released);
        assertThat(b.repeater().held()).isFalse();
        f.focus(b.element());
        f.press(UiCommand.ACCEPT);
        assertThat(runs[0]).as("ACCEPT runs it once").isEqualTo(released + 1);
        assertThat(b.clicks()).isEqualTo(runs[0]);
        assertThat(Repeater.tunables()).containsExactly(Repeater.DELAY, Repeater.RATE);
    }

    @Test
    void numberFieldSteppersAndPickerArrowsRepeat() {
        Fixture f = new Fixture();
        NumberField n = NumberField.create(f.ui, null, "Count", "Less", "More").range(0, 1000);
        f.add(n.element()).settle();
        f.mouse(f.cx(n.stepUp()), f.cy(n.stepUp()), Pointer.PRIMARY);
        f.advance(1);
        f.mouse(f.cx(n.stepUp()), f.cy(n.stepUp()), 0);
        assertThat(n.value()).as("held for a second").isBetween(10.0, 16.0);
        double held = n.value();
        f.click(n.stepDown());
        assertThat(n.value()).as("a click steps once").isEqualTo(held - 1);
        TimePicker t = f.c.timePicker("Time", f.c.clock().intValue(0));
        f.add(t.element()).settle();
        assertThat(t.upButton(0)).isInstanceOf(RepeatButton.class);
    }

    // ---------------------------------------------------------------- scales

    @Test
    void scalesMapAndRoundTrip() {
        SliderScale log = SliderScale.logarithmic(10);
        assertThat(log.fraction(20, 20, 20_000)).isZero();
        assertThat(log.fraction(20_000, 20, 20_000)).isEqualTo(1);
        assertThat(log.fraction(632.4555, 20, 20_000)).isCloseTo(0.5, within(1e-6)); // the geometric middle
        assertThat(log.value(0.5, 20, 20_000)).isCloseTo(632.4555, within(1e-3));
        SliderScale sym = SliderScale.symlog(1, 10);
        assertThat(sym.fraction(0, -1000, 1000)).isCloseTo(0.5, within(1e-9));
        assertThat(sym.forward(-9)).isCloseTo(-1, within(1e-12));
        for (SliderScale s : new SliderScale[] {SliderScale.LINEAR, log, SliderScale.logarithmic(2), sym,
            SliderScale.symlog(0.01, 2)}) {
            double min = s == SliderScale.LINEAR || s.toString().startsWith("symlog") ? -500 : 0.001;
            double max = 1e5;
            for (int i = 0; i <= 100; i++) {
                double f = i / 100.0;
                double v = s.value(f, min, max);
                assertThat(s.fraction(v, min, max)).as(s + " at " + f).isCloseTo(f, within(1e-9));
                assertThat(s.inverse(s.forward(v))).isCloseTo(v, within(Math.abs(v) * 1e-9 + 1e-9));
            }
            assertThat(s.fraction(min - 1, min, max)).as("clamped").isZero();
            assertThat(s.fraction(max * 2, min, max)).isEqualTo(1);
        }
        assertThatThrownBy(() -> log.check(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SliderScale.logarithmic(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SliderScale.symlog(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SliderScale.LINEAR.check(1, 1)).isInstanceOf(IllegalArgumentException.class);
        double[] ticks = new double[16];
        assertThat(log.ticks(20, 20_000, ticks)).isEqualTo(3);
        assertThat(ticks[0]).isCloseTo(100, within(1e-9));
        assertThat(ticks[1]).isCloseTo(1000, within(1e-9));
        assertThat(ticks[2]).isCloseTo(10_000, within(1e-9));
        int n = sym.ticks(-100, 1000, ticks);
        double[] expected = {-100, -10, -1, 0, 1, 10, 100, 1000};
        assertThat(n).isEqualTo(expected.length);
        for (int i = 0; i < n; i++) {
            assertThat(ticks[i]).isCloseTo(expected[i], within(1e-9));
        }
        assertThat(SliderScale.LINEAR.ticks(0, 1, ticks)).isZero();
        assertThat(SliderScale.LINEAR.linear()).isTrue();
        assertThat(log.linear()).isFalse();
    }

    @Test
    void aLogarithmicSliderPlacesThumbTicksAndStepsOnItsScale() {
        Fixture f = new Fixture();
        VersionedDouble hz = f.c.clock().doubleValue(632.4555);
        Slider s = f.c.slider("Frequency", hz, 20, 20_000).scale(SliderScale.logarithmic(10)).scaleTicks();
        f.add(s.element()).settle();
        assertThat(s.ticks()).isEqualTo(3);
        float tx = f.ui.layout().x(s.track().slot());
        float tw = f.ui.layout().width(s.track().slot());
        assertThat((f.cx(s.thumb(0)) - tx) / tw).as("the middle of the track").isCloseTo(0.5f, within(0.02f));
        assertThat((f.cx(s.tick(1)) - tx) / tw).as("1 kHz").isCloseTo((float) (Math.log10(50) / 3), within(0.02f));
        f.focus(s.element());
        double before = s.value();
        f.press(UiCommand.RIGHT);
        assertThat(s.value() / before).as("a key step multiplies").isCloseTo(Math.pow(1000, 0.01), within(1e-6));
        f.press(UiCommand.PAGE_DOWN);
        assertThat(s.value()).isCloseTo(before * Math.pow(1000, 0.01 - Controls.SLIDER_PAGE.get()), within(1e-6));
        f.press(UiCommand.HOME);
        assertThat(s.value()).isEqualTo(20);
        f.mouse(tx + tw * 0.5f, f.cy(s.track()), Pointer.PRIMARY);
        assertThat(s.value()).as("a press in the middle").isCloseTo(632.4555, within(632.0 * 0.02));
        f.mouse(tx + tw * 0.5f, f.cy(s.track()), 0);
        s.step(100);
        f.focus(s.element());
        s.set(0, 20);
        f.press(UiCommand.RIGHT);
        assertThat(s.value()).as("snapped steps still move at least one step").isEqualTo(120);
        Slider ticksAt = f.c.slider("Gain", f.c.clock().doubleValue(1), 0.01, 100)
            .scale(SliderScale.logarithmic(10)).ticksAt(0.001, 0.1, 1, 10, 1000);
        assertThat(ticksAt.ticks()).as("out of range ones left out").isEqualTo(3);
        Slider linear = f.c.slider("Linear", f.c.clock().doubleValue(0), 0, 1).step(0.25).scaleTicks();
        assertThat(linear.ticks()).isEqualTo(5);
        assertThat(f.c.slider("Plain", f.c.clock().doubleValue(0), 0, 1).scaleTicks().ticks()).isEqualTo(11);
        Slider pan = f.c.slider("Offset", f.c.clock().doubleValue(0), -1000, 1000).scale(SliderScale.symlog(1, 10));
        f.add(pan.element()).settle();
        assertThat((f.cx(pan.thumb(0)) - f.ui.layout().x(pan.track().slot()))
            / f.ui.layout().width(pan.track().slot())).isCloseTo(0.5f, within(0.02f));
        pan.range(-10, 10);
        assertThat(pan.min()).isEqualTo(-10);
        assertThat(pan.scale().toString()).startsWith("symlog");
        assertThatThrownBy(() -> f.c.slider("Bad", f.c.clock().doubleValue(1), 0, 10)
            .scale(SliderScale.logarithmic(10))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNumberFieldDragsAndStepsOnItsScale() {
        Fixture f = new Fixture();
        NumberField n = NumberField.create(f.ui, null, "Exposure", "Less", "More").range(0.01, 100).decimals(4)
            .value(1).scale(SliderScale.logarithmic(10));
        f.add(n.element()).settle();
        f.focus(n.editor().element());
        f.press(UiCommand.UP);
        assertThat(n.value()).as("a hundredth of four decades: x1.096").isCloseTo(Math.pow(10, 0.04), within(1e-3));
        float hx = f.cx(n.handle());
        float hy = f.cy(n.handle());
        n.value(1);
        f.mouse(hx, hy, 0);
        f.mouse(hx, hy, Pointer.PRIMARY);
        f.mouse(hx + NumberField.DRAG_PIXELS.get() * 50, hy, Pointer.PRIMARY);
        f.mouse(hx + NumberField.DRAG_PIXELS.get() * 50, hy, 0);
        assertThat(n.value()).as("50 drag steps of 1/200 of 4 decades: one decade")
            .isCloseTo(10, within(0.01));
        assertThat(n.scale().linear()).isFalse();
        assertThatThrownBy(() -> NumberField.create(f.ui, null, "x", "a", "b").scale(SliderScale.logarithmic(10)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
