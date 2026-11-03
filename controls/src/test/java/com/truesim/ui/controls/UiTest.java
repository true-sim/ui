package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.StyleWatcher;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.testkit.DrawListDump;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.jdk.JdkTextBackend;
import com.truesim.ui.core.draw.UiTextures;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiTest {

    @TempDir
    Path dir;

    static TextSystem jdkText() {
        JdkTextBackend backend = new JdkTextBackend();
        FontRegistry fonts = FontRegistry.system(backend);
        assumeTrue(fonts.match("sans-serif", 400, false) != null, "no system font");
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), backend.msdfGenerator(), null, null, new AtlasSettings());
        return new TextSystem(fonts, backend.newShaper(), atlas);
    }

    /** The real text path: FreeType, HarfBuzz, msdfgen (the JDK fallback allocates while shaping). */
    static TextSystem nativeText() {
        com.truesim.ui.text.natives.NativeTextBackend backend = new com.truesim.ui.text.natives.NativeTextBackend();
        FontRegistry fonts = FontRegistry.system(backend);
        assumeTrue(fonts.match("sans-serif", 400, false) != null, "no system font");
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), backend.msdfGenerator(), null, null, new AtlasSettings());
        return new TextSystem(fonts, backend.newShaper(), atlas);
    }

    /** Frames until every glyph is in the atlas. */
    static void settle(Ui ui, float w, float h) {
        for (int i = 0; i < 200; i++) {
            ui.update(w, h);
            if (ui.textSystem() == null || ui.textSystem().atlas().pending() == 0 && ui.recorded() == 0) {
                return;
            }
        }
        throw new AssertionError("glyphs never settled");
    }

    private static Ui boxes(StyleSheet sheet) {
        Ui ui = new Ui(sheet, null);
        Element hud = ui.panel("hud");
        Element card = ui.panel("card");
        Element button = ui.panel("primary");
        Element meter = ui.panel("meter");
        Element fill = ui.panel(null);
        ui.root().add(hud);
        hud.add(card);
        card.add(button).add(meter);
        meter.add(fill);
        ui.styles().inline(fill.slot(), com.truesim.ui.style.InlineStyle.builder()
            .set(com.truesim.ui.style.Styles.WIDTH, com.truesim.ui.style.value.Length.percent(60)).build());
        button.semantics(Role.BUTTON, "Go");
        return ui;
    }

    @Test
    void panelsRecordStyledBoxesAsGoldenData() {
        Ui ui = boxes(Tss.defaultTheme());
        ui.update(800, 600);
        DrawListDump.assertMatches(ui.layers()[0], Path.of("src/test/resources/goldens/boxes-default.txt"));
        String text = DrawListDump.of(ui.layers()[0]);
        assertThat(text).contains("shadow").contains("box");
        assertThat(ui.layerCount()).isEqualTo(1);
    }

    @Test
    void anUnchangedFrameRecordsAndUploadsNothing() {
        Ui ui = boxes(Tss.defaultTheme());
        ui.update(800, 600);
        DrawList list = ui.layers()[0];
        list.clean();
        long version = list.version();
        ui.update(800, 600);
        assertThat(ui.recorded()).isZero();
        assertThat(list.dirtyFrom()).isEqualTo(list.dirtyTo());
        assertThat(list.version()).isEqualTo(version);
        assertThat(ui.frames()).isEqualTo(2);
    }

    @Test
    void aStateChangeReResolvesAndRecordsOnlyThatElement() {
        Ui ui = boxes(Tss.defaultTheme());
        ui.update(800, 600);
        DrawList list = ui.layers()[0];
        list.clean();
        Element card = ui.store().element(ui.store().firstChild(ui.store().firstChild(ui.store().rootSlot())));
        int start = ui.store().rangeStart(card.slot());
        card.state(UiState.HOVER, true);
        ui.update(800, 600);
        assertThat(ui.recorded()).isEqualTo(1);
        assertThat(list.dirtyFrom()).isGreaterThanOrEqualTo(start);
        assertThat(list.dirtyTo()).isLessThanOrEqualTo(start + ui.store().rangeCapacity(card.slot()));
    }

    @Test
    void theTestThemeRestylesEveryElement() {
        Ui plain = boxes(Tss.defaultTheme());
        Ui garish = boxes(Tss.testTheme());
        plain.update(800, 600);
        garish.update(800, 600);
        String a = DrawListDump.of(plain.layers()[0]);
        String b = DrawListDump.of(garish.layers()[0]);
        assertThat(a).isNotEqualTo(b);
        DrawListDump.assertMatches(garish.layers()[0], Path.of("src/test/resources/goldens/boxes-test-theme.txt"));
    }

    @Test
    void switchingTokenSetsCostsOneReResolution() {
        Ui ui = boxes(Tss.defaultTheme());
        ui.update(800, 600);
        String dark = DrawListDump.of(ui.layers()[0]);
        ui.styles().tokens().use("light");
        ui.update(800, 600);
        String light = DrawListDump.of(ui.layers()[0]);
        assertThat(light).isNotEqualTo(dark);
        int recorded = ui.recorded();
        assertThat(recorded).isPositive();
        ui.update(800, 600);
        assertThat(ui.recorded()).isZero();
        ui.styles().tokens().set("accent", Color.rgb(255, 0, 255));
        ui.update(800, 600);
        assertThat(DrawListDump.of(ui.layers()[0])).isNotEqualTo(light);
    }

    @Test
    void aDesignerRestylesLiveFromAFile() throws Exception {
        Path sheet = dir.resolve("hud.tss");
        Files.writeString(sheet, "panel.card { background: box(#ff0000); padding: 4px; }\n");
        try (StyleWatcher watcher = StyleWatcher.file(sheet).start()) {
            Ui ui = new Ui(watcher.sheet(), null);
            Element card = ui.panel("card");
            ui.root().add(card);
            ui.watch(watcher);
            ui.update(100, 100);
            long red = ui.layers()[0].colorAt(ui.store().rangeStart(card.slot()), Primitive.COLOR_A);
            assertThat(Rgba.r(red)).isEqualTo(1);
            Files.writeString(sheet, "panel.card { background: box(#0000ff); padding: 4px; }\n");
            Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
            long color = red;
            while (color == red && Instant.now().isBefore(deadline)) {
                Thread.sleep(20);
                ui.update(100, 100);
                color = ui.layers()[0].colorAt(ui.store().rangeStart(card.slot()), Primitive.COLOR_A);
            }
            assertThat(Rgba.b(color)).isEqualTo(1);
            // a broken file keeps the last good sheet
            Files.writeString(sheet, "panel.card { background: box(#00ff00) \n");
            Thread.sleep(500);
            ui.update(100, 100);
            assertThat(ui.layers()[0].colorAt(ui.store().rangeStart(card.slot()), Primitive.COLOR_A)).isEqualTo(color);
        }
    }

    @Test
    void labelsShapeMeasureAndRecordGlyphs() {
        TextSystem text = jdkText();
        Ui ui = new Ui(Tss.defaultTheme(), text);
        Element hud = ui.panel("hud");
        Element card = ui.panel("card");
        Element title = ui.label("title", "Health");
        Element value = ui.label("value", "100");
        ui.root().add(hud);
        hud.add(card);
        card.add(title).add(value);
        settle(ui, 800, 600);
        DrawList list = ui.layers()[0];
        int glyphs = 0;
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.GLYPH) {
                glyphs++;
            }
        }
        assertThat(glyphs).isEqualTo(9);
        assertThat(ui.layout().width(title.slot())).isPositive();
        assertThat(ui.text(value).toString()).isEqualTo("100");

        // a counter's digits re-record the label alone; more digits than the slack rebuild in tree order
        list.clean();
        ui.text(value, "99");
        ui.update(800, 600);
        assertThat(ui.recorded()).isGreaterThanOrEqualTo(1);
        ui.text(value, "99"); // same text: nothing
        settle(ui, 800, 600);
        ui.text(value, "123456789012345678901234567890");
        settle(ui, 800, 600);
        glyphs = 0;
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.GLYPH) {
                glyphs++;
            }
        }
        assertThat(glyphs).isEqualTo(36);
        assertThatThrownBy(() -> ui.text(card, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Semantics.of(ui.store()).toString()).contains("TEXT \"Health\"");
        assertThat(ui.tunables()).extracting(t -> t.name()).contains("ui.scale", "ui.text.atlas.emSize");
    }

    @Test
    void aFrameWithTenPercentChangingAllocatesNothing() {
        TextSystem text = nativeText();
        Ui ui = new Ui(Tss.defaultTheme(), text);
        Element hud = ui.panel("hud");
        ui.root().add(hud);
        Element[] values = new Element[10];
        for (int i = 0; i < values.length; i++) {
            Element card = ui.panel("card");
            hud.add(card);
            card.add(ui.label("caption", "Item " + i));
            values[i] = ui.label("value", "0");
            card.add(values[i]);
        }
        StringBuilder digits = new StringBuilder();
        // warm every digit into the atlas
        ui.text(values[0], "0123456789");
        settle(ui, 1280, 720);
        int[] frame = {0};
        AllocationAssertions.assertAllocationFree(() -> {
            int f = frame[0]++;
            digits.setLength(0);
            digits.append(f % 1000);
            ui.text(values[f % values.length], digits);
            ui.update(1280, 720);
        });
        AllocationAssertions.assertAllocationFree(() -> ui.update(1280, 720));
    }

    @Test
    void imagesAndNineSlicesComeFromStyles() {
        StyleSheet sheet = Tss.compileString("images.tss", """
            panel.frame { background: nine-slice("frame", 4 4 4 4); width: 100px; height: 50px; }
            panel.picture { background: image("frame"); width: 20px; height: 20px; border: 1px red; }
            panel.missing { background: image("nope"); width: 5px; height: 5px; }
            panel.grad { background: linear-gradient(180deg, red, blue); width: 10px; height: 10px; }
            panel.edge { border: 2px white; width: 10px; height: 10px; }
            image.fill { image: "frame" fill; size: 40px 20px; }
            image.cover { image: "frame" cover; size: 40px 20px; }
            image.contain { image: "frame" contain; size: 40px 20px; }
            image.natural { image: "frame" none; size: 40px 20px; }
            image.gone { image: "nope"; size: 10px; }
            image.auto { image: "frame"; }
            """);
        Ui ui = new Ui(sheet, null);
        BufferedImage frame = new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB);
        frame.setRGB(0, 0, 0xFF102030);
        ui.images().add("frame", frame);
        assertThat(ui.images().get("frame").pixels()[0]).isEqualTo((byte) 0x10);
        assertThat(ui.images().get(null)).isNull();
        for (String style : new String[] {"frame", "picture", "missing", "grad", "edge"}) {
            ui.root().add(ui.panel(style));
        }
        Element auto = null;
        for (String style : new String[] {"fill", "cover", "contain", "natural", "gone", "auto"}) {
            auto = ui.image(style);
            ui.root().add(auto);
        }
        ui.update(400, 400);
        String dump = DrawListDump.of(ui.layers()[0]);
        assertThat(dump).contains("nine-slice").contains("image").contains("flags=1");
        assertThat(ui.layout().height(auto.slot())).isEqualTo(8); // its natural height; stretched across
        assertThat(dump.lines().filter(l -> l.startsWith("image")).count()).isEqualTo(6);
        assertThat(Semantics.of(ui.store()).toString()).contains("IMAGE");
        ui.destroy(auto);
        ui.update(400, 400);
        assertThat(DrawListDump.of(ui.layers()[0]).lines().filter(l -> l.startsWith("image")).count()).isEqualTo(5);
        assertThatThrownBy(() -> new Ui(sheet, null).label(null, "x")).isInstanceOf(IllegalStateException.class);
    }
}
