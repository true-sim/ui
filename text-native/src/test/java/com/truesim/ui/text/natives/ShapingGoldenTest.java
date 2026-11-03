package com.truesim.ui.text.natives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.truesim.ui.text.ShapedGlyphs;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextSystem;
import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * HarfBuzz shaping goldens: glyph ids, clusters, advances and offsets in font units (shaped at a size equal to the
 * font's units per em), over the bundled fonts (ui-fonts, ui-fonts-cjk), so they hold on every machine. Each is pinned
 * to its font file (the key: file, index, size): a changed font fails, and the goldens are made again.
 */
class ShapingGoldenTest {

    private static final float INF = Float.POSITIVE_INFINITY;
    static final String INTER = "Inter-Regular.ttf#0:411640";
    static final String ARABIC = "NotoSansArabic-Regular.ttf#0:234892";
    static final String DEVANAGARI = "NotoSansDevanagari-Regular.ttf#0:243520";
    static final String SC = "NotoSansSC-Regular.otf#0:8331336";
    static final String JP = "NotoSansJP-Regular.otf#0:4533028";

    /** {glyph, cluster, advance, offsetX, offsetY} per glyph. */
    private static List<int[]> shape(NativeFontFace face, String text, boolean rtl, HarfBuzzShaper shaper) {
        ShapedGlyphs out = new ShapedGlyphs();
        shaper.shape(text.toCharArray(), text.length(), 0, text.length(), face, face.unitsPerEm(), rtl, null, out);
        List<int[]> glyphs = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            glyphs.add(new int[] {out.glyph(i), out.cluster(i), Math.round(out.advance(i)),
                Math.round(out.offsetX(i)), Math.round(out.offsetY(i))});
        }
        return glyphs;
    }

    private static List<int[]> shape(NativeFontFace face, String text, boolean rtl) {
        try (HarfBuzzShaper shaper = new HarfBuzzShaper()) {
            return shape(face, text, rtl, shaper);
        }
    }

    @Test
    void kerning() {
        NativeFontFace inter = NativeFonts.golden("Inter", INTER);
        assertThat(inter.unitsPerEm()).isEqualTo(2048);
        assertThat(shape(inter, "AV", false)).containsExactly(new int[] {2, 0, 1273, 0, 0},
            new int[] {456, 1, 1413, 0, 0});
        assertThat(shape(inter, "A", false)).containsExactly(new int[] {2, 0, 1413, 0, 0});
        assertThat(shape(inter, "To", false)).containsExactly(new int[] {411, 0, 1162, 0, 0},
            new int[] {790, 1, 1228, 0, 0});
        assertThat(shape(inter, "Wa", false)).containsExactly(new int[] {459, 0, 1914, 0, 0},
            new int[] {507, 1, 1150, 0, 0});
        try (HarfBuzzShaper noKerning = new HarfBuzzShaper("kern=0")) {
            assertThat(shape(inter, "AV", false, noKerning).get(0)[2]).isEqualTo(1413);
        }
        assertThatThrownBy(() -> new HarfBuzzShaper("not a feature!")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Inter has no fi or ffi ligature (its f is drawn not to collide); its contextual alternates spread "ffi" and
     * join arrows into one glyph, which is the ligature case here: one glyph for several characters.
     */
    @Test
    void ligaturesAndContextualAlternates() {
        NativeFontFace inter = NativeFonts.golden("Inter", INTER);
        assertThat(shape(inter, "fi", false)).containsExactly(new int[] {647, 0, 758, 0, 0},
            new int[] {689, 1, 496, 0, 0});
        assertThat(shape(inter, "ffi", false)).containsExactly(new int[] {647, 0, 678, 0, 0},
            new int[] {647, 1, 758, 0, 0}, new int[] {689, 2, 496, 0, 0});
        assertThat(shape(inter, "a->b", false)).containsExactly(new int[] {507, 0, 1150, 0, 0},
            new int[] {1805, 1, 1954, 0, 0}, new int[] {578, 3, 1254, 0, 0});
        assertThat(shape(inter, "-->", false)).containsExactly(new int[] {1806, 0, 2746, 0, 0});
        try (HarfBuzzShaper noAlternates = new HarfBuzzShaper("calt=0")) {
            assertThat(shape(inter, "->", false, noAlternates)).containsExactly(new int[] {1459, 0, 942, 0, 0},
                new int[] {1517, 1, 1355, 0, 0});
        }
    }

    @Test
    void arabicJoining() {
        NativeFontFace arabic = NativeFonts.golden("Noto Sans Arabic", ARABIC);
        // marhaba, visual order: final alef left, initial meem right; every letter in its joining form, the beh's
        // dot and the hah's form placed by mark positioning
        assertThat(shape(arabic, "مرحبا", true)).containsExactly(
            new int[] {9, 4, 291, 0, 0}, new int[] {316, 3, 0, 94, -3}, new int[] {16, 3, 343, 0, 0},
            new int[] {27, 2, 589, 0, 0}, new int[] {31, 1, 356, -40, 0}, new int[] {79, 0, 525, 0, 0});
        assertThat(arabic.glyph(0x0645)).isEqualTo(74).isNotEqualTo(79); // the isolated meem is another glyph
        try (HarfBuzzShaper noRequired = new HarfBuzzShaper("rlig=0")) {
            assertThat(shape(arabic, "لا", true, noRequired).get(0)[0]).isNotEqualTo(shape(arabic, "لا", true).get(0)[0]);
        }
    }

    @Test
    void devanagariReordering() {
        NativeFontFace devanagari = NativeFonts.golden("Noto Sans Devanagari", DEVANAGARI);
        // ki: the vowel sign i is drawn before the consonant, one cluster
        assertThat(shape(devanagari, "कि", false)).containsExactly(new int[] {545, 0, 259, 0, 0},
            new int[] {56, 0, 768, 0, 0});
        // namaste: s with virama a half form, the vowel sign e a mark on ta
        assertThat(shape(devanagari, "नमस्ते", false)).containsExactly(
            new int[] {75, 0, 555, 0, 0}, new int[] {80, 1, 598, 0, 0}, new int[] {256, 2, 389, 0, 0},
            new int[] {71, 4, 570, 0, 0}, new int[] {40, 4, 0, 0, 0});
    }

    /** CJK falls back to the bundled Noto Sans SC (Han) and JP (kana); emoji has no bundled font: .notdef. */
    @Test
    void cjkFallsBackByScriptAndEmojiHasNoBundledFont() {
        NativeFontFace sc = NativeFonts.golden("Noto Sans SC", SC);
        NativeFontFace jp = NativeFonts.golden("Noto Sans JP", JP);
        assertThat(shape(sc, "你好", false)).containsExactly(new int[] {9112, 0, 1000, 0, 0},
            new int[] {11679, 1, 1000, 0, 0});
        assertThat(shape(jp, "かな", false)).containsExactly(new int[] {1214, 0, 1000, 0, 0},
            new int[] {1245, 1, 1000, 0, 0});
        TextSystem system = new TextSystem(NativeFonts.REGISTRY, new HarfBuzzShaper(), null);
        TextBlock b = new TextBlock(system);
        b.set("Hi 你好 かな", NativeFonts.LATIN, 16, 400, false, TextAlign.START, INF, 0);
        assertThat(b.glyphCount()).isEqualTo(6);
        assertThat(b.glyphId(0)).isEqualTo(161);
        assertThat(b.glyphFace(2)).isSameAs(sc);
        assertThat(b.glyphId(2)).isEqualTo(9112);
        assertThat(b.glyphFace(4)).isSameAs(jp);
        assertThat(b.glyphId(5)).isEqualTo(1245);
        // Emoji: no colour font ships (docs/ui.md); with only bundled fonts it is Inter's .notdef. A machine's emoji
        // font is used when the registry includes installed fonts (FontRegistry.system), not in these goldens.
        b.set("a😀", NativeFonts.LATIN, 16, 400, false, TextAlign.START, INF, 0);
        assertThat(b.glyphId(1)).isZero();
    }

    @Test
    void mixedDirectionsInVisualOrder() {
        List<String> latin = NativeFonts.LATIN;
        NativeFontFace face = NativeFonts.face(latin.get(0));
        NativeFontFace arabic = NativeFonts.golden("Noto Sans Arabic", ARABIC);
        // alef, dal, reh join no letter after them: each is its isolated form, the glyph shaped alone
        int alef = shape(arabic, "ا", true).get(0)[0];
        int dal = shape(arabic, "د", true).get(0)[0];
        int reh = shape(arabic, "ر", true).get(0)[0];
        TextBlock b = new TextBlock(new TextSystem(NativeFonts.REGISTRY, new HarfBuzzShaper(), null));
        b.set("abc ادر def", latin, 20, 400, false, TextAlign.START, INF, 0);
        assertThat(b.lineRightToLeft(0)).isFalse();
        int[] visual = new int[b.glyphCount()];
        for (int i = 0; i < visual.length; i++) {
            visual[i] = b.glyphId(i);
        }
        assertThat(visual).containsExactly(face.glyph('a'), face.glyph('b'), face.glyph('c'), reh, dal, alef,
            face.glyph('d'), face.glyph('e'), face.glyph('f'));
        assertThat(b.glyphFace(3)).isSameAs(arabic);
        assertThat(b.caretX(4)).isGreaterThan(b.caretX(5)); // alef right of dal
        assertThat(b.caretX(8)).isGreaterThan(b.caretX(4));

        b.set("ادر 123 abc!", latin, 20, 400, false, TextAlign.START, INF, 0);
        assertThat(b.lineRightToLeft(0)).isTrue();
        // visual, left to right: "!abc 123 rda" with the exclamation mark at the left end, alef at the right
        assertThat(b.glyphId(0)).isEqualTo(face.glyph('!'));
        assertThat(b.glyphId(1)).isEqualTo(face.glyph('a'));
        // the digits are common characters: they join the Arabic run and take its face
        assertThat(b.glyphFace(4)).isSameAs(arabic);
        assertThat(b.glyphId(4)).isEqualTo(arabic.glyph('1'));
        assertThat(b.glyphId(b.glyphCount() - 1)).isEqualTo(alef);
    }

    @Test
    void linesBreakAtOpportunitiesAndCaretsSplitLigatures() {
        List<String> latin = NativeFonts.LATIN;
        TextBlock b = new TextBlock(new TextSystem(NativeFonts.REGISTRY, new HarfBuzzShaper(), null));
        String text = "The quick brown fox jumps over the lazy dog.";
        b.set(text, latin, 16, 400, false, TextAlign.START, 120, 0);
        assertThat(b.lineCount()).isBetween(3, 5);
        for (int l = 0; l < b.lineCount(); l++) {
            assertThat(b.lineWidth(l)).isLessThanOrEqualTo(120);
            int start = b.lineStart(l);
            assertThat(start == 0 || text.charAt(start - 1) == ' ').as("line %d starts a word", l).isTrue();
        }
        assertThat(b.lineEnd(b.lineCount() - 1)).isEqualTo(text.length());

        List<String> cjk = List.of("Noto Sans SC");
        b.set("你好你好你好", cjk, 20, 400, false, TextAlign.START, 45, 0);
        assertThat(b.lineCount()).isEqualTo(3); // ideographs break anywhere: two per line

        NativeFontFace inter = NativeFonts.golden("Inter", INTER);
        b.set("a-->b", NativeFonts.LATIN, 2048, 400, false, TextAlign.START, INF, 0);
        assertThat(b.glyphFace(1)).isSameAs(inter);
        assertThat(b.glyphCount()).isEqualTo(3);
        assertThat(b.caretX(1)).isEqualTo(1150);
        assertThat(b.caretX(2)).isCloseTo(1150 + 2746 / 3f, offset(0.01f)); // inside the arrow's one glyph
        assertThat(b.caretX(3)).isCloseTo(1150 + 2 * 2746 / 3f, offset(0.01f));
        assertThat(b.caretX(4)).isEqualTo(1150 + 2746);
        assertThat(b.hitTest(1150 + 2746 / 3f + 10, b.baseline())).isEqualTo(2);
        assertThat(b.caretX(5)).isEqualTo(b.width());

        List<String> arabic = List.of("Noto Sans Arabic");
        b.set("مرحبا", arabic, 32, 400, false, TextAlign.START, INF, 0);
        assertThat(b.caretX(0)).isEqualTo(b.width()); // right to left: the start is at the right
        assertThat(b.caretX(5)).isZero();
        assertThat(b.caretX(2)).isLessThan(b.caretX(1));
    }

    @Test
    void contextAndLanguageReachHarfBuzz() throws Exception {
        NativeFontFace face = NativeFonts.golden("Noto Sans Arabic", ARABIC);
        assertThat(face.covers(0x0628)).isTrue();
        String text = "ببب"; // three beh: initial, medial, final
        try (HarfBuzzShaper shaper = new HarfBuzzShaper()) {
            ShapedGlyphs whole = new ShapedGlyphs();
            shaper.shape(text.toCharArray(), 3, 0, 3, face, 32, true, "ar", whole);
            ShapedGlyphs middle = new ShapedGlyphs();
            shaper.shape(text.toCharArray(), 3, 1, 2, face, 32, true, "ar", middle);
            // Noto Sans Arabic draws a beh as its dotless form plus the dot as a mark: two glyphs per letter
            List<Integer> wholeMiddle = new ArrayList<>();
            for (int i = 0; i < whole.size(); i++) {
                if (whole.cluster(i) == 1) {
                    wholeMiddle.add(whole.glyph(i));
                }
            }
            List<Integer> alone = new ArrayList<>();
            for (int i = 0; i < middle.size(); i++) {
                assertThat(middle.cluster(i)).isEqualTo(1);
                alone.add(middle.glyph(i));
            }
            assertThat(alone).isEqualTo(wholeMiddle).hasSize(2); // still the medial form: context counts
            shaper.shape(text.toCharArray(), 3, 0, 3, face, 32, true, "fa", whole); // another language
            char[] longText = new char[2000];
            java.util.Arrays.fill(longText, 'ب');
            ShapedGlyphs many = new ShapedGlyphs();
            shaper.shape(longText, 2000, 0, 2000, face, 32, true, null, many); // grows the native text
            assertThat(many.size()).isEqualTo(4000);
            AutoCloseable twice = shaper;
            twice.close();
        }
        assertThat(UnicodeScript.of(0x0628)).isEqualTo(UnicodeScript.ARABIC);
    }
}
