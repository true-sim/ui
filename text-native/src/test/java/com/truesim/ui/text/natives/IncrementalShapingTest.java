package com.truesim.ui.text.natives;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextDirection;
import com.truesim.ui.text.TextShaper;
import com.truesim.ui.text.TextSystem;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Incremental reshaping with HarfBuzz equals shaping from scratch: random edits of long mixed-script paragraphs (Latin
 * with kerning and ligatures, Arabic joining, CJK, Devanagari conjuncts, marks, emoji), each compared glyph by glyph,
 * line by line and caret by caret with a block shaped from scratch. Runs are split where HarfBuzz's glyphs carry
 * neither unsafe flag.
 */
class IncrementalShapingTest {

    static final String[] WORDS = {"Lorem", "ipsum", "dolor", "sit", "amet,", "office", "AVATAR", "Wolf", "fi",
        "42", "3.14", "(note)", "«quote»", "مرحبا", "بالعالم", "سلام", "١٢٣", "你好", "世界", "日本語", "नमस्ते",
        "दुनिया", "é", "a-b", "x/y", "—", "!?", "\t"};
    /** Words without brackets: BiDi levels are resolved again between the strong characters around an edit. */
    static final String[] PLAIN = {"Lorem", "ipsum", "dolor,", "office", "AV", "42", "3.14", "مرحبا", "بالعالم",
        "١٢٣", "你好", "नमस्ते", "é", "a-b", "$5", "—", "!?"};
    static final String PIECES = " ab مر 你 न 1(é)";

    /** A paragraph of random words up to about {@code length} characters, with line feeds if {@code paragraphs}. */
    static String paragraph(Random random, String[] words, int length, boolean paragraphs) {
        StringBuilder s = new StringBuilder();
        while (s.length() < length) {
            s.append(words[random.nextInt(words.length)]);
            s.append(paragraphs && random.nextInt(60) == 0 ? '\n' : ' ');
        }
        return s.toString();
    }

    /** A random edit of {@code text}: typing, deleting, replacing, pasting, now and then a line feed. */
    static void edit(Random random, String[] words, StringBuilder text) {
        int at = random.nextInt(text.length() + 1);
        switch (random.nextInt(10)) {
            case 0, 1, 2, 3 -> text.insert(at, PIECES.charAt(random.nextInt(words == WORDS ? PIECES.length() : 9)));
            case 4, 5 -> {
                if (at < text.length()) {
                    text.deleteCharAt(at);
                }
            }
            case 6 -> text.insert(at, words[random.nextInt(words.length)] + " ");
            case 7 -> text.replace(at, Math.min(text.length(), at + random.nextInt(12)),
                words[random.nextInt(words.length)]);
            case 8 -> text.delete(at, Math.min(text.length(), at + random.nextInt(40)));
            default -> text.insert(at, random.nextInt(20) == 0 ? "\n" : "é");
        }
    }

    /** Asserts that {@code actual} lays out exactly as {@code expected}. */
    static void assertSame(TextBlock actual, TextBlock expected, String what) {
        assertThat(actual.length()).as(what).isEqualTo(expected.length());
        assertThat(actual.lineCount()).as(what + ": lines").isEqualTo(expected.lineCount());
        assertThat(actual.glyphCount()).as(what + ": glyphs").isEqualTo(expected.glyphCount());
        assertThat(actual.width()).as(what).isEqualTo(expected.width());
        assertThat(actual.height()).as(what).isEqualTo(expected.height());
        for (int l = 0; l < expected.lineCount(); l++) {
            String line = what + ": line " + l;
            assertThat(actual.lineStart(l)).as(line).isEqualTo(expected.lineStart(l));
            assertThat(actual.lineEnd(l)).as(line).isEqualTo(expected.lineEnd(l));
            assertThat(actual.lineGlyphStart(l)).as(line).isEqualTo(expected.lineGlyphStart(l));
            assertThat(actual.lineGlyphEnd(l)).as(line).isEqualTo(expected.lineGlyphEnd(l));
            assertThat(actual.lineLeft(l)).as(line).isEqualTo(expected.lineLeft(l));
            assertThat(actual.lineWidth(l)).as(line).isEqualTo(expected.lineWidth(l));
            assertThat(actual.lineBaseline(l)).as(line).isEqualTo(expected.lineBaseline(l));
            assertThat(actual.lineTop(l)).as(line).isEqualTo(expected.lineTop(l));
            assertThat(actual.lineHeight(l)).as(line).isEqualTo(expected.lineHeight(l));
            assertThat(actual.lineRightToLeft(l)).as(line).isEqualTo(expected.lineRightToLeft(l));
        }
        for (int g = 0; g < expected.glyphCount(); g++) {
            String glyph = what + ": glyph " + g;
            assertThat(actual.glyphId(g)).as(glyph).isEqualTo(expected.glyphId(g));
            assertThat(actual.glyphFace(g)).as(glyph).isSameAs(expected.glyphFace(g));
            assertThat(actual.glyphX(g)).as(glyph).isEqualTo(expected.glyphX(g));
            assertThat(actual.glyphY(g)).as(glyph).isEqualTo(expected.glyphY(g));
            assertThat(actual.glyphCluster(g)).as(glyph).isEqualTo(expected.glyphCluster(g));
            assertThat(actual.glyphSize(g)).as(glyph).isEqualTo(expected.glyphSize(g));
        }
        for (int i = 0; i < expected.length(); i++) {
            String c = what + ": character " + i;
            assertThat(actual.rightToLeft(i)).as(c).isEqualTo(expected.rightToLeft(i));
            assertThat(Float.floatToIntBits(actual.charLeft(i))).as(c)
                .isEqualTo(Float.floatToIntBits(expected.charLeft(i)));
            assertThat(Float.floatToIntBits(actual.charRight(i))).as(c)
                .isEqualTo(Float.floatToIntBits(expected.charRight(i)));
        }
        for (int i = 0; i <= expected.length(); i += 7) {
            assertThat(actual.caretX(i)).as(what + ": caret " + i).isEqualTo(expected.caretX(i));
        }
    }

    /**
     * Edits a {@code length}-character text {@code edits} times with {@code shaper}, comparing each result with a block
     * shaped from scratch; returns how many edits were incremental.
     */
    static long check(TextShaper shaper, FontRegistry fonts, List<String> families, String[] words, long seed,
        int length, int edits, float maxWidth, TextAlign align, TextDirection direction, boolean paragraphs) {
        TextSystem system = new TextSystem(fonts, shaper, null);
        Random random = new Random(seed);
        StringBuilder text = new StringBuilder(paragraph(random, words, length, paragraphs));
        TextBlock block = new TextBlock(system);
        block.set(text, families, 14, 400, false, align, maxWidth, 0, direction, null);
        for (int e = 0; e < edits; e++) {
            edit(random, words, text);
            block.set(text, families, 14, 400, false, align, maxWidth, 0, direction, null);
            TextBlock fresh = new TextBlock(system);
            fresh.incremental(false);
            fresh.set(text, families, 14, 400, false, align, maxWidth, 0, direction, null);
            assertSame(block, fresh, "seed " + seed + ", edit " + e);
        }
        return block.incrementalEdits();
    }

    @Test
    void randomEditsOfLongMixedParagraphsEqualShapingFromScratch() {
        FontRegistry fonts = NativeFonts.REGISTRY;
        List<String> latin = NativeFonts.LATIN;
        try (HarfBuzzShaper shaper = new HarfBuzzShaper()) {
            long incremental = check(shaper, fonts, latin, WORDS, 11, 3000, 300, 400, TextAlign.START, TextDirection.AUTO,
                false);
            assertThat(incremental).isGreaterThan(200);
            check(shaper, fonts, latin, WORDS, 12, 1500, 150, 300, TextAlign.CENTER, TextDirection.AUTO, true);
            check(shaper, fonts, latin, WORDS, 13, 1000, 100, Float.POSITIVE_INFINITY, TextAlign.END, TextDirection.AUTO,
                true);
            check(shaper, fonts, latin, WORDS, 14, 1500, 150, 250, TextAlign.START, TextDirection.RTL, false);
            check(shaper, fonts, latin, WORDS, 15, 1000, 100, 50, TextAlign.START, TextDirection.LTR, false);
            check(shaper, fonts, List.of("Noto Sans Arabic"), WORDS, 16, 1500, 150, 300, TextAlign.START, TextDirection.AUTO,
                false);
            check(shaper, fonts, latin, PLAIN, 17, 3000, 200, 400, TextAlign.START, TextDirection.AUTO, false);
            check(shaper, fonts, latin, PLAIN, 18, 1500, 150, 300, TextAlign.START, TextDirection.RTL, true);
        }
    }
}
