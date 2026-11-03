package com.truesim.ui.text.natives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.text.FontFace;
import com.truesim.ui.text.FontSource;
import com.truesim.ui.text.GlyphOutline;
import com.truesim.ui.text.GlyphRasterizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Vector;

class NativeFaceTest {

    @Test
    void facesReadMetricsCoverageAndOutlines() {
        NativeFontFace face = NativeFonts.face(NativeFonts.latin().get(0));
        assertThat(face.unitsPerEm()).isPositive();
        assertThat(face.ascender()).isBetween(0.6f, 1.3f);
        assertThat(face.descender()).isBetween(0.1f, 0.6f);
        assertThat(face.lineGap()).isBetween(0f, 0.5f);
        assertThat(face.covers('A')).isTrue();
        assertThat(face.covers(0x10FFFD)).isFalse();
        assertThat(face.glyph(0x10FFFD)).isZero();
        assertThat(face.toString()).contains(face.source().family());

        GlyphOutline o = new GlyphOutline();
        assertThat(face.outline(face.glyph('A'), o)).isTrue();
        assertThat(face.outline(face.glyph('O'), o)).isTrue();
        boolean quads = false;
        for (int i = 0; i < o.verbCount(); i++) {
            quads |= o.verbs()[i] == GlyphOutline.QUAD;
        }
        assertThat(quads).isTrue();
        float[] b = new float[4];
        o.bounds(b);
        assertThat(b[3]).isBetween(0.6f, 0.8f); // cap height
        assertThat(face.outline(face.glyph(' '), o)).isFalse();
        assertThat(face.outline(999_999, o)).isFalse();
    }

    @Test
    void collectionsOpenByIndex() {
        NativeFontFace regular = NativeFonts.face("Nirmala UI");
        FontFace bold = NativeFonts.REGISTRY.match("Nirmala UI", 700, false);
        assertThat(bold).isNotSameAs(regular);
        assertThat(bold.source().index()).isEqualTo(1);
        GlyphOutline a = new GlyphOutline();
        GlyphOutline b = new GlyphOutline();
        regular.outline(regular.glyph(0x915), a);
        bold.outline(((NativeFontFace) bold).glyph(0x915), b);
        float[] ra = new float[4];
        float[] rb = new float[4];
        a.bounds(ra);
        b.bounds(rb);
        assertThat(rb[2] - rb[0]).isGreaterThan(ra[2] - ra[0]); // bold is wider
    }

    @Test
    void brokenFilesFailAndClosedFacesDrawNothing(@TempDir Path dir) throws Exception {
        Path junk = dir.resolve("junk.ttf");
        Files.write(junk, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
        try (NativeTextBackend backend = new NativeTextBackend()) {
            assertThatThrownBy(() -> backend.open(FontSource.of(junk, 0, "J", 400, false), 0))
                .isInstanceOf(IOException.class);
            Path real = NativeFonts.face(NativeFonts.latin().get(0)).source().path();
            assertThatThrownBy(() -> backend.open(FontSource.of(real, 7, "J", 400, false), 0))
                .isInstanceOf(IOException.class);
            NativeFontFace face = (NativeFontFace) backend.open(FontSource.of(real, 0, "R", 400, false), 0);
            int a = face.glyph('A');
            AutoCloseable twice = face;
            twice.close();
            twice.close(); // closing twice is harmless
            assertThat(face.outline(a, new GlyphOutline())).isFalse();
            assertThat(backend.newRasterizer()).isInstanceOf(FreeTypeRasterizer.class);
            assertThat(backend.newShaper()).isInstanceOf(HarfBuzzShaper.class);
            AutoCloseable again = backend;
            again.close();
            assertThatThrownBy(() -> backend.open(FontSource.of(real, 0, "R", 400, false), 1))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void theHintedRasterHasInk() {
        NativeFontFace face = NativeFonts.face(NativeFonts.latin().get(0));
        GlyphRasterizer raster = NativeFonts.BACKEND.newRasterizer();
        GlyphRasterizer.Coverage a = raster.rasterize(face, face.glyph('A'), 12);
        assertThat(a.width()).isBetween(5, 12);
        assertThat(a.top()).isBetween(7, 11);
        int ink = 0;
        for (byte p : a.pixels()) {
            ink += p & 0xFF;
        }
        assertThat(ink).isPositive();
        assertThat(raster.rasterize(face, face.glyph(' '), 12)).isNull();
        assertThat(raster.rasterize(face, 999_999, 12)).isNull();
    }

    /** Points, tags and contour ends written into native memory as FreeType lays them out. */
    private static GlyphOutline decompose(float[][] points, int[] tags, int[] ends) {
        long pts = MemoryUtil.nmemCalloc(points.length, FT_Vector.SIZEOF);
        long tg = MemoryUtil.nmemCalloc(tags.length, 1);
        long en = MemoryUtil.nmemCalloc(ends.length, 2);
        try {
            for (int i = 0; i < points.length; i++) {
                FT_Vector.nx(pts + (long) i * FT_Vector.SIZEOF, (long) points[i][0]);
                FT_Vector.ny(pts + (long) i * FT_Vector.SIZEOF, (long) points[i][1]);
                MemoryUtil.memPutByte(tg + i, (byte) tags[i]);
            }
            for (int i = 0; i < ends.length; i++) {
                MemoryUtil.memPutShort(en + 2L * i, (short) ends[i]);
            }
            GlyphOutline out = new GlyphOutline();
            new NativeFontFace.Decomposer(out, pts, tg, 1).contours(en, ends.length);
            return out;
        } finally {
            MemoryUtil.nmemFree(pts);
            MemoryUtil.nmemFree(tg);
            MemoryUtil.nmemFree(en);
        }
    }

    private static final int ON = 1;
    private static final int CONIC = 0;
    private static final int CUBIC = 2;

    @Test
    void outlinesDecomposeAsFreeTypeDoes() {
        // a square of lines, and a contour starting off-curve after an on-curve last point
        GlyphOutline lines = decompose(new float[][] {{0, 0}, {10, 0}, {10, 10}, {0, 10}}, new int[] {ON, ON, ON, ON},
            new int[] {3});
        assertThat(lines.verbCount()).isEqualTo(5); // move, three lines, the closing line
        GlyphOutline conicStart = decompose(new float[][] {{5, 0}, {10, 10}, {0, 10}},
            new int[] {CONIC, ON, ON}, new int[] {2});
        assertThat(conicStart.verbs()[0]).isEqualTo(GlyphOutline.MOVE);
        assertThat(conicStart.points()[0]).isZero(); // started at the last (on-curve) point
        assertThat(conicStart.verbs()[1]).isEqualTo(GlyphOutline.QUAD);
        // all off-curve: implied on-curve midpoints, closed by a conic
        GlyphOutline circle = decompose(new float[][] {{0, 10}, {10, 10}, {10, 0}, {0, 0}},
            new int[] {CONIC, CONIC, CONIC, CONIC}, new int[] {3});
        assertThat(circle.verbCount()).isEqualTo(5);
        assertThat(circle.points()[0]).isZero();
        assertThat(circle.points()[1]).isEqualTo(5); // between the first and the last point
        // cubic segments, one ending at the start
        GlyphOutline cubic = decompose(new float[][] {{0, 0}, {3, 5}, {7, 5}, {10, 0}, {7, -5}, {3, -5}},
            new int[] {ON, CUBIC, CUBIC, ON, CUBIC, CUBIC}, new int[] {5});
        assertThat(cubic.verbs()[1]).isEqualTo(GlyphOutline.CUBIC);
        assertThat(cubic.verbs()[2]).isEqualTo(GlyphOutline.CUBIC);
        assertThat(cubic.verbCount()).isEqualTo(3);
        // invalid ones stop: a cubic start, a lone cubic control, a conic followed by a cubic
        assertThat(decompose(new float[][] {{0, 0}, {1, 1}}, new int[] {CUBIC, ON}, new int[] {1}).verbCount())
            .isZero();
        assertThat(decompose(new float[][] {{0, 0}, {1, 1}, {2, 0}}, new int[] {ON, CUBIC, ON}, new int[] {2})
            .verbCount()).isEqualTo(1);
        assertThat(decompose(new float[][] {{0, 0}, {1, 1}, {2, 0}, {3, 3}}, new int[] {ON, CONIC, CUBIC, ON},
            new int[] {3}).verbCount()).isEqualTo(1);
        // two contours
        assertThat(decompose(new float[][] {{0, 0}, {1, 0}, {1, 1}, {5, 5}, {6, 5}, {6, 6}},
            new int[] {ON, ON, ON, ON, ON, ON}, new int[] {2, 5}).verbCount()).isEqualTo(8);
    }
}
