package com.truesim.ui.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.jdk.JdkFontFace;
import com.truesim.ui.text.jdk.JdkRasterizer;
import com.truesim.ui.text.jdk.JdkSdfGenerator;
import com.truesim.ui.text.jdk.JdkShaper;
import java.io.IOException;
import java.lang.Character.UnicodeScript;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The JDK fallback over the bundled fonts: the JDK's own OpenType layout, the same on every machine. */
class JdkTextTest {

    private static FontFace face(List<String> family) {
        return TestFonts.registry().match(family.get(0), 400, false);
    }

    private static ShapedGlyphs shape(String text, FontFace face, boolean rtl) {
        ShapedGlyphs out = new ShapedGlyphs();
        new JdkShaper().shape(text.toCharArray(), text.length(), 0, text.length(), face, 32, rtl, null, out);
        return out;
    }

    private static float advance(ShapedGlyphs g) {
        float sum = 0;
        for (int i = 0; i < g.size(); i++) {
            sum += g.advance(i);
        }
        return sum;
    }

    @Test
    void kerningPullsPairsTogether() {
        FontFace f = face(TestFonts.latin());
        for (String pair : new String[] {"AV", "To", "Wa"}) {
            float together = advance(shape(pair, f, false));
            float apart = advance(shape(pair.substring(0, 1), f, false)) + advance(shape(pair.substring(1), f, false));
            assertThat(together).as(pair).isLessThan(apart);
        }
    }

    /** Inter has no fi ligature; its arrow is one glyph for two characters (contextual alternates). */
    @Test
    void ligaturesJoinCharacters() {
        FontFace f = face(TestFonts.latin());
        ShapedGlyphs g = shape("a->b", f, false);
        assertThat(g.size()).isEqualTo(3);
        assertThat(g.cluster(1)).isEqualTo(1); // the ligature's cluster is its first character
        assertThat(g.cluster(2)).isEqualTo(3);
    }

    @Test
    void arabicJoinsRightToLeft() {
        FontFace f = face(List.of("Noto Sans Arabic"));
        String word = "مرحبا"; // marhaba
        ShapedGlyphs g = shape(word, f, true);
        assertThat(g.size()).isGreaterThanOrEqualTo(5); // Noto may draw a dot as a mark of its own
        assertThat(g.cluster(0)).isEqualTo(4); // visual order: the last letter is leftmost
        assertThat(g.cluster(g.size() - 1)).isZero();
        JdkFontFace jdk = (JdkFontFace) f;
        int changed = 0;
        for (int i = 0; i < g.size(); i++) {
            if (g.glyph(i) != jdk.glyph(word.charAt(g.cluster(i)))) {
                changed++; // a joining form, not the isolated one
            }
        }
        assertThat(changed).isGreaterThanOrEqualTo(3);
    }

    @Test
    void devanagariReordersTheVowelSign() {
        FontFace f = face(List.of("Noto Sans Devanagari"));
        ShapedGlyphs g = shape("कि", f, false); // ki: the i sign is drawn before the ka
        assertThat(g.size()).isEqualTo(2);
        // the i sign's pre-base form (glyph 545, as HarfBuzz shapes it in ui-text-native's goldens), then ka
        assertThat(g.glyph(0)).isEqualTo(545).isNotEqualTo(((JdkFontFace) f).glyph(0x0915));
        assertThat(g.glyph(1)).isEqualTo(((JdkFontFace) f).glyph(0x0915));
    }

    /** CJK falls back to the bundled Noto Sans SC and JP; no emoji font is bundled, so emoji is not tested here. */
    @Test
    void cjkFallsBack() {
        List<String> latin = TestFonts.latin();
        FontChain chain = TestFonts.registry().chain(latin, 400, false);
        assertThat(chain.faceFor(0x4F60, UnicodeScript.HAN).source().family()).isEqualTo("Noto Sans SC");
        assertThat(chain.faceFor(0x304B, UnicodeScript.HIRAGANA).source().family()).isEqualTo("Noto Sans JP");
        TextSystem system = new TextSystem(TestFonts.registry(), new JdkShaper(), null);
        TextBlock b = new TextBlock(system);
        b.set("Hi 你好", latin, 16, 400, false, TextAlign.START, Float.POSITIVE_INFINITY, 0);
        assertThat(b.glyphCount()).isEqualTo(4);
        assertThat(b.glyphFace(2)).isNotSameAs(b.glyphFace(0));
        assertThat(b.glyphFace(2).covers(0x4F60)).isTrue();
    }

    @Test
    void facesReadMetricsAndOutlines() throws IOException {
        JdkFontFace f = (JdkFontFace) face(TestFonts.latin());
        assertThat(f.ascender()).isBetween(0.6f, 1.3f);
        assertThat(f.descender()).isBetween(0.1f, 0.6f);
        assertThat(f.lineGap()).isBetween(0f, 0.5f);
        assertThat(f.covers('A')).isTrue();
        assertThat(f.glyph('A')).isPositive();
        assertThat(f.glyph(0x10FFFD)).isZero();
        assertThat(f.font()).isNotNull();
        assertThat(f.toString()).contains(f.source().family());
        GlyphOutline o = new GlyphOutline();
        assertThat(f.outline(f.glyph('O'), o)).isTrue();
        boolean curved = false;
        for (int i = 0; i < o.verbCount(); i++) {
            curved |= o.verbs()[i] == GlyphOutline.QUAD || o.verbs()[i] == GlyphOutline.CUBIC;
        }
        assertThat(curved).isTrue();
        float[] b = new float[4];
        o.bounds(b);
        assertThat(b[3]).isBetween(0.6f, 0.8f); // cap height in ems
        assertThat(f.outline(f.glyph(' '), o)).isFalse();
        assertThat(f.outline(-1, o)).isFalse();
        assertThat(f.outline(1_000_000, o)).isFalse();

        JdkRasterizer raster = new JdkRasterizer();
        GlyphRasterizer.Coverage a = raster.rasterize(f, f.glyph('A'), 24);
        assertThat(a.width()).isBetween(10, 24);
        assertThat(a.top()).isBetween(14, 20);
        int ink = 0;
        for (byte p : a.pixels()) {
            ink += p & 0xFF;
        }
        assertThat(ink).isPositive();
        assertThat(raster.rasterize(f, f.glyph(' '), 24)).isNull();
        GlyphOutline cubic = new GlyphOutline();
        cubic.moveTo(0, 0);
        cubic.cubicTo(0.3f, 0.5f, 0.6f, 0.5f, 0.9f, 0);
        cubic.quadTo(0.5f, -0.2f, 0, 0);
        assertThat(raster.rasterize(new OutlineFace(cubic), 1, 30)).isNotNull();
        GlyphOutline flat = new GlyphOutline();
        flat.moveTo(0, 0);
        flat.lineTo(1, 0);
        assertThat(raster.rasterize(new OutlineFace(flat), 1, 30)).isNull();
    }

    @Test
    void openingAFileThatIsNoFontFails(@TempDir Path dir) throws IOException {
        Path junk = dir.resolve("junk.ttf");
        Files.write(junk, new byte[] {0, 1, 2, 3});
        assertThatThrownBy(() -> TestFonts.BACKEND.open(FontSource.of(junk, 0, "J", 400, false), 0))
            .isInstanceOf(IOException.class);
        Path real = TestFonts.registry().match(TestFonts.latin().get(0), 400, false).source().path();
        assertThatThrownBy(() -> TestFonts.BACKEND.open(FontSource.of(real, 5, "J", 400, false), 0))
            .isInstanceOf(IOException.class);
        assertThat(TestFonts.BACKEND.newRasterizer()).isNotNull();
    }

    @Test
    void theSdfIsPositiveInsideAndTheSameInEveryChannel() {
        GlyphOutline square = new GlyphOutline();
        square.moveTo(0, 0);
        square.lineTo(0.5f, 0);
        square.lineTo(0.5f, 0.5f);
        square.lineTo(0, 0.5f); // left open: the generator closes contours
        square.moveTo(2, 2); // an empty contour
        float[] bounds = {0, 0, 0.5f, 0.5f};
        MsdfGeometry g = MsdfGeometry.fit(bounds, 40, 6);
        byte[] px = new JdkSdfGenerator().generate(square, g);
        int center = ((g.height() / 2) * g.width() + g.width() / 2) * 4;
        assertThat(px[center] & 0xFF).isEqualTo(255); // 10 texels inside: beyond the range
        assertThat(px[0] & 0xFF).isZero();
        assertThat(px[center + 3]).isEqualTo(px[center]);
        int edgeRow = g.height() / 2;
        int edge = (edgeRow * g.width() + 4) * 4; // texel 4 is half a texel inside the left edge
        assertThat(px[edge] & 0xFF).isBetween(140, 155);
        assertThat(JdkSdfGenerator.class).isNotNull();
    }

    /** A face with one made-up outline. */
    private record OutlineFace(GlyphOutline shape) implements FontFace {
        @Override
        public int id() {
            return 0;
        }

        @Override
        public FontSource source() {
            return null;
        }

        @Override
        public float ascender() {
            return 1;
        }

        @Override
        public float descender() {
            return 0;
        }

        @Override
        public float lineGap() {
            return 0;
        }

        @Override
        public boolean covers(int codepoint) {
            return true;
        }

        @Override
        public boolean outline(int glyph, GlyphOutline out) {
            out.reset();
            float[] p = shape.points();
            int pi = 0;
            for (int i = 0; i < shape.verbCount(); i++) {
                switch (shape.verbs()[i]) {
                    case GlyphOutline.MOVE -> out.moveTo(p[pi++], p[pi++]);
                    case GlyphOutline.LINE -> out.lineTo(p[pi++], p[pi++]);
                    case GlyphOutline.QUAD -> out.quadTo(p[pi++], p[pi++], p[pi++], p[pi++]);
                    default -> out.cubicTo(p[pi++], p[pi++], p[pi++], p[pi++], p[pi++], p[pi++]);
                }
            }
            return !out.isEmpty();
        }
    }

    @Test
    void theSoftwareRendererInksGlyphsWithTheShadersFormula() {
        UiTextures textures = new UiTextures();
        TextSystem system = TestFonts.system(textures, new AtlasSettings());
        TextBlock b = new TextBlock(system);
        b.set("Hi", TestFonts.latin(), 32, 400, false, TextAlign.START, Float.POSITIVE_INFINITY, 0);
        system.atlas().beginFrame();
        DrawList list = new DrawList();
        int start = list.allocate(b.glyphCount());
        list.begin(start, b.glyphCount());
        b.record(list, 4, 4, Rgba.WHITE, 0, Rgba.BLACK, 0);
        list.end();
        int w = 64;
        int h = 48;
        float[] plain = new float[w * h * 3];
        SoftwareGlyphRenderer.render(list, textures, plain, w, h);
        int lit = 0;
        int partial = 0;
        for (int i = 0; i < plain.length; i += 3) {
            lit += plain[i] > 0.99f ? 1 : 0;
            partial += plain[i] > 0.01f && plain[i] < 0.99f ? 1 : 0;
        }
        assertThat(lit).isGreaterThan(40);
        assertThat(partial).isGreaterThan(10); // anti-aliased edges

        list.begin(start, b.glyphCount());
        b.record(list, 4, 4, Rgba.pack(0, 0, 0, 1), 2, Rgba.pack(0, 1, 0, 1), 0);
        list.end();
        float[] outlined = new float[w * h * 3];
        SoftwareGlyphRenderer.render(list, textures, outlined, w, h);
        int green = 0;
        for (int i = 0; i < outlined.length; i += 3) {
            green += outlined[i + 1] > 0.5f ? 1 : 0;
        }
        assertThat(green).isGreaterThan(lit / 2);
        assertThat(SoftwareGlyphRenderer.median(0.2f, 0.9f, 0.5f)).isEqualTo(0.5f);
    }

    @Test
    void theBakerPrebakesWhatTheRunTimeTakes(@TempDir Path dir) throws Exception {
        List<String> latin = TestFonts.latin();
        UiTextures t1 = new UiTextures();
        GlyphAtlas a1 = new GlyphAtlas(t1, TestFonts.BACKEND.msdfGenerator(), null, new GlyphCache(40, 6),
            new AtlasSettings());
        TextSystem build = new TextSystem(TestFonts.registry(), new JdkShaper(), a1);
        Path file = dir.resolve("latin.tsgc");
        int glyphs = AtlasBaker.bakeTo(file, build, "Hello, World!", List.of(latin), 400, false);
        assertThat(glyphs).isEqualTo(9); // H e l o , W r d ! (spaces draw nothing)
        assertThat(AtlasBaker.LATIN).hasSize(95 + 96);

        UiTextures t2 = new UiTextures();
        GlyphAtlas a2 = new GlyphAtlas(t2, (o, g) -> {
            throw new AssertionError("baked glyphs are not generated again");
        }, null, GlyphCache.load(file), new AtlasSettings());
        TextSystem run = new TextSystem(TestFonts.registry(), new JdkShaper(), a2);
        TextBlock b = new TextBlock(run);
        b.set("Hello, World!", latin, 14, 400, false, TextAlign.START, Float.POSITIVE_INFINITY, 0);
        a2.beginFrame();
        assertThat(t2.get(a2.page(0).id()).pixels()).isEqualTo(t1.get(a1.page(0).id()).pixels());
    }
}
