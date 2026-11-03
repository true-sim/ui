package com.truesim.ui.controls.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.TextInputHost;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/** Multi-line areas: wrapping, line moves, tabs, line breaks, line numbers, scrolling, a megabyte of text. */
class TextAreaTest {

    static final String SHEET = """
        root { layout: stack; padding: 0px; }
        textarea {
          width: 300px; height: 120px; padding: 4px; clip: true; font-family: "Inter"; font-size: 14px;
          line-height: 1.5; color: #ffffff;
          caret { width: 2px; background: box(#ff0000); }
          selection { background: box(#0000ff); }
          gutter { padding: 0px 4px 0px 4px; color: #888888; background: box(#222222); }
        }
        """;

    static TextRig rig() {
        return new TextRig(Tss.compileString("area.tss", SHEET));
    }

    static TextEditor area(TextRig r) {
        TextEditor a = TextEditor.area(r.ui, null, "Notes");
        r.add(a.element());
        r.frame();
        a.focus();
        r.frame();
        return a;
    }

    @Test
    void linesBreakAndTheCaretKeepsItsColumn() {
        TextRig r = rig();
        TextEditor a = area(r);
        assertThat(r.host.kind).isEqualTo(TextInputHost.MULTILINE);
        r.type("first line");
        r.press(UiCommand.SUBMIT); // Enter in an area: a line break
        r.type("second");
        r.press(UiCommand.NEWLINE);
        r.type("third line here");
        assertThat(a.text().toString()).isEqualTo("first line\nsecond\nthird line here");
        assertThat(a.editable().lineCount()).isEqualTo(3);
        r.press(UiCommand.UP);
        assertThat(a.editable().caret()).as("the end of the shorter line").isEqualTo(17);
        r.press(UiCommand.UP);
        assertThat(a.editable().caret()).as("the column kept from the third line").isGreaterThan(8);
        r.press(UiCommand.UP);
        assertThat(a.editable().caret()).as("above the first line: its start").isZero();
        r.press(UiCommand.DOWN, UiCommand.DOWN, UiCommand.DOWN, UiCommand.DOWN);
        assertThat(a.editable().caret()).isEqualTo(a.text().length());
        r.press(UiCommand.NEXT);
        assertThat(a.text().toString()).as("Tab inserts a tab").endsWith("\t");
        TextEditor.AREA_TAB.select("focus");
        try {
            r.press(UiCommand.NEXT);
            assertThat(a.text().toString()).endsWith("here\t");
        } finally {
            TextEditor.AREA_TAB.select("insert");
        }
        a.submitOnEnter(true);
        int[] submitted = new int[1];
        a.listener(new TextEditor.Listener() {
            @Override
            public void submitted(TextEditor editor) {
                submitted[0]++;
            }
        });
        r.press(UiCommand.SUBMIT);
        assertThat(submitted[0]).isEqualTo(1);
    }

    @Test
    void wrappedLinesMoveByVisualLine() {
        TextRig r = rig();
        TextEditor a = area(r);
        a.text("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma");
        r.settle();
        r.press(UiCommand.DOC_START, UiCommand.LINE_END);
        int end = a.editable().caret();
        assertThat(end).as("the end of the first visual line").isGreaterThan(10).isLessThan(60);
        r.press(UiCommand.LINE_START);
        assertThat(a.editable().caret()).isZero();
        r.press(UiCommand.DOWN);
        assertThat(a.editable().caret()).isGreaterThanOrEqualTo(end);
        r.extend(UiCommand.DOC_END);
        assertThat(a.editable().selectionEnd()).isEqualTo(a.text().length());
        a.wrap(false);
        r.settle();
        r.press(UiCommand.DOC_END);
        assertThat(a.scrollX()).as("without wrapping the area scrolls sideways").isGreaterThan(0);
    }

    @Test
    void longTextScrollsByWheelPageAndCaret() {
        TextRig r = rig();
        TextEditor a = area(r);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            b.append("line ").append(i).append('\n');
        }
        a.text(b);
        r.settle();
        assertThat(a.scrollY()).as("the caret at the end is in view").isGreaterThan(1000);
        r.press(UiCommand.DOC_START);
        assertThat(a.scrollY()).isZero();
        r.press(UiCommand.PAGE_DOWN);
        assertThat(a.scrollY()).isGreaterThan(0);
        assertThat(a.editable().caret()).isGreaterThan(0);
        r.press(UiCommand.PAGE_UP, UiCommand.PAGE_UP, UiCommand.PAGE_UP);
        assertThat(a.editable().caret()).isZero();
        assertThat(a.scrollY()).isZero();
        float[] box = r.box(a.element());
        r.synthetic.mouse(box[0] + 50, box[1] + 50, 0);
        r.synthetic.scroll(0, 0, -2);
        r.frame();
        assertThat(a.scrollY()).as("the wheel scrolls without moving the caret").isGreaterThan(0);
        assertThat(a.editable().caret()).isZero();
        assertThat(a.contentHeight()).isGreaterThan(200 * 20);
        // touch: a drag scrolls, a tap places the caret
        r.synthetic.pointer(5, Pointer.Kind.TOUCH, box[0] + 60, box[1] + 80, Pointer.PRIMARY);
        r.frame();
        float before = a.scrollY();
        r.synthetic.pointer(5, Pointer.Kind.TOUCH, box[0] + 60, box[1] + 20, Pointer.PRIMARY);
        r.frame();
        r.synthetic.pointer(5, Pointer.Kind.TOUCH, box[0] + 60, box[1] + 20, 0);
        r.frame();
        r.synthetic.lift(5);
        r.frame();
        assertThat(a.scrollY()).isGreaterThan(before);
        // dragging the mouse past the bottom scrolls down while selecting
        r.mouse(box[0] + 30, box[1] + 30, Pointer.PRIMARY);
        r.mouse(box[0] + 30, box[3] + 40, Pointer.PRIMARY);
        r.mouse(box[0] + 30, box[3] + 40, 0);
        assertThat(a.editable().hasSelection()).isTrue();
    }

    @Test
    void lineNumbersSitInAGutter() {
        TextRig r = rig();
        TextEditor a = area(r);
        a.text("a\nb\nc");
        r.settle();
        int plain = r.glyphs();
        a.lineNumbers(true);
        r.settle();
        assertThat(r.glyphs()).as("three numbers more").isEqualTo(plain + 3);
        a.lineNumbers(false);
        r.settle();
        assertThat(r.glyphs()).isEqualTo(plain);
    }

    @Test
    void steadyFramesAllocateNothing() {
        TextRig r = rig();
        TextEditor a = area(r);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            b.append("The quick brown fox ").append(i).append(" jumps over the lazy dog.\n");
        }
        a.text(b);
        r.press(UiCommand.DOC_START);
        r.settle();
        for (int i = 0; i < 60; i++) { // shape the region scrolled over once
            a.scrollBy(i < 30 ? 9 : -9);
            r.frame();
        }
        float[] box = r.box(a.element());
        r.synthetic.mouse(box[0] + 40, box[1] + 40, 0);
        r.frame();
        int[] frame = new int[1];
        AllocationAssertions.assertAllocationFree(() -> {
            r.later(300_000_000L); // the caret blinks
            r.frame();
            a.scrollBy(frame[0]++ % 2 == 0 ? 9 : -9); // scrolling over shaped text
            r.frame();
            r.synthetic.mouse(box[0] + 40 + frame[0] % 3, box[1] + 40, 0); // hovering
            r.frame();
        });
    }

    @Test
    void aMegabyteStaysResponsive() {
        TextRig r = rig();
        TextEditor a = area(r);
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (b.length() < 1_000_000) {
            b.append("Line ").append(i++).append(": the quick brown fox jumps over the lazy dog, again and again.\n");
        }
        a.text(b);
        r.settle();
        r.press(UiCommand.DOC_START);
        long worst = 0;
        long total = 0;
        int frames = 200;
        for (int f = 0; f < frames; f++) {
            long t0 = System.nanoTime();
            if (f % 2 == 0) {
                r.synthetic.type("x");
            } else {
                a.scrollBy(23);
            }
            r.frame();
            long t = System.nanoTime() - t0;
            total += t;
            worst = Math.max(worst, f > 20 ? t : 0);
        }
        assertThat(a.text().length()).isGreaterThan(1_000_000);
        assertThat(total / frames).as("mean frame, ns (with JaCoCo and no warm-up: generous)").isLessThan(20_000_000L);
        if ("true".equals(System.getenv("TRUESIM_BENCHMARK"))) {
            // typing prose (words, line breaks every ~70 characters) in the middle of the megabyte, and scrolling
            a.editable().moveTo(a.text().length() / 2, false);
            String prose = "the quick brown fox jumps over the lazy dog while the caret keeps up with it\n";
            long[] typing = new long[4000];
            long[] scrolling = new long[4000];
            for (int k = 0; k < 8000; k++) { // the first half warms the JIT up
                long t0 = System.nanoTime();
                r.synthetic.type(String.valueOf(prose.charAt(k % prose.length())));
                r.frame();
                typing[k % 4000] = System.nanoTime() - t0;
            }
            for (int k = 0; k < 8000; k++) {
                long t0 = System.nanoTime();
                a.scrollBy(k % 400 < 200 ? 37 : -37);
                r.frame();
                scrolling[k % 4000] = System.nanoTime() - t0;
            }
            java.util.Arrays.sort(typing);
            java.util.Arrays.sort(scrolling);
            System.out.printf("1 MB text area (%d paragraphs): typing frame median %.3f ms, p99 %.3f ms; "
                    + "scrolling frame median %.3f ms, p99 %.3f ms%n", a.editable().lineCount(),
                typing[2000] / 1e6, typing[3960] / 1e6, scrolling[2000] / 1e6, scrolling[3960] / 1e6);
        }
    }
}
