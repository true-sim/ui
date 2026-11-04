package com.truesim.ui.controls.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.Menu;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.text.spell.Dictionary;
import com.truesim.ui.text.spell.SpellChecker;
import org.junit.jupiter.api.Test;

/** Spell checking in the editor: squiggles, incremental and budgeted rechecks, the spelling menu. */
class SpellEditorTest {

    static final Dictionary SAMPLE = Dictionary.sampleEnglish();

    private static int lines(TextRig r) {
        DrawList list = r.ui.layers()[0];
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.LINE) {
                n++;
            }
        }
        return n;
    }

    private static String word(TextEditor e, int[] range) {
        return e.text().subSequence(range[0], range[1]).toString();
    }

    @Test
    void misspeltWordsGetSquigglesAndOnlyChangedParagraphsAreCheckedAgain() {
        TextRig r = new TextRig();
        SpellChecker checker = new SpellChecker(SAMPLE);
        TextEditor area = TextEditor.area(r.ui, null, "Notes").spelling(checker, null);
        r.ui.styles().inline(area.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(500))
            .set(Styles.HEIGHT, Length.px(300)).build());
        area.text("the wrod is here\nall good\nanother mistak");
        r.add(area.element()).settle();
        assertThat(area.misspelt()).extracting(range -> word(area, range)).containsExactly("wrod", "mistak");
        assertThat(lines(r)).as("two squiggles of segments").isGreaterThan(4);
        int checks = area.spellChecks();
        area.focus();
        r.frame();
        r.press(UiCommand.DOC_START, UiCommand.LINE_END);
        r.type(" ok");
        r.settle();
        assertThat(area.spellChecks() - checks).as("only the edited paragraph").isEqualTo(1);
        r.press(UiCommand.DOC_END);
        r.type(" mor");
        r.settle();
        assertThat(area.misspelt()).extracting(range -> word(area, range)).contains("mor");
        int squiggles = lines(r);
        r.type("e");
        r.settle();
        assertThat(area.misspelt()).extracting(range -> word(area, range)).doesNotContain("more", "mor");
        r.type(" xyzzy");
        r.settle();
        assertThat(area.misspelt()).extracting(range -> word(area, range)).contains("xyzzy");
        assertThat(lines(r)).as("the word being typed is not marked yet").isLessThanOrEqualTo(squiggles);
        r.press(UiCommand.NEWLINE);
        r.settle();
        assertThat(lines(r)).as("left behind: marked").isGreaterThan(squiggles);
        checker.addToDictionary("xyzzy");
        r.settle();
        assertThat(area.misspelt()).extracting(range -> word(area, range)).doesNotContain("xyzzy");
        assertThat(area.spellPending()).isFalse();
        area.spelling(null, null);
        r.settle();
        assertThat(lines(r)).isZero();
    }

    @Test
    void aLargeTextIsCheckedOverSeveralFramesWithinTheBudget() {
        TextRig r = new TextRig();
        float budget = TextEditor.SPELL_BUDGET.get();
        TextEditor.SPELL_BUDGET.set(0.01f);
        try {
            TextEditor area = TextEditor.area(r.ui, null, "Big").spelling(new SpellChecker(SAMPLE), null);
            r.ui.styles().inline(area.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(400))
                .set(Styles.HEIGHT, Length.px(200)).build());
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 2_000; i++) {
                text.append(i % 100 == 99 ? "a wrng word in line\n" : "all is fine in this line\n");
            }
            area.text(text);
            r.add(area.element()).frame().frame();
            assertThat(area.spellPending()).as("not all at once").isTrue();
            int frames = 0;
            while (area.spellPending() && frames < 10_000) {
                r.frame();
                frames++;
            }
            assertThat(frames).isGreaterThan(1);
            assertThat(area.spellPending()).isFalse();
            assertThat(area.misspelt()).hasSize(20);
            int checks = area.spellChecks();
            r.frame().frame();
            assertThat(area.spellChecks()).as("nothing more to do").isEqualTo(checks);
        } finally {
            TextEditor.SPELL_BUDGET.set(budget);
        }
    }

    @Test
    void theSpellingMenuReplacesAddsAndIgnores() {
        TextRig r = new TextRig();
        Controls controls = new Controls(r.ui);
        SpellChecker checker = new SpellChecker(SAMPLE);
        TextEditor field = TextEditor.field(r.ui, null, "Title").spelling(checker, controls)
            .spellMenuText("Add", "Ignore", "None");
        field.text("a wrod and a qqqqqqqqzz");
        r.add(field.element()).settle();
        field.focus();
        r.frame();
        field.editable().moveTo(3, false);
        r.press(UiCommand.CONTEXT_MENU);
        r.settle();
        Menu menu = field.spellMenu();
        assertThat(menu.isOpen()).isTrue();
        assertThat(r.ui.store().label(menu.item(0).element().slot()).toString()).isEqualTo("word");
        float[] item = r.box(menu.item(0).element());
        r.click((item[0] + item[2]) / 2, (item[1] + item[3]) / 2);
        r.settle();
        assertThat(field.text().toString()).isEqualTo("a word and a qqqqqqqqzz");
        assertThat(menu.isOpen()).isFalse();
        field.editable().moveTo(20, false);
        r.press(UiCommand.CONTEXT_MENU);
        r.settle();
        assertThat(r.ui.store().label(menu.item(0).element().slot()).toString()).isEqualTo("None");
        int add = menu.count() - 2;
        item = r.box(menu.item(add).element());
        r.click((item[0] + item[2]) / 2, (item[1] + item[3]) / 2);
        r.settle();
        assertThat(checker.userDictionary()).containsExactly("qqqqqqqqzz");
        assertThat(field.misspelt()).isEmpty();
        field.text("wrod");
        r.settle();
        float[] box = r.box(field.element());
        r.mouse(box[0] + 8, (box[1] + box[3]) / 2, com.truesim.ui.core.input.Pointer.SECONDARY);
        r.mouse(box[0] + 8, (box[1] + box[3]) / 2, 0);
        r.settle();
        assertThat(menu.isOpen()).as("a right click on the word").isTrue();
        item = r.box(menu.item(menu.count() - 1).element());
        r.click((item[0] + item[2]) / 2, (item[1] + item[3]) / 2);
        r.settle();
        assertThat(field.misspelt()).as("ignored").isEmpty();
        field.text("fine words");
        r.settle();
        field.editable().moveTo(2, false);
        r.press(UiCommand.CONTEXT_MENU);
        r.settle();
        assertThat(menu.isOpen()).as("no menu on a right word").isFalse();
        PasswordField pw = PasswordField.create(r.ui, null, "Password", "Show");
        pw.editor().spelling(checker, controls).text("wrod");
        r.add(pw.element()).settle();
        assertThat(pw.editor().misspelt()).as("passwords are never checked").isEmpty();
    }
}
