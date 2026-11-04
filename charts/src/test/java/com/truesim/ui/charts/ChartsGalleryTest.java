package com.truesim.ui.charts;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.testkit.LayoutSanity;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The demo page in both themes: laid out sanely (nothing escaping its clip, no text leaving its box or over other
 * text), every chart drawing its series, axes and legend (never over its data), the test theme changing how it looks;
 * and, with {@code TRUESIM_SCREENSHOTS=true}, CPU previews of it (plain and posed: hovered, a legend entry hidden, an
 * axis selected) in {@code TRUESIM_SCREENSHOT_DIR} (default {@code build/screenshots}).
 */
class ChartsGalleryTest {

    private static Rig rig(String theme) {
        Rig r = new Rig(theme.equals("default") ? Rig.DEFAULT : Rig.GARISH, Rig.nativeText());
        r.width = ChartsGallery.WIDTH;
        r.height = ChartsGallery.HEIGHT;
        return r;
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void theGalleryIsSaneAndDrawsEveryChart(String theme) throws Exception {
        Rig r = rig(theme);
        ChartsGallery g = ChartsGallery.build(r.ui);
        r.settle();
        preview(r, g, theme);
        LayoutSanity.assertSane(r.ui, "charts gallery (" + theme + ")");
        for (Chart c : g.charts()) {
            assertThat(c.plotRight() - c.plotLeft()).as(c.toString()).isGreaterThan(40);
            assertThat(c.plotBottom() - c.plotTop()).as(c.toString()).isGreaterThan(30);
            assertThat(c.series()).isNotEmpty();
        }
        assertThat(kinds(r.ui)).contains(Primitive.LINE, Primitive.TRIANGLE, Primitive.BOX, Primitive.IMAGE,
            Primitive.GLYPH);
        assertThat(g.grouped.legendCount()).isEqualTo(3);
        noLegendOverData(g, theme);
        assertThat(Semantics.unnamed(Semantics.of(r.ui.store(), r.ui.interaction()))).isEmpty();
        g.pose(r.ui, ChartsGallery.WIDTH, ChartsGallery.HEIGHT);
        previewPosed(r, theme);
        assertThat(g.linkedA.crosshairShown()).isTrue();
        assertThat(g.linkedB.crosshairShown()).as("the linked chart shows the shared crosshair").isTrue();
        assertThat(g.linkedA.highlights()).isGreaterThan(0);
        assertThat(g.bars.itemEnabled(2)).isFalse();
        LayoutSanity.assertSane(r.ui, "charts gallery posed (" + theme + ")");
        noLegendOverData(g, theme);
    }

    /** No chart's legend box covers a sample or shape of its series inside the plot. */
    private static void noLegendOverData(ChartsGallery g, String theme) {
        for (Chart c : g.charts()) {
            if (c.legendCount() > 0) {
                assertThat(LegendTest.dataUnderLegend(c)).as(c.title() + " (" + theme + "): data under the legend")
                    .isFalse();
            }
        }
    }

    private static java.util.Set<Integer> kinds(Ui ui) {
        java.util.Set<Integer> kinds = new java.util.HashSet<>();
        for (int l = 0; l < ui.layerCount(); l++) {
            var list = ui.layers()[l];
            for (int i = 0; i < list.size(); i++) {
                kinds.add(Primitive.kind(list.header(i)));
            }
        }
        return kinds;
    }

    private static void preview(Rig r, ChartsGallery g, String theme) throws Exception {
        if (!"true".equals(System.getenv("TRUESIM_SCREENSHOTS"))) {
            return;
        }
        String dir = System.getenv().getOrDefault("TRUESIM_SCREENSHOT_DIR", "build/screenshots");
        long bg = theme.equals("default") ? Rgba.srgb(13, 17, 23, 1) : Rgba.srgb(43, 0, 64, 1);
        new CpuPreview(ChartsGallery.WIDTH, ChartsGallery.HEIGHT, bg).draw(r.ui.layers(), r.ui.layerCount(),
            r.ui.textures()).save(Path.of(dir, "charts-gallery-" + theme + ".png"));
    }

    private static void previewPosed(Rig r, String theme) throws Exception {
        if (!"true".equals(System.getenv("TRUESIM_SCREENSHOTS"))) {
            return;
        }
        String dir = System.getenv().getOrDefault("TRUESIM_SCREENSHOT_DIR", "build/screenshots");
        long bg = theme.equals("default") ? Rgba.srgb(13, 17, 23, 1) : Rgba.srgb(43, 0, 64, 1);
        new CpuPreview(ChartsGallery.WIDTH, ChartsGallery.HEIGHT, bg).draw(r.ui.layers(), r.ui.layerCount(),
            r.ui.textures()).save(Path.of(dir, "charts-gallery-posed-" + theme + ".png"));
    }
}
