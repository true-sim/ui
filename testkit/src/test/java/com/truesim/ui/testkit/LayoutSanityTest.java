package com.truesim.ui.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.controls.Painter;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.BasicStyles;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.fonts.BundledFonts;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Align;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.Direction;
import com.truesim.ui.style.value.GridPlacement;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.LayoutKind;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.style.value.Track;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.jdk.JdkTextBackend;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each defect {@link LayoutSanity} finds, made on purpose, and the look-alikes it leaves alone. */
class LayoutSanityTest {

    private static TextSystem text;

    private static synchronized TextSystem text() {
        if (text == null) {
            JdkTextBackend backend = new JdkTextBackend();
            text = new TextSystem(BundledFonts.registry(backend), backend.newShaper(), new GlyphAtlas(
                new UiTextures(), backend.msdfGenerator(), backend.newRasterizer(), null, null, new AtlasSettings()));
        }
        return text;
    }

    private final Ui ui = new Ui(StyleSheet.empty(), text());

    private Element panel(InlineStyle.Builder style) {
        Element e = ui.panel(null);
        ui.styles().inline(e.slot(), style.build());
        return e;
    }

    private static InlineStyle.Builder box(float w, float h) {
        return InlineStyle.builder().set(Styles.WIDTH, Length.px(w)).set(Styles.HEIGHT, Length.px(h));
    }

    private static Paint fill(String hex) {
        return Paint.box(Color.hex(hex));
    }

    private List<LayoutSanity.Violation> run() {
        for (int i = 0; i < 6; i++) {
            ui.update(400, 300);
        }
        return LayoutSanity.check(ui);
    }

    private static List<LayoutSanity.Kind> kinds(List<LayoutSanity.Violation> v) {
        return v.stream().map(LayoutSanity.Violation::kind).toList();
    }

    @Test
    void aSaneTreeHasNoDefects() {
        Element column = panel(InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.FLEX)
            .set(Styles.DIRECTION, Direction.COLUMN).set(Styles.GAP, 4f));
        ui.root().add(column);
        column.add(ui.label(null, "One")).add(ui.label(null, "Two"));
        Element tiny = ui.label(null, "small"); // hinted raster glyphs below 13 px
        ui.styles().inline(tiny.slot(), InlineStyle.builder().set(Styles.FONT_SIZE, 10f).build());
        column.add(tiny);
        Element faded = panel(box(30, 10).set(Styles.BACKGROUND, fill("#ff0000")).set(Styles.OPACITY, 0f));
        column.add(faded);
        Element ringed = panel(box(30, 10).set(Styles.BACKGROUND, Paint.gradient(Color.hex("#ff0000"),
            Color.hex("#0000ff"), 90)).set(Styles.FOCUS_RING, new com.truesim.ui.style.value.Border(2,
                Color.hex("#ffffff"))));
        column.add(ringed);
        assertThat(run()).isEmpty();
        LayoutSanity.assertSane(ui, "a column");
    }

    @Test
    void contentDrawnOutsideItsClippingAncestorIsFound() {
        Element viewport = panel(box(100, 40).set(Styles.CLIP, true));
        ui.root().add(viewport);
        Element escaping = ui.panel(null);
        ui.styles().inline(escaping.slot(), box(50, 20).build());
        ui.paint(escaping, new Painter() { // drops its clip: what a broken control could do
            @Override
            public long measure(int slot, float width, int widthMode, float height, int heightMode) {
                return Measure.size(50, 20);
            }

            @Override
            public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
                list.clip(DrawList.NO_CLIP);
                list.box(x0, y0 + 60, x1, y1 + 60, Rgba.WHITE, 0, 0, 0, 0, 0, 0);
                list.line(x0, y0 + 80, x1, y1 + 90, 2, Rgba.WHITE);
            }
        });
        viewport.add(escaping);
        List<LayoutSanity.Violation> v = run();
        assertThat(kinds(v)).containsExactly(LayoutSanity.Kind.CLIP);
        assertThat(v.get(0).toString()).startsWith("CLIP ").contains("panel");
        assertThatThrownBy(() -> LayoutSanity.assertSane(ui, "clipping")).isInstanceOf(AssertionError.class)
            .hasMessageContaining("clipping: 1 layout defects");
    }

    @Test
    void siblingsInAStackingContainerMustNotOverlap() {
        Element grid = panel(InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.GRID)
            .set(Styles.GRID_TEMPLATE_COLUMNS, List.of(Track.px(60), Track.px(60))));
        ui.root().add(grid);
        Element a = panel(box(40, 20).set(Styles.GRID_COLUMN, new GridPlacement(1, 1))
            .set(Styles.GRID_ROW, new GridPlacement(1, 1)));
        Element b = panel(box(40, 20).set(Styles.GRID_COLUMN, new GridPlacement(1, 1))
            .set(Styles.GRID_ROW, new GridPlacement(1, 1)));
        Element pulled = panel(box(40, 20).set(Styles.MARGIN, new Insets(0, 0, 0, -30))); // on purpose
        grid.add(a).add(b).add(pulled);
        List<LayoutSanity.Violation> v = run();
        assertThat(kinds(v)).containsExactly(LayoutSanity.Kind.OVERLAP);
        assertThat(v.get(0).other()).isNotNull();
        ui.root().add(panel(InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.OVERLAY)).add(ui.panel(null))
            .add(ui.panel(null))); // an overlay places children over one another
        assertThat(kinds(run())).containsExactly(LayoutSanity.Kind.OVERLAP);
    }

    @Test
    void textLeavingItsBoxIsFoundUnlessClipped() {
        Element narrow = ui.label(null, "a label far wider than its box");
        ui.styles().inline(narrow.slot(), box(40, 30).build());
        ui.root().add(narrow);
        assertThat(kinds(run())).containsExactly(LayoutSanity.Kind.TEXT_OVERFLOW);
        ui.styles().inline(narrow.slot(), box(40, 30).set(Styles.CLIP, true).build());
        assertThat(run()).isEmpty();
    }

    @Test
    void textOverTextIsFoundUnlessSomethingOpaqueCoversIt() {
        Element stack = panel(InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.OVERLAY)
            .set(Styles.ALIGN_ITEMS, Align.START));
        ui.root().add(stack);
        stack.add(ui.label(null, "Under")).add(ui.label(null, "Over"));
        List<LayoutSanity.Violation> v = run();
        assertThat(kinds(v)).containsExactly(LayoutSanity.Kind.TEXT_OVERLAP);
        Element cover = panel(InlineStyle.builder().set(Styles.BACKGROUND, fill("#202020")));
        Element over = stack.store().element(stack.store().lastChild(stack.slot()));
        over.detach();
        cover.add(over);
        stack.add(cover); // a header's opaque bar between them
        assertThat(run()).isEmpty();
    }

    @Test
    void tabsMustNotShowUnderTheOverflowButtonNorInPartInMenuMode() {
        Element pane = panel(box(200, 40).set(BasicStyles.TAB_OVERFLOW, BasicStyles.TabOverflow.MENU));
        Element header = panel(InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.OVERLAY));
        Element strip = panel(box(120, 30).set(Styles.CLIP, true).set(Styles.LAYOUT, LayoutKind.FLEX)
            .set(Styles.DIRECTION, Direction.ROW));
        Element whole = ui.element("tab", null);
        Element cut = ui.element("tab", null);
        for (Element t : new Element[] {whole, cut}) {
            ui.styles().inline(t.slot(), box(70, 30).set(Styles.BACKGROUND, fill("#404040")).set(Styles.SHRINK, 0f)
                .build());
            strip.add(t);
        }
        Element button = ui.element("button", null).id("tab-overflow");
        ui.styles().inline(button.slot(), box(30, 30).set(Styles.BACKGROUND, fill("#808080")).build());
        ui.root().add(pane);
        pane.add(header);
        header.add(strip).add(button);
        List<LayoutSanity.Violation> v = run();
        assertThat(kinds(v)).contains(LayoutSanity.Kind.TAB_UNDER_OVERFLOW, LayoutSanity.Kind.TAB_PARTIAL);
    }

    @Test
    void theGalleriesAreSane() {
        for (StyleSheet sheet : new StyleSheet[] {com.truesim.ui.style.tss.Tss.defaultTheme(),
            com.truesim.ui.style.tss.Tss.testTheme()}) {
            Ui controls = new Ui(sheet, text());
            com.truesim.ui.controls.basic.ControlsGallery.registerIcons(controls);
            com.truesim.ui.controls.basic.ControlsGallery.Gallery g = com.truesim.ui.controls.basic.ControlsGallery
                .build(new com.truesim.ui.controls.basic.Controls(controls), controls.root());
            for (int i = 0; i < 40; i++) {
                controls.update(1280, 800);
            }
            g.openOverlays();
            for (int i = 0; i < 40; i++) {
                controls.update(1280, 800);
            }
            assertThat(LayoutSanity.check(controls)).isEmpty();
            Ui data = new Ui(sheet, text());
            com.truesim.ui.controls.data.DataGallery gallery = com.truesim.ui.controls.data.DataGallery.build(data);
            com.truesim.ui.controls.data.DataGallery.settle(data, 1600, 1000);
            gallery.pose(data, 1600, 1000);
            assertThat(LayoutSanity.check(data)).isEmpty();
        }
    }
}
