package com.truesim.ui.text.natives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.jobs.LaneSpec;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.AtlasBaker;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.GlyphCache;
import com.truesim.ui.text.GlyphOutline;
import com.truesim.ui.text.MsdfGenerator;
import com.truesim.ui.text.MsdfGeometry;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.jdk.JdkSdfGenerator;
import com.truesim.ui.text.natives.tools.BakeAtlas;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MsdfAtlasTest {

    private static final float INF = Float.POSITIVE_INFINITY;
    private static final MsdfGenerator MSDF = NativeFonts.BACKEND.msdfGenerator();

    private static GlyphOutline square(boolean clockwise) {
        GlyphOutline o = new GlyphOutline();
        o.moveTo(0, 0);
        if (clockwise) {
            o.lineTo(0, 0.5f);
            o.lineTo(0.5f, 0.5f);
            o.lineTo(0.5f, 0);
        } else {
            o.lineTo(0.5f, 0);
            o.lineTo(0.5f, 0.5f);
            o.lineTo(0, 0.5f);
        }
        o.lineTo(0, 0); // closed explicitly here; the generator closes open contours too
        return o;
    }

    private static int median(byte[] p, int texel) {
        int r = p[texel * 4] & 0xFF;
        int g = p[texel * 4 + 1] & 0xFF;
        int b = p[texel * 4 + 2] & 0xFF;
        return Math.max(Math.min(r, g), Math.min(Math.max(r, g), b));
    }

    @Test
    void theMtsdfIsInsidePositiveWhicheverWayContoursWind() {
        MsdfGeometry g = MsdfGeometry.fit(new float[] {0, 0, 0.5f, 0.5f}, 40, 6);
        byte[] a = MSDF.generate(square(false), g);
        byte[] b = MSDF.generate(square(true), g);
        assertThat(b).isEqualTo(a);
        int center = (g.height() / 2) * g.width() + g.width() / 2;
        assertThat(median(a, center)).isEqualTo(255);
        assertThat(median(a, 0)).isZero();
        int edge = (g.height() / 2) * g.width() + 4; // half a texel inside the left edge
        assertThat(median(a, edge)).isBetween(140, 155);
        // the alpha channel is the true distance: like the brute-force SDF on straight edges
        byte[] sdf = new JdkSdfGenerator().generate(square(false), g);
        for (int i = 0; i < g.width() * g.height(); i++) {
            assertThat(Math.abs((a[i * 4 + 3] & 0xFF) - (sdf[i * 4 + 3] & 0xFF))).as("texel %d", i)
                .isLessThanOrEqualTo(1);
        }
    }

    @Test
    void cornersStaySharpInTheColourChannels() {
        NativeFontFace face = NativeFonts.face(NativeFonts.latin().get(0));
        GlyphOutline o = new GlyphOutline();
        face.outline(face.glyph('A'), o);
        float[] bounds = new float[4];
        o.bounds(bounds);
        MsdfGeometry g = MsdfGeometry.fit(bounds, 40, 6);
        byte[] px = MSDF.generate(o, g);
        int differ = 0;
        for (int i = 0; i < g.width() * g.height(); i++) {
            int r = px[i * 4] & 0xFF;
            int gg = px[i * 4 + 1] & 0xFF;
            int b = px[i * 4 + 2] & 0xFF;
            differ += r != gg || gg != b ? 1 : 0;
        }
        assertThat(differ).isGreaterThan(50); // multi-channel: the channels disagree near corners
        GlyphOutline curves = new GlyphOutline();
        curves.moveTo(0, 0);
        curves.quadTo(0.25f, 0.5f, 0.5f, 0);
        curves.cubicTo(0.4f, -0.3f, 0.1f, -0.3f, 0, 0);
        curves.moveTo(0.6f, 0); // an open second contour: closed by the generator
        curves.lineTo(0.8f, 0);
        curves.lineTo(0.8f, 0); // a zero-length line: dropped
        curves.lineTo(0.7f, 0.2f);
        MsdfGeometry cg = MsdfGeometry.fit(new float[] {0, -0.3f, 0.8f, 0.5f}, 40, 6);
        assertThat(MSDF.generate(curves, cg)).hasSize(cg.width() * cg.height() * 4);
    }

    @Test
    void generationIsDeterministicAcrossThreads() throws Exception {
        NativeFontFace face = NativeFonts.face(NativeFonts.latin().get(0));
        GlyphOutline o = new GlyphOutline();
        face.outline(face.glyph('g'), o);
        float[] bounds = new float[4];
        o.bounds(bounds);
        MsdfGeometry g = MsdfGeometry.fit(bounds, 40, 6);
        byte[] reference = MSDF.generate(o, g);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<byte[]>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                GlyphOutline copy = o.copy();
                results.add(pool.submit((Callable<byte[]>) () -> MSDF.generate(copy, g)));
            }
            for (Future<byte[]> r : results) {
                assertThat(r.get()).isEqualTo(reference);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(MsdfgenGenerator.quantize(-1)).isZero();
        assertThat(MsdfgenGenerator.quantize(2)).isEqualTo((byte) 255);
    }

    /**
     * Done criterion of stage 1: the build-time and run-time atlases are byte-identical for the same glyphs. The
     * baker's command line writes a cache; a run-time atlas generating on job threads, and one taking the glyphs from
     * the loaded cache, end with the same page bytes, and every run-time glyph equals the baked one.
     */
    @Test
    void buildTimeAndRunTimeAtlasesAreByteIdentical(@TempDir Path dir) throws Exception {
        List<String> latin = NativeFonts.latin();
        String extra = "مرحبا नमस्ते 你好";
        Path file = dir.resolve("atlas.tsgc");
        BakeAtlas.main(new String[] {"--out", file.toString(), "--families", latin.get(0), "--text", extra,
            "--em-size", "40", "--pixel-range", "6"});
        GlyphCache baked = GlyphCache.load(file);
        assertThat(baked.size()).isGreaterThan(150);
        String text = AtlasBaker.LATIN + extra; // what the baker asked for, in its order

        UiTextures generatedTextures = new UiTextures();
        GlyphCache runCache = new GlyphCache(40, 6);
        GlyphAtlas generated;
        try (TaskScheduler scheduler = new TaskScheduler()) {
            generated = new GlyphAtlas(generatedTextures, MSDF, scheduler.lane(LaneSpec.platform("glyphs", 4)),
                runCache, new AtlasSettings());
            TextBlock block = new TextBlock(new TextSystem(NativeFonts.REGISTRY, new HarfBuzzShaper(), generated));
            block.set(text, latin, 32, 400, false, TextAlign.START, INF, 0);
            long deadline = System.nanoTime() + 60_000_000_000L;
            do {
                generated.beginFrame(); // never waits: jobs finish in the background
                Thread.sleep(1);
            } while (generated.pending() > 0 && System.nanoTime() < deadline);
        }
        assertThat(generated.pending()).isZero();

        UiTextures loadedTextures = new UiTextures();
        GlyphAtlas loaded = new GlyphAtlas(loadedTextures, (o, g) -> {
            throw new AssertionError("a baked glyph was generated again");
        }, null, baked, new AtlasSettings());
        TextBlock block = new TextBlock(new TextSystem(NativeFonts.REGISTRY, new HarfBuzzShaper(), loaded));
        block.set(text, latin, 32, 400, false, TextAlign.START, INF, 0);
        for (int frame = 0; frame < 100 && loaded.pending() > 0; frame++) {
            loaded.beginFrame(); // cached glyphs are copied in at once, within the frame budget
        }

        assertThat(loaded.pending()).isZero();
        assertThat(loaded.pageCount()).isEqualTo(generated.pageCount());
        for (int p = 0; p < generated.pageCount(); p++) {
            assertThat(loaded.page(p).pixels()).as("page %d", p).isEqualTo(generated.page(p).pixels());
        }
        String key = NativeFonts.face(latin.get(0)).source().key();
        NativeFontFace face = NativeFonts.face(latin.get(0));
        GlyphCache.Glyph a = baked.get(key, face.glyph('a'));
        assertThat(runCache.get(key, face.glyph('a')).pixels()).isEqualTo(a.pixels());
        assertThat(runCache.get(key, face.glyph('a')).geometry()).isEqualTo(a.geometry());
    }

    @Test
    void theBakerChecksItsArguments(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> BakeAtlas.main(new String[] {"--nonsense"}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BakeAtlas.main(new String[0])).isInstanceOf(IllegalArgumentException.class);
        Path text = dir.resolve("text.txt");
        java.nio.file.Files.writeString(text, "Ångström");
        Path out = dir.resolve("bold.tsgc");
        BakeAtlas.main(new String[] {"--out", out.toString(), "--families", "sans-serif|serif", "--text-file",
            text.toString(), "--weight", "700", "--italic"});
        assertThat(GlyphCache.load(out).size()).isGreaterThan(150);
    }

    @Test
    void anUnchangedNativeTextAllocatesNothing() {
        UiTextures textures = new UiTextures();
        GlyphAtlas atlas = new GlyphAtlas(textures, MSDF, null, null, new AtlasSettings());
        TextSystem system = new TextSystem(NativeFonts.REGISTRY, new HarfBuzzShaper(), atlas);
        TextBlock b = new TextBlock(system);
        List<String> latin = NativeFonts.latin();
        String text = "Score: 12,345 — office AV שלום";
        b.set(text, latin, 14, 400, false, TextAlign.START, 200, 0);
        for (int i = 0; i < 10; i++) {
            atlas.beginFrame();
        }
        DrawList list = new DrawList();
        int start = list.allocate(b.glyphCount());
        AllocationAssertions.assertAllocationFree(() -> {
            atlas.beginFrame();
            b.set(text, latin, 14, 400, false, TextAlign.START, 200, 0);
            list.begin(start, b.glyphCount());
            b.record(list, 3, 4, Rgba.WHITE, 0, 0, 0);
            list.end();
        });
    }
}
