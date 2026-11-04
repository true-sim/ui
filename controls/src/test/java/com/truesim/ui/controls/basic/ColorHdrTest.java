package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.VersionedLong;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.input.PatternScreenSampler;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.ScreenSample;
import com.truesim.ui.core.input.UiCommand;
import org.junit.jupiter.api.Test;

/** The colour picker's HDR mode and the eyedropper over a pattern sampler: latency, picking, cancelling. */
class ColorHdrTest {

    @Test
    void anHdrColourIsABaseColourTimesAnIntensity() {
        Fixture f = new Fixture();
        VersionedLong model = f.c.clock().longValue(Rgba.pack(2, 1, 0.5f, 1));
        ColorPicker p = f.c.colorPicker("Glow", model).hdr(4);
        f.add(p.element()).settle();
        assertThat(p.hdr()).isTrue();
        assertThat(p.stops()).as("the brightest component is 2: one stop").isCloseTo(1, within(1e-4f));
        assertThat(p.hex().toString()).as("the hex is the base colour: (1, 0.5, 0.25) linear").isEqualTo("#FFBC89FF");
        assertThat(f.ui.text(p.intensityLabel()).toString()).isEqualTo("+1.0 EV");
        p.stops(3);
        assertThat(Rgba.r(model.get())).as("base 1 times 2^3").isCloseTo(8, within(0.01f));
        assertThat(Rgba.g(model.get())).isCloseTo(4, within(0.01f));
        f.run();
        assertThat(Rgba.r(f.ui.visuals().backgroundA(p.clippedPreview().slot()))).as("the clipped half").isEqualTo(1);
        assertThat(Rgba.r(f.ui.visuals().backgroundA(p.hdrPreview().slot()))).as("the HDR half").isEqualTo(8);
        assertThat(p.intensitySlider().value()).isCloseTo(3, within(1e-6));
        p.intensitySlider().set(0, 2);
        f.run();
        assertThat(p.stops()).isCloseTo(2, within(1e-6f));
        assertThat(Rgba.r(model.get())).isCloseTo(4, within(0.01f));
        assertThat(p.hex("#808080")).isTrue();
        assertThat(Rgba.r(model.get())).as("hex sets the base, the intensity stays")
            .isCloseTo(Rgba.toLinear(128 / 255f) * 4, within(0.01f));
        model.set(Rgba.pack(1000, 0, 0, 1));
        f.run();
        assertThat(p.stops()).as("clamped to the most stops").isEqualTo(4);
        p.hdr(0);
        assertThat(p.hdr()).isFalse();
        f.run();
        assertThat(f.ui.layout().shown(p.clippedPreview().slot())).isFalse();
        p.hdr(6);
        assertThat(p.intensitySlider().max()).isEqualTo(6);
    }

    @Test
    void theEyedropperPicksTheClickedPixelWhenItsAnswerArrives() {
        Fixture f = new Fixture();
        PatternScreenSampler sampler = new PatternScreenSampler((x, y) -> Rgba.pack(x / 100f, y / 100f, 0.25f, 1));
        f.c.screenSampler(sampler);
        VersionedLong model = f.c.clock().longValue(Rgba.pack(0, 0, 0, 0.5f));
        ColorPicker p = f.c.colorPicker("Tint", model).hdr(3);
        f.add(p.element()).settle();
        Button b = p.eyedropperButton();
        assertThat(b).isNotNull();
        assertThat(b.name()).isEqualTo("Pick from screen");
        f.click(b.element());
        Eyedropper e = p.eyedropperTool();
        assertThat(e.active()).isTrue();
        assertThat(b.element().is(UiState.CHECKED)).isTrue();
        assertThat(f.ui.layer("eyedropper").showing()).isTrue();
        f.mouse(150, 250, 0);
        f.run(3);
        ScreenSample s = e.sample();
        assertThat(s.size()).isEqualTo(11);
        assertThat(s.x()).isEqualTo(150);
        assertThat(Rgba.r(s.center())).isCloseTo(1.5f, within(0.01f));
        assertThat(f.ui.layout().x(e.loupe().slot())).as("the magnifier beside the pointer").isGreaterThan(150);
        f.mouse(300, 50, Pointer.PRIMARY); // a click where no answer is yet: picked when it arrives
        assertThat(e.active()).isTrue();
        f.mouse(300, 50, 0);
        f.run(3);
        assertThat(e.active()).isFalse();
        assertThat(Rgba.r(model.get())).as("HDR kept").isCloseTo(3, within(0.02f));
        assertThat(Rgba.g(model.get())).isCloseTo(0.5f, within(0.01f));
        assertThat(Rgba.a(model.get())).as("the alpha stays").isEqualTo(0.5f);
        assertThat(b.element().is(UiState.CHECKED)).isFalse();
        assertThat(f.ui.layer("eyedropper").showing()).isFalse();
        assertThat(f.ui.interaction().focused(0)).as("focus back").isEqualTo(b.element());

        p.hdr(0);
        f.run();
        f.click(b.element());
        assertThat(e.active()).isTrue();
        f.mouse(300, 50, 0);
        f.run(4);
        f.mouse(300, 50, Pointer.PRIMARY); // the answer is for this point already: picked at once
        assertThat(e.active()).isFalse();
        assertThat(Rgba.r(model.get())).as("clipped without HDR").isEqualTo(1);
        f.mouse(300, 50, 0);

        long before = model.get();
        f.click(b.element());
        f.run(2);
        f.press(UiCommand.CANCEL);
        assertThat(e.active()).isFalse();
        f.click(b.element());
        f.mouse(20, 20, 0);
        f.mouse(20, 20, Pointer.SECONDARY);
        assertThat(e.active()).as("the secondary button cancels").isFalse();
        f.mouse(20, 20, 0);
        f.click(b.element());
        f.mouse(40, 40, 0);
        f.run(4);
        f.press(UiCommand.ACCEPT);
        f.run(3);
        assertThat(e.active()).isFalse();
        assertThat(model.get()).as("ACCEPT picks where the pointer is").isNotEqualTo(before);
        assertThat(sampler.requests()).isGreaterThan(10);
        assertThat(e.catcher()).isNotNull();
        assertThat(f.ui.layerStack().find("eyedropper").z()).isBetween(400, 500);
        assertThat(Layers.DRAG).isNotEqualTo("eyedropper");
    }

    @Test
    void withoutASamplerThereIsNoEyedropper() {
        Fixture f = new Fixture();
        ColorPicker p = f.c.colorPicker("Tint", f.c.clock().longValue(Rgba.WHITE));
        assertThat(p.eyedropperButton()).isNull();
        Eyedropper e = new Eyedropper(f.c, c -> { });
        assertThat(e.start(0)).isFalse();
        PatternScreenSampler unavailable = new PatternScreenSampler().available(false).latency(0);
        f.c.screenSampler(unavailable);
        p.eyedropper();
        assertThat(p.eyedropperButton()).isNotNull();
        assertThat(PatternScreenSampler.card(0, 1000)).as("the card's bottom is 4x paper white")
            .isEqualTo(Rgba.pack(4, 0, 0, 1));
    }
}
