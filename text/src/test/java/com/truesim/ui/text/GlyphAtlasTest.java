package com.truesim.ui.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.jobs.Lane;
import com.truesim.common.jobs.LaneSpec;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.jdk.JdkRasterizer;
import com.truesim.ui.text.jdk.JdkSdfGenerator;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlyphAtlasTest {

    @TempDir
    Path dir;

    private Fakes.Face face(int id) {
        return new Fakes.Face(id, Fakes.source(dir, "f" + id + ".ttf", "F", 400, false), 0, 0x10FFFF);
    }

    /** Counts calls, so tests see whether a glyph was generated or came from a cache. */
    static final class Counting implements MsdfGenerator {
        final AtomicInteger calls = new AtomicInteger();
        final MsdfGenerator inner = new JdkSdfGenerator();

        @Override
        public byte[] generate(GlyphOutline outline, MsdfGeometry geometry) {
            calls.incrementAndGet();
            return inner.generate(outline, geometry);
        }
    }

    @Test
    void glyphsAreQueuedThenGeneratedWithinTheFrameBudget() {
        UiTextures textures = new UiTextures();
        AtlasSettings settings = new AtlasSettings();
        settings.glyphsPerFrame().set(2);
        Counting generator = new Counting();
        GlyphAtlas atlas = new GlyphAtlas(textures, generator, null, null, settings);
        Fakes.Face f = face(1);
        int a = atlas.entry(f, 40);
        int b = atlas.entry(f, 50);
        int space = atlas.entry(f, 32);
        assertThat(atlas.entry(f, 40)).isEqualTo(a);
        assertThat(atlas.state(a)).isEqualTo(GlyphAtlas.QUEUED);
        assertThat(atlas.pending()).isEqualTo(3);
        assertThat(atlas.size()).isEqualTo(3);

        atlas.beginFrame();
        assertThat(atlas.state(a)).isEqualTo(GlyphAtlas.READY);
        assertThat(atlas.state(b)).isEqualTo(GlyphAtlas.READY);
        assertThat(atlas.state(space)).isEqualTo(GlyphAtlas.QUEUED); // over the budget: next frame
        atlas.beginFrame();
        assertThat(atlas.state(space)).isEqualTo(GlyphAtlas.EMPTY);
        assertThat(generator.calls.get()).isEqualTo(2);
        assertThat(atlas.completions()).isEqualTo(3);

        // a 0.4 em square at 40 texels per em: 16 texels plus 3.5 of padding a side, rounded out
        assertThat(atlas.plane(a, 0)).isEqualTo(-4 / 40f);
        assertThat(atlas.plane(a, 2)).isCloseTo(20 / 40f, org.assertj.core.data.Offset.offset(1e-6f));
        UiTexture page = atlas.page(0);
        assertThat(atlas.texture(a)).isEqualTo(page.id());
        assertThat(page.format()).isEqualTo(UiTexture.Format.RGBA8_UNORM);
        assertThat(page.width()).isEqualTo(1024);
        assertThat(atlas.uv(a, 0)).isZero();
        assertThat(atlas.uv(a, 2)).isEqualTo(24 / 1024f);
        assertThat(atlas.uv(b, 0)).isEqualTo(25 / 1024f); // after a, past the gutter
        assertThat(page.dirty()).isTrue();
        int center = ((12) * 1024 + 12) * 4; // inside glyph a
        assertThat(page.pixels()[center] & 0xFF).isGreaterThan(128);
        assertThat(page.pixels()[3] & 0xFF).isZero(); // the corner: outside
        assertThat(atlas.emSize()).isEqualTo(40);
        assertThat(atlas.pixelRange()).isEqualTo(6);
        assertThat(atlas.pageCount()).isEqualTo(1);
        assertThatThrownBy(() -> atlas.page(1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void anUnchangedFrameAllocatesNothing() {
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), new JdkSdfGenerator(), null, null, new AtlasSettings());
        Fakes.Face f = face(1);
        int e = atlas.entry(f, 40);
        atlas.beginFrame();
        AllocationAssertions.assertAllocationFree(() -> {
            atlas.beginFrame();
            atlas.entry(f, 40);
            atlas.state(e);
        });
    }

    @Test
    void jobsGenerateOnLaneThreadsAndThePagesMatchASynchronousAtlas() throws Exception {
        UiTextures syncTextures = new UiTextures();
        GlyphAtlas sync = new GlyphAtlas(syncTextures, new JdkSdfGenerator(), null, null, new AtlasSettings());
        UiTextures jobTextures = new UiTextures();
        try (TaskScheduler scheduler = new TaskScheduler()) {
            Lane lane = scheduler.lane(LaneSpec.platform("glyphs", 3));
            GlyphAtlas jobs = new GlyphAtlas(jobTextures, new JdkSdfGenerator(), lane, null, new AtlasSettings());
            Fakes.Face f = face(1);
            for (int g = 10; g < 70; g += 3) {
                sync.entry(f, g);
                jobs.entry(f, g);
            }
            sync.beginFrame();
            jobs.beginFrame(); // starts the jobs, does not wait
            int e = jobs.entry(f, 10);
            assertThat(jobs.state(e)).isIn(GlyphAtlas.GENERATING, GlyphAtlas.READY);
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (jobs.pending() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(2);
                jobs.beginFrame();
            }
            assertThat(jobs.pending()).isZero();
            assertThat(jobs.state(e)).isEqualTo(GlyphAtlas.READY);
            assertThat(jobTextures.get(jobs.page(0).id()).pixels())
                .isEqualTo(syncTextures.get(sync.page(0).id()).pixels());
        }
    }

    @Test
    void finishAllWaitsForRunningJobs() throws Exception {
        try (TaskScheduler scheduler = new TaskScheduler()) {
            Lane lane = scheduler.lane(LaneSpec.platform("glyphs", 2));
            GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), new JdkSdfGenerator(), lane, null,
                new AtlasSettings());
            Fakes.Face f = face(1);
            for (int g = 10; g < 30; g++) {
                atlas.entry(f, g);
            }
            atlas.beginFrame(); // some run as jobs
            atlas.finishAll();
            assertThat(atlas.pending()).isZero();
            assertThat(atlas.state(atlas.entry(f, 29))).isEqualTo(GlyphAtlas.READY);
        }
    }

    @Test
    void aCacheSuppliesGlyphsAndFillsUp() throws Exception {
        GlyphCache cache = new GlyphCache(40, 6);
        Counting first = new Counting();
        UiTextures t1 = new UiTextures();
        GlyphAtlas a1 = new GlyphAtlas(t1, first, null, cache, new AtlasSettings());
        Fakes.Face f = face(1);
        for (int g : new int[] {40, 32, 60, 20}) {
            a1.entry(f, g);
        }
        assertThat(a1.finishAll()).isEqualTo(4);
        assertThat(first.calls.get()).isEqualTo(3);
        assertThat(cache.size()).isEqualTo(4);
        assertThat(a1.cache()).isSameAs(cache);
        Path file = dir.resolve("glyphs.tsgc");
        cache.save(file);

        Counting second = new Counting();
        UiTextures t2 = new UiTextures();
        GlyphAtlas a2 = new GlyphAtlas(t2, second, null, GlyphCache.load(file), new AtlasSettings());
        for (int g : new int[] {40, 32, 60, 20}) {
            a2.entry(f, g);
        }
        a2.beginFrame();
        assertThat(second.calls.get()).isZero();
        assertThat(a2.state(a2.entry(f, 32))).isEqualTo(GlyphAtlas.EMPTY);
        assertThat(t2.get(a2.page(0).id()).pixels()).isEqualTo(t1.get(a1.page(0).id()).pixels());

        AtlasSettings other = new AtlasSettings();
        other.emSize().set(32); // the cache does not match: generated
        Counting third = new Counting();
        GlyphAtlas a3 = new GlyphAtlas(new UiTextures(), third, null, GlyphCache.load(file), other);
        a3.entry(f, 40);
        a3.entry(f, 32);
        a3.beginFrame();
        assertThat(third.calls.get()).isEqualTo(1);
    }

    @Test
    void fullPagesOpenNewOnesAndHugeGlyphsAreSkipped() {
        AtlasSettings settings = new AtlasSettings();
        settings.pageSize().set(256);
        settings.emSize().set(128);
        settings.glyphsPerFrame().set(100);
        UiTextures textures = new UiTextures();
        GlyphAtlas atlas = new GlyphAtlas(textures, new JdkSdfGenerator(), null, null, settings);
        Fakes.Face f = face(1);
        for (int g = 60; g < 80; g++) {
            atlas.entry(f, g);
        }
        int huge = atlas.entry(f, 300); // 3 ems: 384 texels, more than a page
        atlas.beginFrame();
        assertThat(atlas.pageCount()).isGreaterThan(1);
        assertThat(atlas.state(huge)).isEqualTo(GlyphAtlas.EMPTY);
        assertThat(atlas.texture(atlas.entry(f, 79))).isEqualTo(atlas.page(atlas.pageCount() - 1).id());
    }

    @Test
    void changingTheSettingsStartsOverReusingThePages() {
        AtlasSettings settings = new AtlasSettings();
        UiTextures textures = new UiTextures();
        GlyphAtlas atlas = new GlyphAtlas(textures, new JdkSdfGenerator(), null, null, settings);
        Fakes.Face f = face(1);
        atlas.entry(f, 40);
        atlas.beginFrame();
        UiTexture page = atlas.page(0);
        settings.pixelRange().set(4);
        atlas.beginFrame();
        assertThat(atlas.epoch()).isEqualTo(1);
        assertThat(atlas.size()).isZero();
        assertThat(atlas.page(0)).isSameAs(page);
        assertThat(atlas.pixelRange()).isEqualTo(4);
        int e = atlas.entry(f, 40);
        atlas.beginFrame();
        assertThat(atlas.state(e)).isEqualTo(GlyphAtlas.READY);

        settings.pageSize().set(512); // a new size: new pages
        atlas.beginFrame();
        assertThat(atlas.page(0)).isNotSameAs(page);
        assertThat(atlas.page(0).width()).isEqualTo(512);
        assertThat(textures.count()).isEqualTo(3);
        assertThat(settings.all()).hasSize(7);
    }

    @Test
    void aFailingGeneratorLeavesTheGlyphEmpty() throws Exception {
        MsdfGenerator failing = (outline, geometry) -> {
            throw new IllegalStateException("broken");
        };
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), failing, null, null, new AtlasSettings());
        int e = atlas.entry(face(1), 40);
        atlas.beginFrame();
        assertThat(atlas.state(e)).isEqualTo(GlyphAtlas.EMPTY);
        try (TaskScheduler scheduler = new TaskScheduler()) {
            GlyphAtlas jobs = new GlyphAtlas(new UiTextures(), failing, scheduler.lane(LaneSpec.platform("g", 1)),
                null, new AtlasSettings());
            int j = jobs.entry(face(1), 40);
            jobs.finishAll();
            assertThat(jobs.state(j)).isEqualTo(GlyphAtlas.EMPTY);
        }
    }

    @Test
    void manyGlyphsGrowTheTables() {
        AtlasSettings settings = new AtlasSettings();
        settings.glyphsPerFrame().set(4096);
        settings.emSize().set(16);
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), new JdkSdfGenerator(), null, null, settings);
        Fakes.Face f = face(1);
        for (int g = 1; g <= 600; g++) {
            atlas.entry(f, 1 + g % 40);
            atlas.entry(face(2 + g), 10);
        }
        assertThat(atlas.size()).isEqualTo(640);
        atlas.beginFrame();
        assertThat(atlas.pending()).isZero();
    }

    // ---------------------------------------------------------------- raster glyphs (UI16)

    private static GlyphAtlas rasterAtlas(UiTextures textures, Lane lane, GlyphCache cache, AtlasSettings settings) {
        return new GlyphAtlas(textures, new JdkSdfGenerator(), new JdkRasterizer(), lane, cache, settings);
    }

    @Test
    void rasterGlyphsAreKeyedBySizeAndShareThePages() {
        UiTextures textures = new UiTextures();
        GlyphAtlas atlas = rasterAtlas(textures, null, null, new AtlasSettings());
        Fakes.Face f = face(1);
        int msdf = atlas.entry(f, 40);
        int r10 = atlas.rasterEntry(f, 40, 10, 0);
        int r12 = atlas.rasterEntry(f, 40, 12, 0);
        assertThat(atlas.rasterEntry(f, 40, 10.001f, 0)).as("the same 1/64 pixel").isEqualTo(r10);
        assertThat(atlas.rasterEntry(f, 40, 10.25f, 0)).isNotEqualTo(r10);
        assertThat(java.util.Set.of(msdf, r10, r12)).hasSize(3);
        assertThat(atlas.raster(r10)).isTrue();
        assertThat(atlas.raster(msdf)).isFalse();
        int space = atlas.rasterEntry(f, 32, 10, 0);
        assertThat(atlas.state(r10)).isEqualTo(GlyphAtlas.QUEUED);

        atlas.beginFrame();
        assertThat(atlas.state(r10)).isEqualTo(GlyphAtlas.READY);
        assertThat(atlas.state(r12)).isEqualTo(GlyphAtlas.READY);
        assertThat(atlas.state(space)).isEqualTo(GlyphAtlas.EMPTY);
        assertThat(atlas.texture(r10)).as("the same page as MTSDF glyphs").isEqualTo(atlas.texture(msdf));
        // a 0.4 em square at 10 px is 4 px from the pen, padded by 2 px each side: whole pixels
        assertThat(atlas.plane(r10, 0)).isEqualTo(-2);
        assertThat(atlas.plane(r10, 1)).isEqualTo(-2);
        assertThat(atlas.plane(r10, 2)).isEqualTo(6);
        assertThat(atlas.plane(r10, 3)).isEqualTo(6);
        assertThat(atlas.plane(r12, 2) - atlas.plane(r12, 0)).as("4.8 px rounded out, padded").isEqualTo(9);
        assertThat((atlas.uv(r10, 2) - atlas.uv(r10, 0)) * 1024)
            .isCloseTo(8, org.assertj.core.data.Offset.offset(1e-3f));
        UiTexture page = atlas.page(0);
        int x = (int) (atlas.uv(r10, 0) * 1024 + 0.5f);
        int y = (int) (atlas.uv(r10, 1) * 1024 + 0.5f);
        byte[] p = page.pixels();
        int inside = ((y + 3) * 1024 + x + 3) * 4; // texel 3, 3 of the box: inside the square (texels 2 to 5)
        assertThat(p[inside] & 0xFF).isEqualTo(255);
        assertThat(p[inside + 1]).isEqualTo(p[inside]);
        assertThat(p[inside + 3]).as("alpha is the coverage").isEqualTo(p[inside]);
        int pad = ((y + 1) * 1024 + x + 1) * 4;
        assertThat(p[pad + 3]).as("the padding stays clear").isZero();
        assertThat(atlas.rasterizer()).isInstanceOf(JdkRasterizer.class);
    }

    @Test
    void rasterGlyphsFollowTheSettingsSizeAndSpace() {
        AtlasSettings settings = new AtlasSettings();
        GlyphAtlas atlas = rasterAtlas(new UiTextures(), null, null, settings);
        assertThat(settings.smallText().option()).isEqualTo("HINTED");
        assertThat(settings.hintedBelow().get()).isEqualTo(13);
        assertThat(atlas.rasterFor(12.9f, false)).isTrue();
        assertThat(atlas.rasterFor(13, false)).as("at the threshold: MTSDF").isFalse();
        assertThat(atlas.rasterFor(10, true)).as("world space: MTSDF").isFalse();
        Fakes.Face g = face(2);
        int[] phases = new int[GlyphAtlas.RASTER_PHASES];
        for (int p = 0; p < phases.length; p++) {
            phases[p] = atlas.rasterEntry(g, 40, 10, p);
        }
        assertThat(java.util.Arrays.stream(phases).distinct().count()).as("one entry per phase").isEqualTo(4);
        atlas.beginFrame();
        // a 4 px square moved by 3/4 px: its box reaches one pixel further right
        assertThat(atlas.plane(phases[3], 2)).isEqualTo(atlas.plane(phases[0], 2) + 1);
        assertThat(atlas.plane(phases[3], 0)).isEqualTo(atlas.plane(phases[0], 0));
        assertThat(atlas.raster(atlas.rasterEntry(g, 40, 10, 4))).as("no such phase: MTSDF").isFalse();
        assertThat(atlas.raster(atlas.rasterEntry(g, -1, 10, 0))).isFalse();
        assertThat(atlas.raster(atlas.rasterEntry(g, 0x1000000, 10, 0))).as("beyond a raster key's glyphs").isFalse();
        assertThat(atlas.raster(atlas.rasterEntry(g, 40, 10, -1))).isFalse();
        long v = settings.techniqueVersion();
        settings.smallText().select("MTSDF");
        assertThat(settings.techniqueVersion()).isGreaterThan(v);
        assertThat(atlas.rasterFor(10, false)).isFalse();
        settings.smallText().set(AtlasSettings.HINTED);
        settings.hintedBelow().set(9);
        assertThat(atlas.rasterFor(10, false)).isFalse();
        assertThat(settings.all()).contains(settings.smallText(), settings.hintedBelow());

        GlyphAtlas none = new GlyphAtlas(new UiTextures(), new JdkSdfGenerator(), null, null, new AtlasSettings());
        assertThat(none.rasterFor(10, false)).as("no rasterizer: MTSDF").isFalse();
        Fakes.Face f = face(1);
        int e = none.rasterEntry(f, 40, 10, 0);
        assertThat(e).isEqualTo(none.entry(f, 40));
        assertThat(none.raster(e)).isFalse();
        assertThat(atlas.raster(atlas.rasterEntry(f, 40, 0, 0))).as("no size: MTSDF").isFalse();
        assertThatThrownBy(() -> atlas.entry(face(0x8000), 40)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> atlas.entry(face(-1), 40)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rasterJobsOnLaneThreadsMatchASynchronousAtlas() throws Exception {
        UiTextures syncTextures = new UiTextures();
        GlyphAtlas sync = rasterAtlas(syncTextures, null, null, new AtlasSettings());
        UiTextures jobTextures = new UiTextures();
        try (TaskScheduler scheduler = new TaskScheduler()) {
            GlyphAtlas jobs = rasterAtlas(jobTextures, scheduler.lane(LaneSpec.platform("glyphs", 3)), null,
                new AtlasSettings());
            Fakes.Face f = face(1);
            for (int g = 10; g < 70; g += 3) {
                sync.entry(f, g);
                jobs.entry(f, g);
                sync.rasterEntry(f, g, 11, 0);
                jobs.rasterEntry(f, g, 11, 0);
            }
            sync.beginFrame();
            sync.finishAll();
            jobs.beginFrame();
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (jobs.pending() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(2);
                jobs.beginFrame();
            }
            assertThat(jobs.pending()).isZero();
            assertThat(jobs.state(jobs.rasterEntry(f, 13, 11, 0))).isEqualTo(GlyphAtlas.READY);
            assertThat(jobTextures.get(jobs.page(0).id()).pixels())
                .isEqualTo(syncTextures.get(sync.page(0).id()).pixels());
            jobs.rasterEntry(f, 32, 9, 0); // a space: empty without a job
            jobs.rasterEntry(f, 40, 9, 0);
            jobs.finishAll();
            assertThat(jobs.state(jobs.rasterEntry(f, 40, 9, 0))).isEqualTo(GlyphAtlas.READY);
            assertThat(jobs.state(jobs.rasterEntry(f, 32, 9, 0))).isEqualTo(GlyphAtlas.EMPTY);
        }
    }

    @Test
    void anUnchangedFrameWithRasterGlyphsAllocatesNothing() {
        GlyphAtlas atlas = rasterAtlas(new UiTextures(), null, null, new AtlasSettings());
        Fakes.Face f = face(1);
        int e = atlas.rasterEntry(f, 40, 10, 0);
        atlas.beginFrame();
        AllocationAssertions.assertAllocationFree(() -> {
            atlas.beginFrame();
            atlas.rasterEntry(f, 40, 10, 0);
            atlas.state(e);
        });
    }

    @Test
    void aFailingOrInklessRasterizerLeavesTheGlyphEmptyAndTheCacheHoldsNoRaster() throws Exception {
        GlyphRasterizer failing = (face, glyph, size, offset) -> {
            if (glyph == 41) {
                return null;
            }
            throw new IllegalStateException("broken");
        };
        GlyphCache cache = new GlyphCache(40, 6);
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), new JdkSdfGenerator(), failing, null, cache,
            new AtlasSettings());
        Fakes.Face f = face(1);
        int broken = atlas.rasterEntry(f, 40, 10, 0);
        int inkless = atlas.rasterEntry(f, 41, 10, 0);
        atlas.beginFrame();
        assertThat(atlas.state(broken)).isEqualTo(GlyphAtlas.EMPTY);
        assertThat(atlas.state(inkless)).isEqualTo(GlyphAtlas.EMPTY);
        GlyphAtlas good = rasterAtlas(new UiTextures(), null, cache, new AtlasSettings());
        int r = good.rasterEntry(f, 40, 10, 0);
        good.beginFrame();
        assertThat(good.state(r)).isEqualTo(GlyphAtlas.READY);
        assertThat(cache.size()).as("raster glyphs are not cached").isZero();
    }

    @Test
    void pastItsPageBudgetTheLeastRecentlyUsedPageNoTextDrawsIsEvicted() {
        AtlasSettings settings = new AtlasSettings();
        settings.pageSize().set(256);
        settings.maxPages().set(2);
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), new JdkSdfGenerator(), null, null, settings);
        Fakes.Face f = face(1);
        int a = atlas.entry(f, 400); // 4 em squares: one fills a 256-texel page
        atlas.beginFrame();
        int b = atlas.entry(f, 401);
        atlas.beginFrame();
        assertThat(atlas.pageCount()).isEqualTo(2);
        assertThat(atlas.texture(a)).isNotEqualTo(atlas.texture(b));
        int pageA = atlas.texture(a);
        int generation = atlas.generation();

        int c = atlas.entry(f, 402); // a third page past the budget: a's (least recently used) is evicted
        atlas.beginFrame();
        assertThat(atlas.pageCount()).isEqualTo(2);
        assertThat(atlas.evictions()).isEqualTo(1);
        assertThat(atlas.generation()).isGreaterThan(generation);
        assertThat(atlas.state(a)).isEqualTo(GlyphAtlas.EVICTED);
        assertThat(atlas.state(b)).isEqualTo(GlyphAtlas.READY);
        assertThat(atlas.state(c)).isEqualTo(GlyphAtlas.READY);
        assertThat(atlas.texture(c)).isEqualTo(pageA);

        // the page text draws is never evicted: with no other page to give up, the atlas grows past its budget
        atlas.pageGuard(id -> id == atlas.texture(b));
        int d = atlas.entry(f, 403);
        atlas.beginFrame();
        assertThat(atlas.state(d)).isEqualTo(GlyphAtlas.READY);
        assertThat(atlas.pageCount()).isEqualTo(3);
        assertThat(atlas.state(b)).isEqualTo(GlyphAtlas.READY);

        // an evicted glyph looked up again keeps its entry and is generated again, evicting c's page (not drawn,
        // used before d's)
        assertThat(atlas.entry(f, 400)).isEqualTo(a);
        assertThat(atlas.state(a)).isEqualTo(GlyphAtlas.QUEUED);
        atlas.beginFrame();
        assertThat(atlas.state(a)).isEqualTo(GlyphAtlas.READY);
        assertThat(atlas.state(c)).isEqualTo(GlyphAtlas.EVICTED);
        assertThat(atlas.state(d)).isEqualTo(GlyphAtlas.READY);
        assertThat(atlas.evictions()).isEqualTo(2);
    }

    @Test
    void iconsAreGlyphsOfTheirFaceAndNeverCached() throws Exception {
        GlyphCache cache = new GlyphCache(40, 6);
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), new JdkSdfGenerator(), null, cache, new AtlasSettings());
        IconFace icons = new IconFace();
        int square = icons.add(SvgIcon.parse("<svg viewBox='0 0 10 10'><rect x='2' y='2' width='6' height='6'/></svg>"));
        int e = atlas.entry(icons, square);
        atlas.finishAll();
        assertThat(atlas.state(e)).isEqualTo(GlyphAtlas.READY);
        assertThat(atlas.plane(e, 0)).isLessThan(0.2f); // the square from 0.2 to 0.8 em, plus the range's padding
        assertThat(atlas.plane(e, 2)).isGreaterThan(0.8f);
        assertThat(icons.outline(5, new GlyphOutline())).isFalse();
        assertThat(icons.count()).isEqualTo(1);
        assertThat(icons.id()).isEqualTo(IconFace.ID);
        assertThat(icons.source()).isNull();
        assertThat(icons.covers('a')).isFalse();
        assertThat(icons.ascender()).isEqualTo(1);
        assertThat(icons.descender() + icons.lineGap()).isZero();
        assertThat(cache.size()).isZero();
    }
}
