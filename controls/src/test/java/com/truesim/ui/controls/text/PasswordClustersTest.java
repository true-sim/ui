package com.truesim.ui.controls.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.input.UiCommand;
import org.junit.jupiter.api.Test;

/**
 * Passwords mask per extended grapheme cluster: one mask for an emoji ZWJ sequence, a flag, a letter with combining
 * marks, a skin-toned emoji; the caret steps, clicks and Backspace go by clusters.
 */
class PasswordClustersTest {

    /** e + combining acute; family (man ZWJ woman ZWJ girl); flag DE; thumbs up + skin tone; a; o + two marks. */
    static final String TEXT = "é" + "👨‍👩‍👧" + "🇩🇪"
        + "👍🏽" + "a" + "ọ̈";

    @Test
    void eachClusterShowsOneMask() {
        TextRig r = new TextRig();
        PasswordField pw = PasswordField.create(r.ui, null, "Password", "Show");
        r.add(pw.element()).frame();
        pw.editor().text(TEXT);
        r.settle();
        assertThat(r.glyphs()).as("six clusters, six masks").isEqualTo(6);
        Slice s = new Slice().set("ab́", 0, 3, '*');
        assertThat(s.toString()).as("per code point without cluster starts").isEqualTo("***");
        s.clusters(new boolean[] {true, true, false});
        assertThat(s.toString()).isEqualTo("**​");
        assertThat(s.subSequence(1, 3).toString()).isEqualTo("*​");
        assertThat(s.length()).isEqualTo(3);
    }

    @Test
    void theCaretAndBackspaceMoveByClusters() {
        TextRig r = new TextRig();
        PasswordField pw = PasswordField.create(r.ui, null, "Password", "Show");
        r.add(pw.element()).frame();
        pw.editor().focus();
        r.frame();
        r.type(TEXT);
        r.settle();
        var text = pw.editor().editable();
        assertThat(text.caret()).isEqualTo(TEXT.length());
        int[] stops = {TEXT.length() - 3, TEXT.length() - 4, TEXT.length() - 8, TEXT.length() - 12, 2, 0};
        for (int stop : stops) {
            r.press(UiCommand.LEFT);
            assertThat(text.caret()).isEqualTo(stop);
        }
        r.press(UiCommand.RIGHT, UiCommand.RIGHT);
        assertThat(text.caret()).as("over the family at once").isEqualTo(10);
        r.press(UiCommand.DELETE_BACK);
        assertThat(pw.text().toString()).as("the whole family goes").isEqualTo(TEXT.substring(0, 2) + TEXT.substring(10));
        r.press(UiCommand.DOC_END, UiCommand.DELETE_BACK);
        assertThat(pw.text().toString()).as("o and both marks go").endsWith("a");
        r.settle();
        float[] box = r.box(pw.editor().element());
        for (float x = box[0]; x < box[2]; x += 2) { // a click never puts the caret inside a cluster
            r.click(x, (box[1] + box[3]) / 2);
            int c = text.caret();
            assertThat(c == 0 || text.nextGrapheme(text.previousGrapheme(c)) == c).as("caret %d", c).isTrue();
        }
        pw.revealed(true);
        r.press(UiCommand.DOC_END, UiCommand.DELETE_BACK);
        assertThat(pw.text().toString()).as("revealed: still one cluster").doesNotEndWith("a");
    }
}
