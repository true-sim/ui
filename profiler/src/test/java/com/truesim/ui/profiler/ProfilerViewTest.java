package com.truesim.ui.profiler;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.LayoutSanity;
import com.truesim.ui.testkit.SyntheticInput;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The profiler's views in both themes: the gallery laid out sanely and named, every part drawing, the test theme
 * restyling it, interaction (zoom, pan, selection, sorting, flame focus, going live), and frames allocation-free while
 * frames stream in; CPU previews with {@code TRUESIM_SCREENSHOTS=true}.
 */
class ProfilerViewTest {

    private static StyleSheet theme(String name) {
        return name.equals("default") ? Tss.defaultTheme() : Tss.testTheme();
    }

    /** The real text path over the bundled fonts, as the screenshots use. */
    static Ui ui(String theme) {
        NativeTextBackend backend = new NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        UiTextures textures = new UiTextures();
        GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
            new AtlasSettings());
        return new Ui(theme(theme), new TextSystem(fonts, backend.newShaper(), atlas), textures);
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void theGalleryIsSaneNamedAndDrawsEveryPart(String theme) throws Exception {
        Ui ui = ui(theme);
        ProfilerGallery g = ProfilerGallery.build(ui);
        ProfilerGallery.settle(ui, ProfilerGallery.WIDTH, ProfilerGallery.HEIGHT);
        preview(ui, theme, "");
        LayoutSanity.assertSane(ui, "profiler gallery (" + theme + ")");
        assertThat(Semantics.unnamed(Semantics.of(ui.store(), ui.interaction()))).isEmpty();
        assertThat(g.profiler.timeline().drawnZones()).isGreaterThan(50);
        assertThat(g.profiler.flameGraph().tree().count()).isGreaterThan(5);
        assertThat(g.profiler.zoneTable().stats().rows()).isGreaterThan(5);
        assertThat(g.frameGraph.statsText().toString()).contains("CPU").contains("GPU").contains("hitches 1");
        assertThat(g.frameGraph.chart().y().range().max()).isGreaterThan(16.6);
        assertThat(kinds(ui)).contains(Primitive.BOX, Primitive.GLYPH, Primitive.LINE);
        g.pose(ui);
        preview(ui, theme, "-posed");
        LayoutSanity.assertSane(ui, "profiler gallery posed (" + theme + ")");
        assertThat(g.profiler.statusText().toString()).contains("UPDATE");
        assertThat(g.profiler.flameGraph().tree().time(0))
            .isEqualTo(g.recording.frameStart(238) - g.recording.frameStart(237));
    }

    @Test
    void theTestThemeRestylesTheViews() {
        Ui a = ui("default");
        Ui b = ui("test");
        ProfilerGallery ga = ProfilerGallery.build(a);
        ProfilerGallery gb = ProfilerGallery.build(b);
        ProfilerGallery.settle(a, ProfilerGallery.WIDTH, ProfilerGallery.HEIGHT);
        ProfilerGallery.settle(b, ProfilerGallery.WIDTH, ProfilerGallery.HEIGHT);
        assertThat(colors(b)).isNotEqualTo(colors(a));
        assertThat(b.layout().width(gb.frameGraph.element().slot()))
            .isNotEqualTo(a.layout().width(ga.frameGraph.element().slot()));
    }

    @Test
    void theFrameGraphOverlayIsOpaqueInTheDefaultTheme() {
        Ui ui = ui("default");
        ProfilerGallery g = ProfilerGallery.build(ui);
        ProfilerGallery.settle(ui, ProfilerGallery.WIDTH, ProfilerGallery.HEIGHT);
        com.truesim.ui.style.value.Paint fill = ui.styles().styleOf(g.frameGraph.element().slot())
            .get(com.truesim.ui.style.Styles.BACKGROUND);
        assertThat(fill).isInstanceOf(com.truesim.ui.style.value.Paint.Box.class);
        assertThat(Rgba.a(((com.truesim.ui.style.value.Paint.Box) fill).color().rgba())).isEqualTo(1f);
    }

    @Test
    void theTimelineZoomsPansSelectsAndGoesLive() {
        Ui ui = ui("default");
        ProfilerGallery g = ProfilerGallery.build(ui);
        SyntheticInput in = new SyntheticInput();
        UiInput input = new UiInput(16);
        Runner run = new Runner(ui, in, input);
        run.settle();
        TimelineView tl = g.profiler.timeline();
        assertThat(tl.live()).isFalse();
        float cx = tl.pointerX(tl.pixelOf(g.recording.frameStart(237) + 1_000_000));
        float cy = tl.pointerY(tl.laneTopOf(g.mainLane) + 2);
        long span = tl.viewEnd() - tl.viewStart();
        in.mouse(cx, cy, 0);
        run.frame();
        in.scroll(SyntheticInput.MOUSE, 0, 2);
        run.frame();
        assertThat(tl.viewEnd() - tl.viewStart()).isLessThan(span);
        long start = tl.viewStart();
        in.mouse(cx, cy, Pointer.PRIMARY);
        run.frame();
        in.mouse(cx - 100, cy, Pointer.PRIMARY);
        run.frame();
        in.mouse(cx - 100, cy, 0);
        run.frame();
        assertThat(tl.viewStart()).isGreaterThan(start);
        // a click selects the zone under it
        float zx = tl.pointerX(tl.pixelOf(g.recording.frameStart(237) + 1_000_000));
        in.mouse(zx, cy, Pointer.PRIMARY);
        run.frame();
        in.mouse(zx, cy, 0);
        run.frame();
        assertThat(tl.selectedLane()).isEqualTo(g.mainLane);
        assertThat(tl.selectedZone()).isGreaterThanOrEqualTo(0);
        assertThat(g.recording.zoneDepth(g.mainLane, tl.selectedZone())).isZero();
        // the ruler selects a frame
        float ry = tl.pointerY(tl.top() + 3);
        in.mouse(zx, ry, Pointer.PRIMARY);
        run.frame();
        in.mouse(zx, ry, 0);
        run.frame();
        assertThat(tl.hasSpan()).isTrue();
        long f = g.recording.frameAt(tl.spanFrom());
        assertThat(tl.spanTo()).isEqualTo(g.recording.frameStart(f + 1));
        // reset goes live
        ui.interaction().focus(tl.element(), 0);
        in.press(UiCommand.CHART_RESET);
        run.frame();
        assertThat(tl.live()).isTrue();
        assertThat(tl.viewEnd()).isEqualTo(g.recording.endTime());
    }

    @Test
    void theTableSortsByItsHeadersAndTheFlameGraphFocusesANode() {
        Ui ui = ui("default");
        ProfilerGallery g = ProfilerGallery.build(ui);
        SyntheticInput in = new SyntheticInput();
        Runner run = new Runner(ui, in, new UiInput(16));
        run.settle();
        ZoneTableView table = g.profiler.zoneTable();
        g.profiler.tabs().select(1);
        run.settle();
        assertThat(table.stats().sortedBy()).isEqualTo(ZoneStats.Column.TOTAL);
        float hx = table.pointerX(table.left() + 4);
        float hy = table.pointerY(table.top() + 4);
        in.mouse(hx, hy, Pointer.PRIMARY);
        run.frame();
        in.mouse(hx, hy, 0);
        run.frame();
        assertThat(table.stats().sortedBy()).isEqualTo(ZoneStats.Column.NAME);
        assertThat(table.stats().descending()).isFalse();
        g.profiler.tabs().select(0);
        run.settle();
        FlameGraphView flame = g.profiler.flameGraph();
        int node = 1;
        float nx = flame.left() + (float) ((flame.tree().offset(node) + flame.tree().time(node) * 0.5)
            * (flame.right() - flame.left()) / flame.tree().time(0));
        float ny = flame.top() + flame.rowPixels() * 1.5f;
        assertThat(flame.nodeAt(nx, ny)).isEqualTo(node);
        in.mouse(flame.pointerX(nx), flame.pointerY(ny), Pointer.PRIMARY);
        run.frame();
        in.mouse(flame.pointerX(nx), flame.pointerY(ny), 0);
        run.frame();
        assertThat(flame.focus()).isEqualTo(node);
        float tx = flame.pointerX(flame.left() + 5);
        float ty = flame.pointerY(flame.top() + 3);
        in.mouse(tx, ty, Pointer.PRIMARY);
        run.frame();
        in.mouse(tx, ty, 0);
        run.frame();
        assertThat(flame.focus()).isZero();
    }

    @Test
    void streamingFramesIntoTheViewsAllocatesNothing() {
        Ui ui = ui("default");
        ProfilerGallery g = ProfilerGallery.build(ui);
        g.profiler.timeline().live(true);
        ProfilerTunables.STATS_INTERVAL.set(0); // every frame: the worst case
        SyntheticInput in = new SyntheticInput();
        UiInput input = new UiInput(16);
        Runner run = new Runner(ui, in, input);
        run.settle();
        Recording r = g.recording;
        int main = g.mainLane;
        int name = r.name("UPDATE");
        int gpu = g.graphicsLane;
        long[] frame = {r.endFrame()};
        long[] t = {r.endTime() + 1_000_000};
        Runnable step = () -> {
            long f = frame[0]++;
            long s = t[0];
            long z = r.open(main, s, name, 0);
            r.zone(main, s + 1000, s + 400_000, name, 1);
            r.close(main, z, s + 5_000_000);
            r.zone(gpu, s + 5_100_000, s + 9_000_000, name, 0);
            r.frame(f, s, s + 5_000_000, 300_000, f % 97 == 0);
            r.gpuFrame(f, s + 5_100_000, s + 9_000_000);
            r.retain(2_000_000_000L);
            r.commit();
            g.times.frame(f, 5, 0.3, 16.6, f % 97 == 0);
            g.times.gpu(f, 3.9);
            t[0] = s + 16_600_000;
            run.frame();
        };
        try {
            for (int i = 0; i < 3000; i++) {
                step.run(); // rings and blocks grow to their size
            }
            // frames that allocate, counted (pitfalls.md: one in a few hundred is the compiler or a text buffer
            // growing to a longer label, e.g. a ruler's time gaining a digit)
            com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            int allocating = 0;
            for (int i = 0; i < 3000; i++) {
                long before = mx.getCurrentThreadAllocatedBytes();
                step.run();
                if (mx.getCurrentThreadAllocatedBytes() != before) {
                    allocating++;
                }
            }
            assertThat(allocating).as("frames allocating of 3000").isLessThanOrEqualTo(10);
        } finally {
            ProfilerTunables.STATS_INTERVAL.set(0.5f);
        }
    }

    @Test
    void theFrameGraphAloneStreamsWithoutAllocating() {
        Ui ui = ui("default");
        FrameTimes times = new FrameTimes(ui.store().clock(), 300);
        FrameGraphView graph = new FrameGraphView(ui, times).budgets(60, 144);
        ui.root().add(graph.element());
        Runner run = new Runner(ui, new SyntheticInput(), new UiInput(4));
        long[] n = {0};
        Runnable step = () -> {
            long f = n[0]++;
            times.frame(f, 4 + (f % 7) * 0.3, 0.2, 16.6 + (f % 5) * 0.1, f % 200 == 0);
            if (f > 2) {
                times.gpu(f - 2, 3.5);
            }
            run.frame();
        };
        for (int i = 0; i < 2000; i++) {
            step.run();
        }
        assertThat(graph.budgetCount()).isEqualTo(2);
        assertThat(graph.cpuStats().count).isEqualTo(300);
        assertAllocationFree(step, 200, 300, 3);
    }

    private static Set<Long> colors(Ui ui) {
        Set<Long> set = new HashSet<>();
        for (int l = 0; l < ui.layerCount(); l++) {
            var list = ui.layers()[l];
            for (int i = 0; i < list.size(); i++) {
                set.add(list.colorAt(i, Primitive.COLOR_A));
            }
        }
        return set;
    }

    private static Set<Integer> kinds(Ui ui) {
        Set<Integer> kinds = new HashSet<>();
        for (int l = 0; l < ui.layerCount(); l++) {
            var list = ui.layers()[l];
            for (int i = 0; i < list.size(); i++) {
                kinds.add(Primitive.kind(list.header(i)));
            }
        }
        return kinds;
    }

    private static void preview(Ui ui, String theme, String suffix) throws Exception {
        Path dir = CpuPreview.dir();
        if (dir != null) {
            long bg = theme.equals("default") ? Rgba.srgb(13, 17, 23, 1) : Rgba.srgb(43, 0, 64, 1);
            new CpuPreview(ProfilerGallery.WIDTH, ProfilerGallery.HEIGHT, bg).draw(ui.layers(), ui.layerCount(),
                ui.textures()).save(dir.resolve("profiler-" + theme + suffix + ".png"));
        }
    }

    /** Runs frames 16 ms apart with synthetic input. */
    static final class Runner {
        final Ui ui;
        final SyntheticInput in;
        final UiInput input;
        long now = 1_000_000_000L;

        Runner(Ui ui, SyntheticInput in, UiInput input) {
            this.ui = ui;
            this.in = in;
            this.input = input;
        }

        void frame() {
            input.beginFrame();
            in.poll(input);
            ui.update(ProfilerGallery.WIDTH, ProfilerGallery.HEIGHT, input, now += 16_000_000L);
        }

        void settle() {
            int quiet = 0;
            for (int i = 0; i < 400; i++) {
                frame();
                if (i > 10 && ProfilerGallery.quiet(ui) && ui.recorded() == 0 && ++quiet >= 3) {
                    return;
                }
            }
        }
    }
}
