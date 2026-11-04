package com.truesim.ui.text.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The editing engine: every edit, the selection, undo and redo with coalescing, IME composition, filters, lines. */
class EditableTextTest {

    private static EditableText text(String value) {
        EditableText t = new EditableText();
        t.set(value);
        return t;
    }

    @Test
    void typingReplacesTheSelectionAndMovesTheCaret() {
        EditableText t = text("hello");
        assertThat(t.caret()).isEqualTo(5);
        t.select(1, 4);
        assertThat(t.selectedText()).isEqualTo("ell");
        assertThat(t.hasSelection()).isTrue();
        t.type("ipp");
        assertThat(t.text().toString()).isEqualTo("hippo");
        assertThat(t.caret()).isEqualTo(4);
        assertThat(t.hasSelection()).isFalse();
        t.selectAll();
        assertThat(t.selectionStart()).isZero();
        assertThat(t.selectionEnd()).isEqualTo(5);
        assertThat(t.anchor()).isZero();
        long v = t.version();
        assertThat(t.type("")).as("nothing over nothing").isTrue();
        assertThat(t.text().toString()).isEmpty();
        assertThat(t.version()).isGreaterThan(v);
        assertThat(t.type("")).isFalse();
    }

    @Test
    void movesByGraphemeAndWord() {
        EditableText t = text("ab \uD83D\uDC4D\uD83C\uDFFD cd, ef");
        t.moveTo(3, false);
        t.moveLogically(true, false, false);
        assertThat(t.caret()).as("an emoji with its skin tone is one step").isEqualTo(7);
        t.moveLogically(false, false, true);
        assertThat(t.caret()).isEqualTo(3);
        assertThat(t.anchor()).isEqualTo(7);
        t.moveLogically(true, false, false); // collapses to the selection's end
        assertThat(t.caret()).isEqualTo(7);
        t.moveTo(0, false);
        t.moveLogically(true, true, false);
        assertThat(t.caret()).as("after the word and its space").isEqualTo(3);
        t.moveLogically(true, true, false);
        assertThat(t.caret()).as("the emoji is a word of its own").isEqualTo(8);
        t.moveLogically(true, true, false);
        assertThat(t.caret()).isEqualTo(10);
        t.moveLogically(true, true, false);
        assertThat(t.caret()).as("punctuation").isEqualTo(12);
        t.moveLogically(false, true, false);
        assertThat(t.caret()).isEqualTo(10);
        t.moveLogically(false, true, false);
        assertThat(t.caret()).isEqualTo(8);
        assertThat(t.previousWord(0)).isZero();
        assertThat(t.nextWord(t.length())).isEqualTo(t.length());
        assertThat(t.previousGrapheme(0)).isZero();
        assertThat(t.nextGrapheme(t.length())).isEqualTo(t.length());
        t.moveTo(99, false);
        assertThat(t.caret()).isEqualTo(t.length());
    }

    @Test
    void wordsAndLinesAtAPosition() {
        EditableText t = new EditableText().multiLine(true);
        t.set("one two_3, four\nfive");
        assertThat(span(t.wordAt(5))).isEqualTo("4..9");
        assertThat(span(t.wordAt(9))).as("just after a word").isEqualTo("4..9");
        assertThat(span(t.wordAt(9 + 1))).as("the space after the comma").isEqualTo("10..11");
        assertThat(span(t.lineAt(2))).isEqualTo("0..15");
        assertThat(span(t.lineAt(17))).isEqualTo("16..20");
        assertThat(t.wordAt(0)).isEqualTo(3);
        assertThat(new EditableText().wordAt(0)).isZero();
        t.moveTo(15, false);
        t.moveLogically(true, true, false);
        assertThat(t.caret()).as("a word move stops at a line feed").isEqualTo(16);
        t.moveLogically(false, true, false);
        assertThat(t.caret()).isEqualTo(15);
        assertThat(t.nextGrapheme(15)).isEqualTo(16);
        assertThat(t.previousGrapheme(16)).isEqualTo(15);
    }

    private static String span(long packed) {
        return (packed >>> 32) + ".." + (int) packed;
    }

    @Test
    void deletingByCharacterWordAndSelection() {
        EditableText t = text("hello brave world");
        t.deleteBack(false);
        assertThat(t.text().toString()).isEqualTo("hello brave worl");
        t.deleteBack(true);
        assertThat(t.text().toString()).isEqualTo("hello brave ");
        t.moveTo(0, false);
        t.deleteForward(true);
        assertThat(t.text().toString()).isEqualTo("brave ");
        t.deleteForward(false);
        assertThat(t.text().toString()).isEqualTo("rave ");
        t.select(1, 3);
        t.deleteForward(false);
        assertThat(t.text().toString()).isEqualTo("re ");
        t.select(0, 1);
        t.deleteBack(false);
        assertThat(t.text().toString()).isEqualTo("e ");
        t.moveTo(0, false);
        assertThat(t.deleteBack(false)).isFalse();
        t.moveTo(2, false);
        assertThat(t.deleteForward(false)).isFalse();
        EditableText marks = text("e\u0301x");
        marks.moveTo(2, false);
        marks.deleteBack(false);
        assertThat(marks.text().toString()).as("backspace takes the mark typed last").isEqualTo("ex");
    }

    @Test
    void typingUndoesAsWordsAndDeletingAsRuns() {
        EditableText t = new EditableText();
        t.now(0);
        for (char c : "hello world".toCharArray()) {
            t.type(String.valueOf(c));
        }
        t.deleteBack(false);
        t.deleteBack(false);
        assertThat(t.text().toString()).isEqualTo("hello wor");
        assertThat(t.undo()).isTrue();
        assertThat(t.text().toString()).as("both backspaces at once").isEqualTo("hello world");
        assertThat(t.undo()).isTrue();
        assertThat(t.text().toString()).as("the second word").isEqualTo("hello ");
        assertThat(t.undo()).isTrue();
        assertThat(t.text().toString()).isEmpty();
        assertThat(t.undo()).isFalse();
        assertThat(t.canRedo()).isTrue();
        assertThat(t.redo()).isTrue();
        assertThat(t.text().toString()).isEqualTo("hello ");
        assertThat(t.caret()).isEqualTo(6);
        assertThat(t.redo()).isTrue();
        assertThat(t.redo()).isTrue();
        assertThat(t.redo()).isFalse();
        assertThat(t.text().toString()).isEqualTo("hello wor");
        t.type("m");
        assertThat(t.canRedo()).isFalse();
        assertThat(t.canUndo()).isTrue();
        t.undo();
        assertThat(t.text().toString()).as("a redone edit does not swallow new typing").isEqualTo("hello wor");
        t.clearHistory();
        assertThat(t.canUndo()).isFalse();
    }

    @Test
    void aPauseOrAJumpStartsANewEdit() {
        EditableText t = new EditableText().coalesceNanos(100);
        assertThat(t.coalesceNanos()).isEqualTo(100);
        t.now(0);
        t.type("a");
        t.now(50);
        t.type("b");
        t.now(500);
        t.type("c");
        t.moveTo(0, false);
        t.type("d");
        t.undo();
        assertThat(t.text().toString()).isEqualTo("abc");
        t.undo();
        assertThat(t.text().toString()).isEqualTo("ab");
        t.undo();
        assertThat(t.text().toString()).isEmpty();
        t.now(0);
        t.set("abc");
        t.moveTo(0, false);
        t.deleteForward(false);
        t.deleteForward(false);
        t.undo();
        assertThat(t.text().toString()).as("forward deletes undo together").isEqualTo("abc");
        assertThat(t.caret()).isZero();
        t.historyLimit(1);
        t.type("x");
        t.type(" y");
        t.type("z");
        t.undo();
        assertThat(t.undo()).as("one edit kept").isFalse();
    }

    @Test
    void pasteReplaceAndMaxLength() {
        EditableText t = text("abc").maxLength(6);
        assertThat(t.maxLength()).isEqualTo(6);
        t.paste("defgh", null);
        assertThat(t.text().toString()).as("cut to the maximum").isEqualTo("abcdef");
        assertThat(t.paste("x", null)).as("full").isFalse();
        t.select(0, 3);
        t.paste("\uD83D\uDE00\uD83D\uDE00", null);
        assertThat(t.text().toString()).as("never half a pair").isEqualTo("\uD83D\uDE00def");
        t.replace(0, 2, "Z");
        assertThat(t.text().toString()).isEqualTo("Zdef");
        t.undo();
        assertThat(t.text().toString()).isEqualTo("\uD83D\uDE00def");
        assertThatThrownBy(() -> t.maxLength(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lineBreaksAreSpacesInAFieldAndLineFeedsInAnArea() {
        EditableText field = new EditableText();
        field.set("a\r\nb\nc\rd");
        assertThat(field.text().toString()).isEqualTo("a b c d");
        assertThat(field.multiLine()).isFalse();
        EditableText area = new EditableText().multiLine(true);
        area.set("a\r\nb\rc");
        assertThat(area.text().toString()).isEqualTo("a\nb\nc");
        assertThat(area.lineCount()).isEqualTo(3);
        assertThat(area.lineStart(1)).isEqualTo(2);
        assertThat(area.lineEnd(1)).isEqualTo(3);
        assertThat(area.lineEnd(2)).isEqualTo(5);
        assertThat(area.lineOf(0)).isZero();
        assertThat(area.lineOf(1)).as("a line feed ends its line").isZero();
        assertThat(area.lineOf(2)).isEqualTo(1);
        assertThat(area.lineOf(5)).isEqualTo(2);
        area.moveTo(1, false);
        area.type("x\ny\n");
        assertThat(area.text().toString()).isEqualTo("ax\ny\n\nb\nc");
        assertThat(area.lineCount()).isEqualTo(5);
        assertThat(area.lineStart(3)).isEqualTo(6);
        area.select(2, 7);
        area.deleteBack(false);
        assertThat(area.text().toString()).isEqualTo("ax\nc");
        assertThat(area.lineCount()).isEqualTo(2);
        assertThat(area.lineStart(1)).isEqualTo(3);
        area.undo();
        assertThat(area.lineCount()).isEqualTo(5);
        area.redo();
        assertThat(area.lineCount()).isEqualTo(2);
        for (int i = 0; i < 40; i++) {
            area.type("\n");
        }
        assertThat(area.lineCount()).as("the index grows").isEqualTo(42);
    }

    @Test
    void filtersChangeOrRefuseEdits() {
        EditableText t = new EditableText().filter((text, start, end, value) -> {
            if (value.toString().contains("!")) {
                return null;
            }
            return value.toString().toUpperCase();
        });
        assertThat(t.type("ab")).isTrue();
        assertThat(t.type("c!")).isFalse();
        assertThat(t.text().toString()).isEqualTo("AB");
        t.compose("xy", 2);
        assertThat(t.text().toString()).as("composition is not filtered").isEqualTo("ABxy");
        t.type("xy");
        assertThat(t.text().toString()).as("but its commit is").isEqualTo("ABXY");
        t.filter(null);
        t.type("z");
        assertThat(t.text().toString()).isEqualTo("ABXYz");
    }

    @Test
    void compositionSitsInTheTextUntilCommitted() {
        EditableText t = text("ab");
        t.moveTo(1, false);
        t.compose("ka", 2);
        assertThat(t.text().toString()).isEqualTo("akab");
        assertThat(t.composing()).isTrue();
        assertThat(t.compositionStart()).isEqualTo(1);
        assertThat(t.compositionEnd()).isEqualTo(3);
        assertThat(t.compositionCursor()).isEqualTo(2);
        assertThat(t.caret()).isEqualTo(3);
        t.compose("\u304B\u306A", 1);
        assertThat(t.text().toString()).isEqualTo("a\u304B\u306Ab");
        assertThat(t.caret()).isEqualTo(2);
        assertThat(t.canUndo()).as("preedit is outside the history").isFalse();
        t.type("\u4EEE\u540D");
        assertThat(t.text().toString()).isEqualTo("a\u4EEE\u540Db");
        assertThat(t.composing()).isFalse();
        assertThat(t.caret()).isEqualTo(3);
        t.compose("", 0); // the IME's empty composition after the commit: nothing to remove
        assertThat(t.text().toString()).isEqualTo("a\u4EEE\u540Db");
        t.undo();
        assertThat(t.text().toString()).as("the commit undoes as one edit").isEqualTo("ab");

        t.moveTo(2, false);
        t.compose("x", 1);
        t.compose("", 0);
        assertThat(t.text().toString()).as("a cancelled composition leaves nothing").isEqualTo("ab");
        t.select(0, 1);
        t.compose("q", 1);
        assertThat(t.text().toString()).as("composing over a selection replaces it").isEqualTo("qb");
        t.deleteForward(false); // an edit commits the preedit first
        assertThat(t.composing()).isFalse();
        assertThat(t.text().toString()).isEqualTo("q");
        t.compose("z", 5);
        assertThat(t.compositionCursor()).isEqualTo(1);
        t.endComposition(true);
        assertThat(t.text().toString()).isEqualTo("qz");
        t.compose("w", 1);
        t.set("new");
        assertThat(t.composing()).isFalse();
        assertThat(t.text().toString()).isEqualTo("new");
    }

    @Test
    void spansFollowTheEditsAndUndo() {
        EditableText t = new EditableText();
        t.setMarkup("say [b]hello[/b] now");
        assertThat(t.text().toString()).isEqualTo("say hello now");
        t.moveTo(6, false);
        t.type("XX");
        assertThat(t.markup()).as("typing inside a span widens it").isEqualTo("say [b]heXXllo[/b] now");
        t.moveTo(11, false);
        t.type("!");
        assertThat(t.markup()).as("right after it: the preceding character's style").isEqualTo(
            "say [b]heXXllo![/b] now");
        t.moveTo(4, false);
        t.type(">");
        assertThat(t.markup()).as("where it starts: not in it").isEqualTo("say >[b]heXXllo![/b] now");
        t.undo();
        t.undo();
        t.undo();
        assertThat(t.markup()).isEqualTo("say [b]hello[/b] now");
        t.redo();
        assertThat(t.markup()).isEqualTo("say [b]heXXllo[/b] now");
        t.setMarkup("[b]ab[/b]-[b]cd[/b]");
        t.select(2, 3);
        t.deleteBack(false);
        assertThat(t.markup()).as("deleting merges spans that come to touch").isEqualTo("[b]abcd[/b]");
        assertThat(t.spans().count()).isEqualTo(1);
        t.spans().add(0, 2, Spans.Kind.ITALIC, null);
        t.changedSpans();
        assertThat(t.markup()).isEqualTo("[b][i]ab[/i]cd[/b]");
        t.select(0, 2);
        Spans fragment = new Spans();
        t.spans().copy(1, 3, fragment);
        t.moveTo(4, false);
        t.paste("xy", fragment);
        assertThat(t.markup()).isEqualTo("[b][i]ab[/i]cd[i]x[/i]y[/b]");
    }
}
