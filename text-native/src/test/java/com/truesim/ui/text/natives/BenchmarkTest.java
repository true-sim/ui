package com.truesim.ui.text.natives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.AtlasBaker;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontFace;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.GlyphOutline;
import com.truesim.ui.text.MsdfGenerator;
import com.truesim.ui.text.MsdfGeometry;
import com.truesim.ui.text.SkylinePacker;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextShaper;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.jdk.JdkSdfGenerator;
import com.truesim.ui.text.jdk.JdkTextBackend;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.stb.STBRPContext;
import org.lwjgl.stb.STBRPNode;
import org.lwjgl.stb.STBRPRect;
import org.lwjgl.stb.STBRectPack;

/**
 * Benchmarks of section 11 and the competing techniques, run with {@code TRUESIM_BENCHMARK=true} (coverage off, see
 * the build file). Each warms past C2 compilation, then reports the median of several rounds.
 */
class BenchmarkTest {

    private static final int ROUNDS = 7;

    @BeforeEach
    void onlyWhenAsked() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")), "benchmarks: TRUESIM_BENCHMARK=true");
    }

    private static double median(double[] values) {
        double[] v = values.clone();
        Arrays.sort(v);
        return v[v.length / 2];
    }

    private static String label(String unit, int length, Random random) {
        StringBuilder s = new StringBuilder();
        while (s.length() < length) {
            s.append(unit, 0, 1 + random.nextInt(unit.length()));
            s.append(' ');
        }
        return s.substring(0, length);
    }

    /** Microseconds per reshape (text changes every call) of a 200-character label wrapped at 300 px. */
    private static double reshape(TextSystem system, List<String> families, String a, String b) {
        TextBlock block = new TextBlock(system);
        int n = 20_000;
        for (int i = 0; i < 3 * n; i++) { // warm past C2
            block.set((i & 1) == 0 ? a : b, families, 14, 400, false, TextAlign.START, 300, 0);
        }
        double[] rounds = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                block.set((i & 1) == 0 ? a : b, families, 14, 400, false, TextAlign.START, 300, 0);
            }
            rounds[r] = (System.nanoTime() - t0) / 1000.0 / n;
        }
        assertThat(block.lineCount()).isGreaterThan(1);
        return median(rounds);
    }

    @Test
    void reshapeOf200CharacterLabel() {
        List<String> latin = NativeFonts.latin();
        Random random = new Random(1);
        String text = label("Lorem ipsum dolor sit amet consectetur adipiscing elit sed do", 200, random);
        String other = text.substring(0, 199) + "!";
        String mixed = label("Score مرحبا 你好世界 office AV 42", 200, random);
        String mixedOther = mixed.substring(0, 199) + "!";
        assertThat(text).hasSize(200);

        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), NativeFonts.BACKEND.msdfGenerator(), null, null,
            new AtlasSettings());
        TextSystem harfbuzz = new TextSystem(NativeFonts.REGISTRY, new HarfBuzzShaper(), atlas);
        double latinUs = reshape(harfbuzz, latin, text, other);
        double mixedUs = reshape(harfbuzz, latin, mixed, mixedOther);
        JdkTextBackend jdk = new JdkTextBackend();
        FontRegistry jdkFonts = FontRegistry.system(jdk);
        TextShaper jdkShaper = jdk.newShaper();
        double jdkUs = reshape(new TextSystem(jdkFonts, jdkShaper, null), latin, text, other);
        System.out.printf("BENCH reshape 200 chars, HarfBuzz, Latin: %.2f us (target <= 50)%n", latinUs);
        System.out.printf("BENCH reshape 200 chars, HarfBuzz, Latin+Arabic+CJK+BiDi: %.2f us%n", mixedUs);
        System.out.printf("BENCH reshape 200 chars, JDK fallback, Latin: %.2f us%n", jdkUs);
    }

    @Test
    void mtsdfGenerationPerGlyph() {
        NativeFontFace face = NativeFonts.face(NativeFonts.latin().get(0));
        List<GlyphOutline> outlines = new ArrayList<>();
        List<MsdfGeometry> geometries = new ArrayList<>();
        float[] bounds = new float[4];
        for (char c : AtlasBaker.LATIN.toCharArray()) {
            GlyphOutline o = new GlyphOutline();
            if (face.outline(face.glyph(c), o)) {
                o.bounds(bounds);
                outlines.add(o);
                geometries.add(MsdfGeometry.fit(bounds, 40, 6));
            }
        }
        MsdfGenerator msdfgen = NativeFonts.BACKEND.msdfGenerator();
        double msdf = perGlyph(msdfgen, outlines, geometries, 5);
        double jdk = perGlyph(new JdkSdfGenerator(), outlines, geometries, 1);
        System.out.printf("BENCH MTSDF per glyph (msdfgen, 40 px/em, range 6, %d Latin glyphs): %.1f us%n",
            outlines.size(), msdf);
        System.out.printf("BENCH plain SDF per glyph (JDK fallback, brute force): %.1f us%n", jdk);
    }

    private static double perGlyph(MsdfGenerator generator, List<GlyphOutline> outlines,
        List<MsdfGeometry> geometries, int warm) {
        for (int w = 0; w < warm; w++) {
            for (int i = 0; i < outlines.size(); i++) {
                generator.generate(outlines.get(i), geometries.get(i));
            }
        }
        double[] rounds = new double[3];
        for (int r = 0; r < rounds.length; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < outlines.size(); i++) {
                generator.generate(outlines.get(i), geometries.get(i));
            }
            rounds[r] = (System.nanoTime() - t0) / 1000.0 / outlines.size();
        }
        return median(rounds);
    }

    @Test
    void ownSkylinePackerAgainstStbRectPack() {
        int n = 200_000;
        int[] w = new int[n];
        int[] h = new int[n];
        Random random = new Random(3);
        for (int i = 0; i < n; i++) { // glyph-like: 40 px/em glyphs with padding
            w[i] = 12 + random.nextInt(40);
            h[i] = 16 + random.nextInt(44);
        }
        SkylinePacker own = new SkylinePacker(1024, 1024);
        STBRPContext context = STBRPContext.malloc();
        STBRPNode.Buffer nodes = STBRPNode.malloc(1024);
        STBRPRect.Buffer one = STBRPRect.malloc(1);
        try {
            double[] ownUs = new double[ROUNDS];
            double[] stbUs = new double[ROUNDS];
            int ownPages = 0;
            int stbPages = 0;
            for (int r = 0; r < ROUNDS + 3; r++) { // the first three warm
                long t0 = System.nanoTime();
                ownPages = packOwn(own, w, h);
                long t1 = System.nanoTime();
                stbPages = packStb(context, nodes, one, w, h);
                long t2 = System.nanoTime();
                if (r >= 3) {
                    ownUs[r - 3] = (t1 - t0) / 1000.0 / n;
                    stbUs[r - 3] = (t2 - t1) / 1000.0 / n;
                }
            }
            System.out.printf("BENCH packer, own skyline: %.3f us per glyph, %d pages of 1024 for %d glyphs%n",
                median(ownUs), ownPages, n);
            System.out.printf("BENCH packer, stb_rect_pack: %.3f us per glyph, %d pages of 1024 for %d glyphs%n",
                median(stbUs), stbPages, n);
        } finally {
            context.free();
            nodes.free();
            one.free();
        }
    }

    private static int packOwn(SkylinePacker packer, int[] w, int[] h) {
        packer.reset(1024, 1024);
        int pages = 1;
        for (int i = 0; i < w.length; i++) {
            if (!packer.pack(w[i], h[i])) {
                packer.reset(1024, 1024);
                pages++;
                packer.pack(w[i], h[i]);
            }
        }
        return pages;
    }

    /** stb_rect_pack one rectangle at a time, as the atlas must (glyphs arrive in request order). */
    private static int packStb(STBRPContext context, STBRPNode.Buffer nodes, STBRPRect.Buffer one, int[] w,
        int[] h) {
        STBRectPack.stbrp_init_target(context, 1024, 1024, nodes);
        int pages = 1;
        for (int i = 0; i < w.length; i++) {
            one.get(0).w(w[i]).h(h[i]);
            if (STBRectPack.stbrp_pack_rects(context, one) == 0) {
                STBRectPack.stbrp_init_target(context, 1024, 1024, nodes);
                pages++;
                STBRectPack.stbrp_pack_rects(context, one);
            }
        }
        return pages;
    }

    @Test
    void faceLookupsStayCheap() {
        FontFace face = NativeFonts.face(NativeFonts.latin().get(0));
        int n = 1_000_000;
        int covered = 0;
        for (int i = 0; i < n; i++) {
            covered += face.covers('a' + i % 26) ? 1 : 0;
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            covered += face.covers('a' + i % 26) ? 1 : 0;
        }
        System.out.printf("BENCH coverage check (HarfBuzz cmap): %.1f ns%n", (System.nanoTime() - t0) / (double) n);
        assertThat(covered).isEqualTo(2 * n);
    }
}
