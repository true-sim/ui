package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.text.TextEditor;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.testkit.SyntheticInput;
import com.truesim.ui.text.spell.Dictionary;
import com.truesim.ui.text.spell.SpellChecker;
import org.junit.jupiter.api.Test;

/**
 * Steady frames of group A3's additions allocate nothing (without escape analysis), each over the whole gallery and a
 * spell-checked text area: the mnemonic modifier going down and up, a held repeat button repeating, a logarithmic
 * slider stepped by keys, a spell-checked area scrolling with its squiggles, and the eyedropper following the pointer
 * with its sampler answering.
 */
class A3AllocationTest {

    /** What a frame does with the input. */
    @FunctionalInterface
    private interface Input {
        void fill(Fixture f, Pointer mouse, int phase);
    }

    private static final class Rig {
        final Fixture f = new Fixture(Fixture.DEFAULT, Fixture.nativeText());
        final ControlsGallery.Gallery g = ControlsGallery.build(f.c, f.ui.root());
        final TextEditor area = TextEditor.area(f.ui, null, "Notes")
            .spelling(new SpellChecker(Dictionary.sampleEnglish()), f.c);
        int step;

        Rig() {
            f.ui.styles().inline(area.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(300))
                .set(Styles.HEIGHT, Length.px(120)).build());
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 400; i++) {
                text.append(i % 7 == 0 ? "a mispelled wrod here and there\n" : "all is fine in this line\n");
            }
            area.text(text);
            f.ui.layer(com.truesim.ui.core.Layers.OVERLAY).root().add(area.element());
            f.ui.visuals().offsets(area.element().slot(), 900, 640, 0, 0);
            f.settle();
        }

        Runnable frame(Input input) {
            return () -> {
                int phase = step++ % 40;
                f.input.beginFrame();
                input.fill(f, f.input.pointer(SyntheticInput.MOUSE, Pointer.Kind.MOUSE), phase);
                f.ui.update(f.width, f.height, f.input, f.now);
                f.now += 16_000_000L;
            };
        }

        void check(Runnable frame) {
            for (int i = 0; i < 2_000; i++) {
                frame.run(); // every state and style variant seen once
            }
            AllocationAssertions.assertAllocationFree(frame);
        }
    }

    @Test
    void mnemonicsRepeatAndScaledSliders() {
        Rig r = new Rig();
        r.check(r.frame((f, mouse, phase) -> f.input.mnemonicsShown(phase < 20)));
        float rx = r.f.cx(r.g.repeat.element());
        float ry = r.f.cy(r.g.repeat.element());
        r.check(r.frame((f, mouse, phase) -> mouse.moveTo(rx, ry).buttons(phase < 38 ? Pointer.PRIMARY : 0)));
        assertThat(r.g.repeat.repeater().repeats()).isPositive();
        r.f.ui.interaction().focus(r.g.logarithmic.element());
        r.check(r.frame((f, mouse, phase) -> f.input.command(phase % 2 == 0 ? UiCommand.RIGHT : UiCommand.LEFT, 0,
            0)));
    }

    @Test
    void aSpellCheckedAreaScrolling() {
        Rig r = new Rig();
        float ax = r.f.cx(r.area.element());
        float ay = r.f.cy(r.area.element());
        r.check(r.frame((f, mouse, phase) -> mouse.moveTo(ax, ay).buttons(0).scroll(0, phase < 20 ? -1 : 1)));
        assertThat(r.area.spellPending()).isFalse();
        assertThat(r.area.misspelt()).hasSize(2 * 58);
    }

    @Test
    void theEyedropperFollowingThePointer() {
        Rig r = new Rig();
        r.g.color.eyedropperTool().start(0);
        r.check(r.frame((f, mouse, phase) -> mouse.moveTo(200 + phase * 7, 150 + (phase & 7)).buttons(0)));
        assertThat(r.g.color.eyedropperTool().active()).isTrue();
        assertThat(r.g.color.eyedropperTool().sample().size()).isEqualTo(11);
    }
}
