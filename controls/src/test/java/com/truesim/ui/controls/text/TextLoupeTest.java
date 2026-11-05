package com.truesim.ui.controls.text;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.ui.core.Layers;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.testkit.LayoutSanity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The loupe over a dragged touch selection handle: shown while dragging, above the line, the text magnified. */
class TextLoupeTest {

    private static final int FINGER = 3;

    /** A field at {@code top} with "two" selected by a long press, its handles shown. */
    private static TextEditor selected(TextRig r, float top) {
        TextEditor f = TextEditor.field(r.ui, null, "Loupe");
        r.ui.styles().inline(f.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(360))
            .set(Styles.MARGIN, Insets.of(top, 0, 0, 0)).build());
        r.add(f.element()).frame();
        f.text("one two three four five");
        r.settle();
        float[] b = r.box(f.element());
        float y = (b[1] + b[3]) / 2;
        float two = b[0] + (b[2] - b[0]) * 0.18f; // on "two"
        r.synthetic.pointer(FINGER, Pointer.Kind.TOUCH, two, y, Pointer.PRIMARY);
        r.frame();
        r.later(800_000_000L).frame(); // a long press selects the word
        r.synthetic.pointer(FINGER, Pointer.Kind.TOUCH, two, y, 0);
        r.frame();
        assertThat(f.touchHandles()).isTrue();
        assertThat(f.loupeShown()).as("no loupe until a handle is dragged").isFalse();
        return f;
    }

    private static TextRig rig(String theme) {
        return new TextRig(theme.equals("default") ? Tss.defaultTheme() : Tss.testTheme());
    }

    /** The tallest visible glyph quad in {@code list}, as drawn. */
    private static float tallestGlyph(DrawList list) {
        float tallest = 0;
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.GLYPH) {
                float h = list.drawnY(i, Primitive.Y1) - list.drawnY(i, Primitive.Y0);
                tallest = Math.max(tallest, h);
            }
        }
        return tallest;
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void draggingAHandleShowsTheTextMagnifiedAboveTheLine(String theme) {
        TextRig r = rig(theme);
        TextEditor f = selected(r, 200);
        float hx = f.handle(2);
        float hy = f.handle(3);
        r.synthetic.pointer(FINGER, Pointer.Kind.TOUCH, hx, hy, Pointer.PRIMARY);
        r.frame();
        assertThat(f.loupeShown()).isTrue();
        r.settle();
        int loupe = f.loupe().slot();
        assertThat(r.ui.layerStack().of(loupe).name()).isEqualTo(Layers.TOOLTIP);
        float[] lb = r.box(f.loupe());
        float[] fb = r.box(f.element());
        assertThat(lb[3]).as("above the field's line").isLessThanOrEqualTo(fb[1] + 1);
        float half = (lb[2] - lb[0]) / 2;
        assertThat(lb[0]).as("on the screen").isGreaterThanOrEqualTo(0);
        assertThat((lb[0] + lb[2]) / 2).as("centred on the dragged end, kept on the screen")
            .isCloseTo(Math.max(half, hx), within(1f));
        float zoom = r.ui.styles().styleOf(loupe).getFloat(TextStyles.LOUPE_ZOOM);
        DrawList tips = r.ui.layer(Layers.TOOLTIP).drawList();
        float magnified = tallestGlyph(tips);
        float plain = tallestGlyph(r.ui.layers()[0]);
        assertThat(plain).isPositive();
        assertThat(magnified / plain).as("the glyphs %s times the size", zoom).isCloseTo(zoom, within(0.05f));
        LayoutSanity.assertSane(r.ui, "loupe shown (" + theme + ")");

        // dragging on: the loupe follows the end, then goes when the finger lifts
        float before = lb[0] + lb[2];
        for (int i = 1; i <= 6; i++) {
            r.synthetic.pointer(FINGER, Pointer.Kind.TOUCH, hx + i * 12, hy, Pointer.PRIMARY);
            r.frame();
        }
        float[] moved = r.box(f.loupe());
        assertThat(moved[0] + moved[2]).as("followed").isGreaterThan(before);
        assertThat((moved[0] + moved[2]) / 2).isCloseTo(Math.max(half, f.handle(2)), within(1f));
        r.synthetic.pointer(FINGER, Pointer.Kind.TOUCH, hx + 72, hy, 0);
        r.frame();
        assertThat(f.loupeShown()).isFalse();
        assertThat(f.loupe().parent()).as("taken off the layer").isNull();
        r.frame();
        assertThat(tallestGlyph(r.ui.layer(Layers.TOOLTIP).drawList())).as("nothing magnified left").isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"default"})
    void atTheTopOfTheScreenItShowsBelowTheLineAndDraggingAllocatesNothing(String theme) {
        TextRig r = rig(theme);
        TextEditor f = selected(r, 0);
        float hx = f.handle(2);
        float hy = f.handle(3);
        r.synthetic.pointer(FINGER, Pointer.Kind.TOUCH, hx, hy, Pointer.PRIMARY);
        r.frame();
        r.settle();
        float[] lb = r.box(f.loupe());
        float[] fb = r.box(f.element());
        assertThat(lb[1]).as("below the line where there is no room above").isGreaterThanOrEqualTo(hy);
        assertThat(lb[1]).isGreaterThan(fb[1]);
        float[] x = {hx};
        assertAllocationFree(() -> {
            x[0] = x[0] == hx ? hx + 20 : hx;
            r.synthetic.pointer(FINGER, Pointer.Kind.TOUCH, x[0], hy, Pointer.PRIMARY);
            r.frame();
        });
        assertThat(f.loupeShown()).isTrue();
        f.element().destroy();
        r.frame();
        assertThat(f.loupe().alive()).as("goes with its editor").isFalse();
    }
}
