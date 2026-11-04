package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.LayoutSanity;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * SVG icons from resources ({@code icons/test-gear.svg}, {@code test-star.svg}, {@code test-heart.svg}, written for
 * the tests): drawn as MTSDF glyphs of the text atlas, tinted by the style, fitted and measured as images.
 */
class SvgIconsTest {

    static final StyleSheet SHEET = Tss.compileString("svg-icons.tss", """
        root { layout: flex; direction: row; align-items: start; gap: 8px; padding: 8px; }
        image.gear { image: "icons/test-gear" #ff0000 contain; width: 96px; height: 96px; }
        image.small { image: "icons/test-gear" #ffffff contain; width: 16px; height: 16px; }
        image.star { image: "icons/test-star" #00ff00; }
        image.heart { image: "icons/test-heart" #ff66aa contain; width: 120px; height: 120px; }
        image.missing { image: "icons/no-such-icon"; width: 20px; height: 20px; }
        image.builtin { image: "icons/close" #ffffff; width: 16px; height: 16px; }
        """);

    @Test
    void svgResourcesDrawAsTintedGlyphsOfTheAtlas() throws Exception {
        Ui ui = new Ui(SHEET, Fixture.newJdkText());
        Element gear = ui.image("gear");
        Element small = ui.image("small");
        Element star = ui.image("star");
        Element heart = ui.image("heart");
        Element missing = ui.image("missing");
        Element builtin = ui.image("builtin");
        for (Element e : new Element[] {gear, small, star, heart, missing, builtin}) {
            ui.root().add(e);
        }
        for (int i = 0; i < 50 && (i < 2 || ui.textSystem().atlas().pending() > 0 || ui.recorded() > 0); i++) {
            ui.update(500, 160);
        }
        DrawList list = ui.layers()[0];

        float[] g = glyph(ui, list, gear);
        assertThat(g).as("the gear draws a glyph").isNotNull();
        assertThat(g[4]).as("tinted red").isEqualTo(1);
        assertThat(g[5]).isZero();
        float x = ui.layout().x(gear.slot());
        float y = ui.layout().y(gear.slot());
        // the 24-unit view box fills the 96 px box: the glyph quad (with its distance range's padding) around it
        assertThat(g[0]).isLessThanOrEqualTo(x + 4).isGreaterThan(x - 20);
        assertThat(g[3]).isGreaterThanOrEqualTo(y + 92).isLessThan(y + 116);
        assertThat(ui.textSystem().atlas().texture(0)).isPositive();

        assertThat(ui.layout().width(star.slot())).as("measured at its view box").isEqualTo(32);
        assertThat(ui.layout().height(star.slot())).isEqualTo(32);
        assertThat(glyph(ui, list, star)[5]).as("green").isEqualTo(1);
        float[] h = glyph(ui, list, heart);
        assertThat(h[2] - h[0]).as("2:1, contained in the square").isGreaterThan(110);
        assertThat(h[3] - h[1]).isLessThan(80);
        assertThat(glyph(ui, list, small)).isNotNull();

        assertThat(glyph(ui, list, missing)).as("no such image: nothing").isNull();
        assertThat(ui.images().icon("icons/no-such-icon")).isEqualTo(-1);
        assertThat(ui.images().icon("icons/close")).as("a built-in icon stays a texture").isEqualTo(-1);
        assertThat(primitives(ui, list, builtin, Primitive.IMAGE)).isEqualTo(1);
        assertThat(ui.images().icon("icons/test-gear")).isEqualTo(ui.images().icon("icons/test-gear"));
        assertThat(ui.images().iconFace().count()).isEqualTo(3);
        LayoutSanity.assertSane(ui, "SVG icons");

        if ("true".equals(System.getenv("TRUESIM_SCREENSHOTS"))) {
            String dir = System.getenv().getOrDefault("TRUESIM_SCREENSHOT_DIR", "build/screenshots");
            new CpuPreview(500, 160, Rgba.srgb(13, 17, 23, 1)).draw(ui.layers(), ui.layerCount(), ui.textures())
                .save(Path.of(dir, "svg-icons.png"));
        }
    }

    @Test
    void anIconAddedInCodeAndOneLoadedFromAFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Ui ui = new Ui(SHEET, Fixture.newJdkText());
        int a = ui.images().addSvg("icons/test-star", "<svg viewBox='0 0 10 20'><rect width='10' height='20'/></svg>");
        assertThat(ui.images().icon("icons/test-star")).as("registered before the resource is looked for").isEqualTo(a);
        assertThat(ui.images().svg(a).aspect()).isEqualTo(0.5f);
        Path file = dir.resolve("dot.svg");
        java.nio.file.Files.writeString(file, "<svg viewBox='0 0 4 4'><circle cx='2' cy='2' r='2'/></svg>");
        int b = ui.images().loadSvg("dot", file);
        assertThat(ui.images().icon("dot")).isEqualTo(b);
        ui.images().svgResources(null);
        assertThat(ui.images().icon("icons/test-heart")).as("no resources to look in").isEqualTo(-1);
        ui.images().svgResources(SvgIconsTest.class.getClassLoader());
        assertThat(ui.images().icon("icons/test-heart")).isNotNegative();
        assertThat(ui.images().icon("")).isEqualTo(-1);
        assertThat(ui.images().icon(null)).isEqualTo(-1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ui.images().loadSvg("x", dir.resolve("none.svg")))
            .isInstanceOf(java.io.UncheckedIOException.class);
    }

    /** The first glyph with ink in {@code e}'s range: x0 y0 x1 y1, then its colour's r g b. */
    private static float[] glyph(Ui ui, DrawList list, Element e) {
        int start = ui.store().rangeStart(e.slot());
        for (int i = start; i < start + ui.store().rangeCapacity(e.slot()); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.GLYPH
                && list.floatAt(i, Primitive.X1) > list.floatAt(i, Primitive.X0)) {
                long c = list.colorAt(i, Primitive.COLOR_A);
                return new float[] {list.drawnX(i, Primitive.X0), list.drawnY(i, Primitive.Y0),
                    list.drawnX(i, Primitive.X1), list.drawnY(i, Primitive.Y1), Rgba.r(c), Rgba.g(c), Rgba.b(c)};
            }
        }
        return null;
    }

    private static int primitives(Ui ui, DrawList list, Element e, int kind) {
        int n = 0;
        int start = ui.store().rangeStart(e.slot());
        for (int i = start; i < start + ui.store().rangeCapacity(e.slot()); i++) {
            n += Primitive.kind(list.header(i)) == kind ? 1 : 0;
        }
        return n;
    }
}
