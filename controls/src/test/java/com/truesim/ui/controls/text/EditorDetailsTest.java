package com.truesim.ui.controls.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.DrawListDump;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** The details: placeholders, touch, wheel, word and line drags, decorations, line breaks, many paragraphs. */
class EditorDetailsTest {

    private static final String SHEET = TextAreaTest.SHEET + """
        textfield {
          width: 120px; padding: 2px; font-family: "Inter"; font-size: 14px; color: #ffffff; clip: true;
          caret { width: 2px; background: box(#ff0000); }
          placeholder { color: #808080; font-size: 12px; font-style: italic; font-family: "Inter"; }
          composition { background: box(#004400); }
        }
        textfield.centred { text-align: center; }
        span.u { text-decoration: underline; font-style: italic; background: box(#333333); }
        span.s { text-decoration: line-through; }
        img { height: 12px; }
        """;

    private static TextRig rig() {
        return new TextRig(Tss.compileString("details.tss", SHEET));
    }

    private static int boxes(TextRig r) {
        DrawList list = r.ui.layers()[0];
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            n += Primitive.kind(list.header(i)) == Primitive.BOX ? 1 : 0;
        }
        return n;
    }

    @Test
    void anEmptyFieldShowsItsPlaceholderCentredWhenAsked() {
        TextRig r = rig();
        TextEditor f = TextEditor.field(r.ui, "centred", "Name").placeholder("Your name");
        r.add(f.element()).settle();
        assertThat(r.glyphs()).isEqualTo(8);
        float[] b = r.box(f.element());
        DrawList list = r.ui.layers()[0];
        float firstX = 0;
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.GLYPH) {
                firstX = list.floatAt(i, Primitive.X0);
                break;
            }
        }
        assertThat(firstX - b[0]).as("centred").isGreaterThan(20);
        f.focus();
        r.type("x");
        r.settle();
        assertThat(r.glyphs()).isEqualTo(1);
        f.text("");
        r.settle();
        assertThat(r.glyphs()).isEqualTo(8);
    }

    @Test
    void touchSelectionsHaveHandlesThatMoveTheirEnds() {
        TextRig r = rig();
        TextEditor f = TextEditor.field(r.ui, null, "Name");
        r.ui.styles().inline(f.element().slot(), com.truesim.ui.style.InlineStyle.builder()
            .set(com.truesim.ui.style.Styles.WIDTH, com.truesim.ui.style.value.Length.px(400)).build());
        r.add(f.element()).frame();
        f.text("one two three four");
        r.settle();
        float[] b = r.box(f.element());
        float y = (b[1] + b[3]) / 2;
        float x = f.handle(0);
        assertThat(x).as("no handles yet").isNaN();
        // a long press on "two" selects it, with a handle at each end
        float two = b[0] + 45;
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, two, y, Pointer.PRIMARY);
        r.frame();
        r.later(800_000_000L).frame();
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, two, y, 0);
        r.frame();
        assertThat(f.text().subSequence(f.editable().selectionStart(), f.editable().selectionEnd()).toString())
            .isEqualTo("two");
        assertThat(f.touchHandles()).isTrue();
        assertThat(f.handle(2)).isGreaterThan(f.handle(0));
        // the end handle dragged right takes the selection's end along
        float hx = f.handle(2);
        float hy = f.handle(3);
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, hx + 2, hy + 2, Pointer.PRIMARY);
        r.frame();
        for (float dx = 10; dx <= 50; dx += 10) {
            r.synthetic.pointer(3, Pointer.Kind.TOUCH, hx + 2 + dx, hy + 2, Pointer.PRIMARY);
            r.frame();
        }
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, hx + 52, hy + 2, 0);
        r.frame();
        assertThat(f.text().subSequence(f.editable().selectionStart(), f.editable().selectionEnd()).toString())
            .startsWith("two th");
        // the start handle dragged past the end becomes the end
        float sx = f.handle(0);
        float sy = f.handle(1);
        float end = f.handle(2);
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, sx, sy, Pointer.PRIMARY);
        r.frame();
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, end + 30, sy, Pointer.PRIMARY);
        r.frame();
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, end + 30, sy, 0);
        r.frame();
        assertThat(f.editable().selectionStart()).isGreaterThan(4);
        assertThat(f.touchHandles()).isTrue();
        // typing or a mouse press ends them
        r.type("x");
        assertThat(f.touchHandles()).isFalse();
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, two, y, Pointer.PRIMARY);
        r.frame();
        r.later(800_000_000L).frame();
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, two, y, 0);
        r.frame();
        assertThat(f.touchHandles()).isTrue();
        r.click(two, y);
        assertThat(f.touchHandles()).isFalse();
        assertThat(f.handle(0)).isNaN();
    }

    @Test
    void touchTapsDragsAndLongPressesInAField() {
        TextRig r = rig();
        TextEditor f = TextEditor.field(r.ui, null, "Name");
        r.add(f.element()).frame();
        f.text("one two three four five six seven");
        r.settle();
        float[] b = r.box(f.element());
        float y = (b[1] + b[3]) / 2;
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, b[0] + 30, y, Pointer.PRIMARY);
        r.frame();
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, b[0] + 30, y, 0);
        r.frame();
        assertThat(f.focused()).isTrue();
        int tapped = f.editable().caret();
        assertThat(tapped).isBetween(1, f.text().length() - 1);
        f.editable().moveTo(f.text().length(), false);
        r.frame();
        float scrolled = f.scrollX();
        assertThat(scrolled).isGreaterThan(0);
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, b[0] + 30, y, Pointer.PRIMARY);
        r.frame();
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, b[0] + 90, y, Pointer.PRIMARY);
        r.frame();
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, b[0] + 90, y, 0);
        r.frame();
        assertThat(f.scrollX()).as("a touch drag scrolls the field").isLessThan(scrolled);
        // a long press selects the word
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, b[0] + 30, y, Pointer.PRIMARY);
        r.frame();
        r.later(800_000_000L).frame();
        r.synthetic.pointer(3, Pointer.Kind.TOUCH, b[0] + 30, y, 0);
        r.frame();
        assertThat(f.editable().hasSelection()).isTrue();
        // the wheel scrolls a field sideways
        r.synthetic.mouse(b[0] + 10, y, 0);
        r.synthetic.scroll(0, -3, 0);
        r.frame();
        assertThat(f.scrollX()).isGreaterThan(0);
        r.synthetic.scroll(0, 50, 0);
        r.frame();
        assertThat(f.scrollX()).isZero();
        r.synthetic.lift(3);
        r.frame();
    }

    @Test
    void wordAndLineDragsExtendByTheirUnit() {
        TextRig r = rig();
        TextEditor a = TextEditor.area(r.ui, null, "Doc");
        r.add(a.element()).frame();
        a.text("alpha beta gamma\ndelta epsilon zeta\neta theta iota");
        r.settle();
        float[] b = r.box(a.element());
        float lh = 21;
        float x = b[0] + 60;
        // double press on "beta", drag down a line: whole words
        r.mouse(x, b[1] + 10, Pointer.PRIMARY);
        r.mouse(x, b[1] + 10, 0);
        r.mouse(x, b[1] + 10, Pointer.PRIMARY);
        r.mouse(x, b[1] + 10 + lh, Pointer.PRIMARY);
        r.mouse(x, b[1] + 10 + lh, 0);
        assertThat(a.editable().selectionStart()).isEqualTo(6);
        assertThat(a.text().charAt(a.editable().selectionEnd() - 1)).isNotEqualTo(' ');
        // dragging back above the word reverses
        r.later(1_000_000_000L).mouse(x, b[1] + 10 + lh, Pointer.PRIMARY);
        r.mouse(x, b[1] + 10 + lh, 0);
        r.mouse(x, b[1] + 10 + lh, Pointer.PRIMARY);
        r.mouse(b[0] + 5, b[1] + 10, Pointer.PRIMARY);
        r.mouse(b[0] + 5, b[1] + 10, 0);
        assertThat(a.editable().selectionStart()).isZero();
        // a triple press selects the line, a drag extends by lines
        r.later(1_000_000_000L).mouse(x, b[1] + 10, Pointer.PRIMARY);
        r.mouse(x, b[1] + 10, 0);
        r.mouse(x, b[1] + 10, Pointer.PRIMARY);
        r.mouse(x, b[1] + 10, 0);
        r.mouse(x, b[1] + 10, Pointer.PRIMARY);
        r.mouse(x, b[1] + 10 + 2 * lh, Pointer.PRIMARY);
        r.mouse(x, b[1] + 10 + 2 * lh, 0);
        assertThat(a.editable().selectedText()).isEqualTo(a.text().toString());
        int before = boxes(r);
        r.settle();
        assertThat(boxes(r)).as("selection boxes, the selected line breaks too").isGreaterThanOrEqualTo(before);
        assertThat(DrawListDump.of(r.ui.layers()[0])).contains("a=(0.00 0.00 1.00 1.00)");
        r.press(UiCommand.LEFT);
        assertThat(a.editable().caret()).as("left collapses to the start").isZero();
        r.press(UiCommand.DOC_END, UiCommand.RIGHT, UiCommand.LEFT, UiCommand.LINE_START, UiCommand.LEFT);
        assertThat(a.editable().caret()).as("left at a line start goes to the previous line's end").isEqualTo(35);
        r.press(UiCommand.RIGHT);
        assertThat(a.editable().caret()).isEqualTo(36);
    }

    @Test
    void decorationsHighlightsAndImagesDrawAsBoxes() {
        TextRig r = rig();
        r.ui.images().add("icon", new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB));
        TextEditor a = TextEditor.area(r.ui, null, "Doc").markup(
            "[class=u]under[/class] [class=s]struck[/class] [u]u[/u][s]s[/s] [img=icon] [img=missing] "
                + "[color=red]r[/color][color=nope]n[/color][size=18px]z[/size][size=x]q[/size]");
        r.add(a.element()).settle();
        String dump = DrawListDump.of(r.ui.layers()[0]);
        assertThat(dump).contains("a=(0.03 0.03 0.03 1.00)"); // the highlight
        assertThat(dump).contains("image ");
        assertThat(boxes(r)).as("highlight, two underlines, two lines through").isGreaterThanOrEqualTo(5);
        assertThat(a.markup()).contains("[img=icon]");
    }

    @Test
    void manyParagraphsCycleThePool() {
        TextRig r = rig();
        TextEditor a = TextEditor.area(r.ui, null, "Doc").lineNumbers(true);
        r.add(a.element()).frame();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            b.append(i % 7 == 0 ? "" : "paragraph ").append(i).append('\n');
        }
        a.text(b);
        r.settle();
        for (int i = 0; i < 80; i++) {
            a.scrollBy(-200);
            r.frame();
        }
        assertThat(a.scrollY()).isZero();
        a.focus();
        r.press(UiCommand.DOC_START);
        r.type("\n\n");
        r.press(UiCommand.DOC_END);
        r.type("tail");
        assertThat(a.editable().lineCount()).isEqualTo(403);
        a.text("short");
        r.settle();
        assertThat(a.editable().lineCount()).isEqualTo(1);
        assertThat(a.scrollY()).isZero();
        assertThat(TextEditor.tunables()).hasSize(11); // 5 of editing, 6 of spell checking
        assertThatThrownBy(() -> TextEditor.field(new Ui(Tss.compileString("x.tss", ""), null), null, "x"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyboardFocusSelectsAFieldsTextAndCompositionIsUnderlined() {
        TextRig r = rig();
        TextEditor first = TextEditor.field(r.ui, null, "First");
        TextEditor second = TextEditor.field(r.ui, null, "Second");
        r.add(first.element()).add(second.element()).frame();
        second.text("selected");
        first.focus();
        r.frame();
        r.press(UiCommand.NEXT);
        assertThat(second.focused()).isTrue();
        assertThat(second.editable().selectedText()).isEqualTo("selected");
        r.press(UiCommand.DOC_END);
        r.compose("ab", 1);
        r.settle();
        assertThat(DrawListDump.of(r.ui.layers()[0])).contains("a=(0.00 0.06 0.00 1.00)");
        r.press(UiCommand.PREVIOUS);
        assertThat(second.text().toString()).as("leaving commits the preedit").isEqualTo("selectedab");
    }
}
