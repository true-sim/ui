package com.truesim.ui.controls.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.UiState;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.TextInputHost;
import com.truesim.ui.core.input.UiCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Password fields, search boxes, number fields and tag inputs. */
class SmallControlsTest {

    private static float cx(float[] b) {
        return (b[0] + b[2]) / 2;
    }

    private static float cy(float[] b) {
        return (b[1] + b[3]) / 2;
    }

    @Test
    void passwordsAreMaskedNeverCopiedAndTakeNoComposition() {
        TextRig r = new TextRig();
        PasswordField pw = PasswordField.create(r.ui, null, "Password", "Show password");
        r.add(pw.element()).frame();
        pw.editor().focus();
        r.frame();
        assertThat(r.host.kind).isEqualTo(TextInputHost.PASSWORD);
        r.type("s3cr\uD83D\uDE00t");
        assertThat(pw.text().toString()).isEqualTo("s3cr\uD83D\uDE00t");
        assertThat(pw.editor().masked()).isTrue();
        r.settle();
        int masked = r.glyphs();
        assertThat(masked).as("one mask a character, the emoji too").isEqualTo(6);
        r.clipboard.text("before");
        r.press(UiCommand.SELECT_ALL, UiCommand.COPY, UiCommand.CUT);
        assertThat(r.clipboard.text()).as("nothing reaches the clipboard").isEqualTo("before");
        assertThat(pw.text().toString()).isEqualTo("s3cr\uD83D\uDE00t");
        r.press(UiCommand.DOC_END);
        r.compose("ka", 2);
        assertThat(pw.text().toString()).as("no composition").isEqualTo("s3cr\uD83D\uDE00t");
        r.press(UiCommand.DELETE_WORD_BACK);
        assertThat(pw.text().toString()).as("words are not revealed by deleting them").isEqualTo("s3cr\uD83D\uDE00");
        float[] toggle = r.box(pw.revealToggle());
        r.click(cx(toggle), cy(toggle));
        assertThat(pw.revealed()).isTrue();
        assertThat(pw.revealToggle().is(UiState.CHECKED)).isTrue();
        assertThat(pw.editor().masked()).isFalse();
        r.settle();
        assertThat(r.glyphs()).isEqualTo(5);
        pw.revealed(false);
        assertThat(pw.editor().masked()).isTrue();
        r.press(UiCommand.PASTE);
        assertThat(pw.text().toString()).as("pasting into it is fine").endsWith("before");
    }

    @Test
    void searchBoxesClearAndSubmit() {
        TextRig r = new TextRig();
        SearchField s = SearchField.create(r.ui, null, "Search", "Clear");
        List<String> searches = new ArrayList<>();
        List<String> typed = new ArrayList<>();
        s.listener(new SearchField.Listener() {
            @Override
            public void search(SearchField field, CharSequence query) {
                searches.add(query.toString());
            }

            @Override
            public void changed(SearchField field, CharSequence query) {
                typed.add(query.toString());
            }
        });
        r.add(s.element()).frame();
        assertThat(r.ui.layout().shown(s.clearButton().slot())).as("no clear button while empty").isFalse();
        s.editor().focus();
        r.type("dra");
        r.type("gon");
        assertThat(typed).containsExactly("dra", "dragon");
        r.press(UiCommand.SUBMIT);
        assertThat(searches).containsExactly("dragon");
        assertThat(r.ui.layout().shown(s.clearButton().slot())).isTrue();
        r.press(UiCommand.CANCEL);
        assertThat(s.query().toString()).isEmpty();
        r.type("x");
        r.frame();
        float[] clear = r.box(s.clearButton());
        r.click(cx(clear), cy(clear));
        assertThat(s.query().toString()).isEmpty();
        assertThat(s.editor().focused()).isTrue();
    }

    @Test
    void numberFieldsStepDragAndClamp() {
        TextRig r = new TextRig();
        NumberField n = NumberField.create(r.ui, null, "Volume", "Less", "More").range(0, 10).step(0.5).decimals(1);
        List<Double> values = new ArrayList<>();
        n.listener((field, v) -> values.add(v));
        r.add(n.element()).frame();
        assertThat(n.editor().text().toString()).isEqualTo("0.0");
        float[] up = r.box(n.stepUp());
        r.click(cx(up), cy(up));
        r.later(1_000_000_000L).click(cx(up), cy(up));
        assertThat(n.value()).isEqualTo(1.0);
        float[] down = r.box(n.stepDown());
        r.later(1_000_000_000L).click(cx(down), cy(down));
        assertThat(n.value()).isEqualTo(0.5);
        n.editor().focus();
        r.frame();
        assertThat(r.host.kind).isEqualTo(TextInputHost.NUMBER);
        r.press(UiCommand.UP, UiCommand.UP);
        assertThat(n.value()).isEqualTo(1.5);
        r.press(UiCommand.PAGE_UP);
        assertThat(n.value()).as("ten steps, clamped").isEqualTo(6.5);
        r.press(UiCommand.SELECT_ALL);
        r.type("42");
        assertThat(n.element().is(UiState.INVALID)).as("out of range while typed").isTrue();
        r.press(UiCommand.SUBMIT);
        assertThat(n.value()).isEqualTo(10);
        assertThat(n.editor().text().toString()).isEqualTo("10.0");
        r.press(UiCommand.SELECT_ALL);
        r.type("3.5");
        r.press(UiCommand.PAGE_DOWN);
        assertThat(n.value()).as("the typed value committed, then ten steps down, clamped").isEqualTo(0);
        r.press(UiCommand.SELECT_ALL);
        r.type("7");
        r.ui.interaction().blur(0); // focus leaves: the typed value commits
        r.frame();
        assertThat(n.value()).isEqualTo(7);
        float[] h = r.box(n.handle());
        r.mouse(cx(h), cy(h), Pointer.PRIMARY);
        r.mouse(cx(h) + 12, cy(h), Pointer.PRIMARY);
        assertThat(n.value()).as("12 px at 4 px a step: three steps").isEqualTo(8.5);
        r.mouse(cx(h) + 12, cy(h), 0);
        assertThat(values).contains(1.0, 0.5, 10.0, 8.5);
        n.value(-3);
        assertThat(n.value()).isZero();
    }

    @Test
    void tagInputsMakeChipsFromSeparatorsAndEnter() {
        TextRig r = new TextRig();
        TagInput t = TagInput.create(r.ui, null, "Tags", "Remove");
        List<String> seen = new ArrayList<>();
        t.listener((input, tags) -> seen.add(String.join("|", tags)));
        r.add(t.element()).frame();
        t.editor().focus();
        r.type("red, green");
        assertThat(t.tags()).containsExactly("red");
        assertThat(t.editor().text().toString()).isEqualTo(" green");
        r.press(UiCommand.SUBMIT);
        assertThat(t.tags()).containsExactly("red", "green");
        r.type("red;");
        assertThat(t.tags()).as("no repeats").containsExactly("red", "green");
        r.press(UiCommand.DELETE_BACK);
        assertThat(t.tags()).as("backspace in an empty field removes the last chip").containsExactly("red");
        t.add("blue");
        r.settle();
        float[] remove = r.box(r.ui.store().element(r.ui.store().lastChild(r.ui.store().firstChild(
            t.element().slot()))));
        r.click(cx(remove), cy(remove));
        assertThat(t.tags()).containsExactly("blue");
        assertThat(t.add("  ")).isFalse();
        assertThat(seen).contains("red", "red|green", "blue");
        t.separators(" ");
        r.type("a b");
        assertThat(t.tags()).containsExactly("blue", "a");
    }
}
