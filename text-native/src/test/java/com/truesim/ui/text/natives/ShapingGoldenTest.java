package com.truesim.ui.text.natives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * font's units per em). Each is pinned to the font file it was made with, from Windows 11 (the key: file, index,
 * size); elsewhere it is skipped.
 */
class ShapingGoldenTest {

    private static final float INF = Float.POSITIVE_INFINITY;

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
        NativeFontFace arial = NativeFonts.golden("Arial", "arial.ttf#0:1045720");
        assertThat(shape(arial, "AV", false)).containsExactly(new int[] {36, 0, 1214, 0, 0},
            new int[] {57, 1, 1366, 0, 0});
        assertThat(shape(arial, "A", false)).containsExactly(new int[] {36, 0, 1366, 0, 0});
        assertThat(shape(arial, "To", false)).containsExactly(new int[] {55, 0, 1024, 0, 0},
            new int[] {82, 1, 1139, 0, 0});
        assertThat(shape(arial, "Wa", false)).containsExactly(new int[] {58, 0, 1857, 0, 0},
            new int[] {68, 1, 1139, 0, 0});
        try (HarfBuzzShaper noKerning = new HarfBuzzShaper("kern=0")) {
            assertThat(shape(arial, "AV", false, noKerning).get(0)[2]).isEqualTo(1366);
        }
        assertThatThrownBy(() -> new HarfBuzzShaper("not a feature!")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ligatures() {
        NativeFontFace calibri = NativeFonts.golden("Calibri", "calibri.ttf#0:1650632");
        assertThat(shape(calibri, "fi", false)).containsExactly(new int[] {302, 0, 1084, 0, 0});
        assertThat(shape(calibri, "ffi", false)).containsExactly(new int[] {312, 0, 1654, 0, 0});
        assertThat(shape(calibri, "office", false)).containsExactly(new int[] {381, 0, 1080, 0, 0},
            new int[] {312, 1, 1654, 0, 0}, new int[] {272, 4, 866, 0, 0}, new int[] {286, 5, 1019, 0, 0});
        try (HarfBuzzShaper noLigatures = new HarfBuzzShaper("liga=0")) {
            assertThat(shape(calibri, "fi", false, noLigatures)).hasSize(2);
        }
    }

    @Test
    void arabicJoining() {
        NativeFontFace tahoma = NativeFonts.golden("Tahoma", "tahoma.ttf#0:919260");
        // marhaba, visual order: final alef left, initial meem right; every letter in its joining form
        assertThat(shape(tahoma, "مرحبا", true)).containsExactly(
            new int[] {910, 4, 606, 0, 0}, new int[] {914, 3, 719, 0, 0}, new int[] {931, 2, 1350, 0, 0},
            new int[] {942, 1, 854, 0, 0}, new int[] {995, 0, 1044, 0, 0});
        assertThat(tahoma.glyph(0x0645)).isNotEqualTo(995); // the isolated meem is another glyph
    }

    @Test
    void devanagariReordering() {
        NativeFontFace nirmala = NativeFonts.golden("Nirmala UI", "Nirmala.ttc#0:5304988");
        // ki: the vowel sign i is drawn before the consonant, one cluster
        assertThat(shape(nirmala, "कि", false)).containsExactly(new int[] {302, 0, 532, 0, 0},
            new int[] {248, 0, 1768, 0, 0});
        // namaste: the conjunct s+t one glyph, the vowel sign e a mark on it
        assertThat(shape(nirmala, "नमस्ते", false)).containsExactly(
            new int[] {267, 0, 1244, 0, 0}, new int[] {273, 1, 1379, 0, 0}, new int[] {721, 2, 2115, 0, 0},
            new int[] {312, 2, 0, -57, 0});
    }

    @Test
    void cjkAndEmojiFallBackByCoverage() {
        List<String> latin = NativeFonts.latin();
        NativeFontFace yahei = NativeFonts.golden("Microsoft YaHei", "msyh.ttc#0:19704352");
        assertThat(shape(yahei, "你好", false)).containsExactly(new int[] {1154, 0, 2048, 0, 0},
            new int[] {1521, 1, 2048, 0, 0});
        TextSystem system = new TextSystem(NativeFonts.REGISTRY, new HarfBuzzShaper(), null);
        TextBlock b = new TextBlock(system);
        b.set("Hi 你好 😀", latin, 16, 400, false, TextAlign.START, INF, 0);
        assertThat(b.glyphCount()).isEqualTo(5);
        assertThat(b.glyphFace(2)).isSameAs(yahei);
        assertThat(b.glyphId(2)).isEqualTo(1154);
        NativeFontFace emoji = NativeFonts.golden("Segoe UI Emoji", "seguiemj.ttf#0:12934920");
        assertThat(b.glyphFace(4)).isSameAs(emoji);
        assertThat(b.glyphId(4)).isEqualTo(2317);
    }

    @Test
    void mixedDirectionsInVisualOrder() {
        List<String> latin = NativeFonts.family("Segoe UI", "Arial", "DejaVu Sans");
        NativeFontFace face = NativeFonts.face(latin.get(0));
        TextBlock b = new TextBlock(new TextSystem(NativeFonts.REGISTRY, new HarfBuzzShaper(), null));
        b.set("abc אבג def", latin, 20, 400, false, TextAlign.START, INF, 0);
        assertThat(b.lineRightToLeft(0)).isFalse();
        int[] visual = new int[b.glyphCount()];
        for (int i = 0; i < visual.length; i++) {
            visual[i] = b.glyphId(i);
        }
        assertThat(visual).containsExactly(face.glyph('a'), face.glyph('b'), face.glyph('c'), face.glyph(0x5D2),
            face.glyph(0x5D1), face.glyph(0x5D0), face.glyph('d'), face.glyph('e'), face.glyph('f'));
        assertThat(b.caretX(4)).isGreaterThan(b.caretX(5)); // alef right of bet
        assertThat(b.caretX(8)).isGreaterThan(b.caretX(4));

        b.set("שלום 123 abc!", latin, 20, 400, false, TextAlign.START, INF, 0);
        assertThat(b.lineRightToLeft(0)).isTrue();
        // visual, left to right: "abc! 123 םולש" with the exclamation mark at the left end
        assertThat(b.glyphId(0)).isEqualTo(face.glyph('!'));
        assertThat(b.glyphId(1)).isEqualTo(face.glyph('a'));
        assertThat(b.glyphId(4)).isEqualTo(face.glyph('1'));
        assertThat(b.glyphId(b.glyphCount() - 1)).isEqualTo(face.glyph(0x05E9));
    }

    @Test
    void linesBreakAtOpportunitiesAndCaretsSplitLigatures() {
        List<String> latin = NativeFonts.latin();
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

        List<String> cjk = NativeFonts.family("Microsoft YaHei", "Noto Sans CJK SC");
        b.set("你好你好你好", cjk, 20, 400, false, TextAlign.START, 45, 0);
        assertThat(b.lineCount()).isEqualTo(3); // ideographs break anywhere: two per line

        NativeFontFace calibri = NativeFonts.golden("Calibri", "calibri.ttf#0:1650632");
        b.set("office", List.of("Calibri"), 2048, 400, false, TextAlign.START, INF, 0);
        assertThat(b.glyphFace(1)).isSameAs(calibri);
        assertThat(b.caretX(1)).isEqualTo(1080);
        assertThat(b.caretX(2)).isCloseTo(1080 + 1654 / 3f, offset(0.01f)); // inside the ffi ligature
        assertThat(b.caretX(3)).isCloseTo(1080 + 2 * 1654 / 3f, offset(0.01f));
        assertThat(b.caretX(4)).isEqualTo(1080 + 1654);
        assertThat(b.hitTest(1080 + 1654 / 3f + 10, b.baseline())).isEqualTo(2);
        assertThat(b.caretX(6)).isEqualTo(b.width());

        List<String> arabic = NativeFonts.family("Tahoma", "Segoe UI", "Noto Sans Arabic");
        b.set("مرحبا", arabic, 32, 400, false, TextAlign.START, INF, 0);
        assertThat(b.caretX(0)).isEqualTo(b.width()); // right to left: the start is at the right
        assertThat(b.caretX(5)).isZero();
        assertThat(b.caretX(2)).isLessThan(b.caretX(1));
    }

    @Test
    void contextAndLanguageReachHarfBuzz() throws Exception {
        NativeFontFace face = NativeFonts.face(NativeFonts.family("Tahoma", "Segoe UI", "Noto Sans Arabic").get(0));
        assumeTrue(face.covers(0x0628));
        String text = "ببب"; // three beh: initial, medial, final
        try (HarfBuzzShaper shaper = new HarfBuzzShaper()) {
            ShapedGlyphs whole = new ShapedGlyphs();
            shaper.shape(text.toCharArray(), 3, 0, 3, face, 32, true, "ar", whole);
            ShapedGlyphs middle = new ShapedGlyphs();
            shaper.shape(text.toCharArray(), 3, 1, 2, face, 32, true, "ar", middle);
            assertThat(middle.size()).isEqualTo(1);
            assertThat(middle.cluster(0)).isEqualTo(1);
            assertThat(middle.glyph(0)).isEqualTo(whole.glyph(1)); // still the medial form: context counts
            shaper.shape(text.toCharArray(), 3, 0, 3, face, 32, true, "fa", whole); // another language
            char[] longText = new char[2000];
            java.util.Arrays.fill(longText, 'ب');
            ShapedGlyphs many = new ShapedGlyphs();
            shaper.shape(longText, 2000, 0, 2000, face, 32, true, null, many); // grows the native text
            assertThat(many.size()).isEqualTo(2000);
            AutoCloseable twice = shaper;
            twice.close();
        }
        assertThat(UnicodeScript.of(0x0628)).isEqualTo(UnicodeScript.ARABIC);
    }
}
