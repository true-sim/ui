package com.truesim.ui.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.jobs.Lane;
import com.truesim.common.jobs.LaneSpec;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
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
        assertThat(settings.all()).hasSize(4);
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
}
