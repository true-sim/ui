package com.truesim.ui.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.draw.UiTextures;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Incremental reshaping equals shaping from scratch: random edits of long mixed-script paragraphs, each compared glyph
 * by glyph, line by line and caret by caret with a block shaped from scratch, with the JDK shaper (no unsafe flags:
 * splits after white space) and with a shaper that marks flags.
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
        FontRegistry fonts = TestFonts.registry();
        TextShaper jdk = TestFonts.BACKEND.newShaper();
        List<String> latin = TestFonts.latin();
        long incremental = check(jdk, fonts, latin, WORDS, 1, 1500, 150, 300, TextAlign.START, TextDirection.AUTO, false);
        assertThat(incremental).isGreaterThan(100);
        check(jdk, fonts, latin, WORDS, 2, 900, 80, 250, TextAlign.CENTER, TextDirection.AUTO, true);
        check(jdk, fonts, latin, WORDS, 3, 600, 60, Float.POSITIVE_INFINITY, TextAlign.END, TextDirection.AUTO, true);
        check(jdk, fonts, latin, WORDS, 4, 900, 80, 200, TextAlign.START, TextDirection.RTL, false);
        check(jdk, fonts, latin, WORDS, 5, 900, 60, 40, TextAlign.START, TextDirection.LTR, false); // words split
        check(jdk, fonts, latin, PLAIN, 9, 1500, 150, 300, TextAlign.START, TextDirection.AUTO, false);
        check(jdk, fonts, latin, PLAIN, 10, 900, 100, 250, TextAlign.END, TextDirection.RTL, true);
    }

    @Test
    void aShaperWithUnsafeFlagsSplitsRunsWhereTheyAreClear() {
        FontRegistry fonts = TestFonts.registry();
        TextShaper flagged = new Flagging(TestFonts.BACKEND.newShaper());
        long incremental = check(flagged, fonts, TestFonts.latin(), WORDS, 6, 1500, 120, 300, TextAlign.START,
            TextDirection.AUTO, false);
        assertThat(incremental).isGreaterThan(80);
        check(flagged, fonts, TestFonts.latin(), WORDS, 7, 900, 80, 250, TextAlign.START, TextDirection.RTL, true);
    }

    @Test
    void shortTextAndOtherChangesShapeFromScratch() {
        TextSystem system = new TextSystem(TestFonts.registry(), TestFonts.BACKEND.newShaper(),
            TestFonts.system(new UiTextures(), new AtlasSettings()).atlas());
        TextBlock block = new TextBlock(system);
        assertThat(block.incremental()).isTrue();
        String text = paragraph(new Random(8), WORDS, 400, false);
        block.set(text, TestFonts.latin(), 14, 400, false, TextAlign.START, 300, 0);
        block.set(text + "x", TestFonts.latin(), 14, 400, false, TextAlign.START, 300, 0);
        assertThat(block.incrementalEdits()).isOne();
        block.set(text + "xy", TestFonts.latin(), 15, 400, false, TextAlign.START, 300, 0); // the size changed
        block.set(text + "xyz", TestFonts.latin(), 15, 400, false, TextAlign.START, 310, 0); // the width changed
        block.set("short", TestFonts.latin(), 15, 400, false, TextAlign.START, 310, 0);
        block.set("shorter", TestFonts.latin(), 15, 400, false, TextAlign.START, 310, 0);
        assertThat(block.incrementalEdits()).isOne();
        // the paragraph's direction changes: from scratch, the same result
        String arabic = "مرحبا " + text;
        block.set(arabic, TestFonts.latin(), 15, 400, false, TextAlign.START, 310, 0);
        block.set("a" + arabic, TestFonts.latin(), 15, 400, false, TextAlign.START, 310, 0);
        TextBlock fresh = new TextBlock(system);
        fresh.set("a" + arabic, TestFonts.latin(), 15, 400, false, TextAlign.START, 310, 0);
        assertSame(block, fresh, "direction");
    }

    /**
     * The JDK shaper with flags as HarfBuzz gives them: a glyph is unsafe to break and concatenate unless white space
     * comes before its cluster; and every 13th cluster is unsafe anyway, and so is the first of every segment shaped
     * apart starting at a multiple of five (so that splitting is refused and retried).
     */
    static final class Flagging implements TextShaper {

        private final TextShaper shaper;
        private final ShapedGlyphs scratch = new ShapedGlyphs();

        Flagging(TextShaper shaper) {
            this.shaper = shaper;
        }

        @Override
        public void shape(char[] text, int length, int start, int end, FontFace face, float sizePx,
            boolean rightToLeft, String language, ShapedGlyphs out) {
            shape(text, length, start, end, start, end, face, sizePx, rightToLeft, language, out);
        }

        @Override
        public void shape(char[] text, int length, int runStart, int runEnd, int start, int end, FontFace face,
            float sizePx, boolean rightToLeft, String language, ShapedGlyphs out) {
            scratch.clear();
            shaper.shape(text, length, start, end, face, sizePx, rightToLeft, language, scratch);
            for (int i = 0; i < scratch.size(); i++) {
                int c = scratch.cluster(i);
                boolean unsafe = c > 0 && !Character.isWhitespace(text[c - 1]) || c % 13 == 0
                    || c == start && start > runStart && start % 5 == 0;
                out.add(scratch.glyph(i), c, scratch.advance(i), scratch.offsetX(i), scratch.offsetY(i),
                    unsafe ? ShapedGlyphs.UNSAFE_TO_BREAK | ShapedGlyphs.UNSAFE_TO_CONCAT : 0);
            }
        }

        @Override
        public boolean marksUnsafeBreaks() {
            return true;
        }
    }
}
