package com.truesim.ui.controls.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.UiState;
import com.truesim.ui.core.input.TextInputHost;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.text.edit.TextFilters;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Single-line fields through the real frame: every command, the pointer, the IME, filters, scrolling. */
class TextFieldTest {

    private static TextEditor field(TextRig r) {
        TextEditor f = TextEditor.field(r.ui, null, "Name");
        r.add(f.element());
        r.frame();
        return f;
    }

    @Test
    void typingShowsTextAndCommandsEditIt() {
        TextRig r = new TextRig();
        TextEditor f = field(r);
        List<String> changes = new ArrayList<>();
        List<String> submits = new ArrayList<>();
        f.listener(new TextEditor.Listener() {
            @Override
            public void changed(TextEditor editor) {
                changes.add(editor.text().toString());
            }

            @Override
            public void submitted(TextEditor editor) {
                submits.add(editor.text().toString());
            }
        });
        float[] b = r.box(f.element());
        r.click(b[0] + 5, (b[1] + b[3]) / 2);
        assertThat(f.focused()).isTrue();
        assertThat(r.host.calls).containsExactly("start " + TextInputHost.TEXT);
        r.type("hello world");
        assertThat(f.text().toString()).isEqualTo("hello world");
        r.settle();
        assertThat(r.glyphs()).isEqualTo(10);
        r.press(UiCommand.WORD_LEFT);
        assertThat(f.editable().caret()).isEqualTo(6);
        r.extend(UiCommand.LINE_END);
        assertThat(f.editable().selectedText()).isEqualTo("world");
        r.press(UiCommand.COPY);
        assertThat(r.clipboard.text()).isEqualTo("world");
        r.press(UiCommand.CUT);
        assertThat(f.text().toString()).isEqualTo("hello ");
        r.press(UiCommand.UNDO);
        assertThat(f.text().toString()).isEqualTo("hello world");
        r.press(UiCommand.REDO);
        r.press(UiCommand.PASTE, UiCommand.PASTE);
        assertThat(f.text().toString()).isEqualTo("hello worldworld");
        r.press(UiCommand.DELETE_WORD_BACK);
        assertThat(f.text().toString()).isEqualTo("hello ");
        r.press(UiCommand.LINE_START, UiCommand.DELETE_WORD_FORWARD);
        assertThat(f.text().toString()).isEmpty();
        r.type("abc");
        r.press(UiCommand.LEFT, UiCommand.DELETE_BACK, UiCommand.DELETE_FORWARD);
        assertThat(f.text().toString()).isEqualTo("a");
        r.press(UiCommand.SELECT_ALL);
        assertThat(f.editable().selectedText()).isEqualTo("a");
        r.press(UiCommand.HOME, UiCommand.RIGHT, UiCommand.END, UiCommand.DOC_START, UiCommand.DOC_END);
        assertThat(f.editable().caret()).isEqualTo(1);
        r.press(UiCommand.SUBMIT);
        assertThat(submits).containsExactly("a");
        assertThat(changes).isNotEmpty().endsWith("a");
        r.press(UiCommand.NEWLINE);
        assertThat(f.text().toString()).as("no line breaks in a field").isEqualTo("a");
        r.press(UiCommand.DOWN);
        assertThat(f.focused()).as("up and down are not the field's: focus may move").isTrue();
    }

    @Test
    void thePointerSelectsCharactersWordsAndLines() {
        TextRig r = new TextRig();
        TextEditor f = field(r);
        f.text("one two three");
        r.settle();
        float[] b = r.box(f.element());
        float y = (b[1] + b[3]) / 2;
        float x0 = b[0] + 9; // the padding
        r.click(x0 + 1, y);
        assertThat(f.editable().caret()).isZero();
        r.later(1_000_000_000L).click(b[2] - 3, y);
        assertThat(f.editable().caret()).isEqualTo(13);
        r.synthetic.modifiers(0, com.truesim.ui.core.input.Pointer.SHIFT);
        r.later(1_000_000_000L).click(x0 + 1, y);
        r.synthetic.modifiers(0, 0);
        assertThat(f.editable().selectedText()).isEqualTo("one two three");
        // a double click selects the word
        float twoX = x0 + 40;
        r.later(1_000_000_000L).click(twoX, y);
        r.click(twoX, y);
        assertThat(f.editable().selectedText()).isEqualTo("two");
        r.click(twoX, y);
        assertThat(f.editable().selectedText()).as("a triple click: the line").isEqualTo("one two three");
        // press and drag
        r.later(1_000_000_000L).mouse(x0 + 1, y, com.truesim.ui.core.input.Pointer.PRIMARY);
        r.mouse(twoX, y, com.truesim.ui.core.input.Pointer.PRIMARY);
        r.mouse(twoX, y, 0);
        assertThat(f.editable().selectionStart()).isZero();
        assertThat(f.editable().selectionEnd()).isBetween(4, 7);
    }

    @Test
    void theImeComposesInlineWithItsWindowAtTheCaret() {
        TextRig r = new TextRig();
        TextEditor f = field(r);
        f.focus();
        r.type("ab");
        r.press(UiCommand.LEFT);
        r.compose("\u304B", 1);
        assertThat(f.text().toString()).isEqualTo("a\u304Bb");
        assertThat(f.editable().composing()).isTrue();
        float before = r.host.x;
        r.compose("\u304B\u306A", 2);
        assertThat(r.host.x).as("the candidate window follows the caret").isGreaterThan(before);
        r.synthetic.type("\u4EEE\u540D");
        r.compose("", 0);
        assertThat(f.text().toString()).isEqualTo("a\u4EEE\u540Db");
        assertThat(f.editable().composing()).isFalse();
        r.press(UiCommand.UNDO);
        assertThat(f.text().toString()).isEqualTo("ab");
    }

    @Test
    void filtersValidationLengthAndReadOnly() {
        TextRig r = new TextRig();
        TextEditor f = field(r);
        f.filter(TextFilters.number(false, 1)).validator(TextFilters.range(1, 10, false)).maxLength(4);
        f.focus();
        for (String c : new String[] {"1", "x", "2", ".", "5", "5"}) {
            r.type(c);
        }
        assertThat(f.text().toString()).as("letters dropped, one decimal").isEqualTo("12.5");
        r.press(UiCommand.DELETE_BACK, UiCommand.DELETE_BACK);
        assertThat(f.element().is(UiState.INVALID)).isTrue();
        assertThat(f.valid()).isFalse();
        r.press(UiCommand.DELETE_BACK);
        assertThat(f.element().is(UiState.INVALID)).isFalse();
        f.readOnly(true);
        r.type("5");
        r.press(UiCommand.DELETE_BACK, UiCommand.CUT, UiCommand.PASTE, UiCommand.UNDO);
        assertThat(f.text().toString()).isEqualTo("1");
        assertThat(f.readOnly()).isTrue();
        assertThat(f.element().is(UiState.READ_ONLY)).isTrue();
    }

    @Test
    void aLongTextScrollsToKeepTheCaretInView() {
        TextRig r = new TextRig();
        TextEditor f = field(r);
        f.focus();
        r.type("the quick brown fox jumps over the lazy dog and keeps running far away");
        assertThat(f.scrollX()).isGreaterThan(0);
        r.press(UiCommand.DOC_START);
        assertThat(f.scrollX()).isZero();
    }
}
