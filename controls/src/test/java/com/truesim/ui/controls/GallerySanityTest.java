package com.truesim.ui.controls;

import com.truesim.ui.charts.ChartsGallery;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.ControlsGallery;
import com.truesim.ui.controls.data.DataGallery;
import com.truesim.ui.controls.text.TextGallery;
import com.truesim.ui.controls.windows.WindowsScene;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.fonts.emoji.BundledEmojiFonts;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.LayoutSanity;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every gallery (the engine's GPU screenshots show the same scenes) in both themes, as the screenshots take them:
 * nothing drawn outside its clipping ancestor, no overlapping siblings in stacking containers, no text leaving its box,
 * no tab under its strip's overflow button ({@link LayoutSanity}).
 */
class GallerySanityTest {

    private static StyleSheet theme(String name) {
        return name.equals("default") ? Tss.defaultTheme() : Tss.testTheme();
    }

    /** The real text path over the bundled fonts and colour emoji, as the screenshots use. */
    private static Ui ui(String theme) {
        NativeTextBackend backend = new NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        BundledEmojiFonts.register(fonts);
        UiTextures textures = new UiTextures();
        GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
            new AtlasSettings());
        return new Ui(theme(theme), new TextSystem(fonts, backend.newShaper(), atlas), textures);
    }

    private static long nanos = 50_000_000_000L;

    /** Frames until the atlas has every glyph and animations end. */
    static void settle(Ui ui, int w, int h) {
        UiInput input = new UiInput(4);
        for (int i = 0; i < 400; i++) {
            input.beginFrame();
            ui.update(w, h, input, nanos += 16_000_000L);
            if (i > 20 && ui.textSystem().atlas().pending() == 0 && ui.animator().running() == 0
                && ui.recorded() == 0) {
                return;
            }
        }
    }

    private static void check(String name, String theme, Consumer<Ui> scene) {
        Ui ui = ui(theme);
        scene.accept(ui);
        LayoutSanity.assertSane(ui, name + " (" + theme + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void controlsGallery(String theme) {
        check("controls gallery", theme, ui -> {
            ControlsGallery.registerIcons(ui);
            ControlsGallery.build(new Controls(ui), ui.root());
            settle(ui, 1280, 800);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void controlsGalleryWhileTheCarouselTurns(String theme) {
        check("controls gallery, carousel turning", theme, ui -> {
            ControlsGallery.registerIcons(ui);
            ControlsGallery.Gallery g = ControlsGallery.build(new Controls(ui), ui.root());
            settle(ui, 1280, 800);
            g.carousel.turn(0);
            UiInput input = new UiInput(4);
            for (int i = 0; i < 6; i++) { // part of the way: pages straddle the viewport's edges
                input.beginFrame();
                ui.update(1280, 800, input, nanos += 16_000_000L);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void controlsGalleryWithItsOverlays(String theme) {
        check("controls gallery with overlays", theme, ui -> {
            ControlsGallery.registerIcons(ui);
            ControlsGallery.Gallery g = ControlsGallery.build(new Controls(ui), ui.root());
            settle(ui, 1280, 800);
            g.openOverlays();
            settle(ui, 1280, 800);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void dataGallery(String theme) {
        check("data gallery", theme, ui -> {
            DataGallery gallery = DataGallery.build(ui);
            DataGallery.settle(ui, 1600, 1000);
            gallery.pose(ui, 1600, 1000);
            settle(ui, 1600, 1000);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void filesGallery(String theme) {
        check("files gallery", theme, ui -> {
            com.truesim.ui.controls.files.FilesGallery.registerIcons(ui);
            com.truesim.ui.controls.files.FilesGallery.build(ui);
            settle(ui, 1280, 720);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void filesGalleryWithItsDialogs(String theme) {
        check("files gallery with dialogs", theme, ui -> {
            com.truesim.ui.controls.files.FilesGallery.registerIcons(ui);
            com.truesim.ui.controls.files.FilesGallery g = com.truesim.ui.controls.files.FilesGallery.build(ui);
            settle(ui, 1280, 720);
            g.openDialog();
            settle(ui, 1280, 720);
            g.confirmOverwrite();
            settle(ui, 1280, 720);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void windowsScene(String theme) {
        check("windows scene", theme, ui -> WindowsScene.build(ui, 1280, 720).frames(60));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void textGallery(String theme) {
        check("text gallery", theme, ui -> TextGallery.build(ui, 1280, 720));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void chartsGallery(String theme) {
        check("charts gallery", theme, ui -> {
            ChartsGallery g = ChartsGallery.build(ui);
            settle(ui, ChartsGallery.WIDTH, ChartsGallery.HEIGHT);
            g.pose(ui, ChartsGallery.WIDTH, ChartsGallery.HEIGHT);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void propsGallery(String theme) {
        check("props gallery", theme, ui -> {
            com.truesim.ui.props.PropsGallery.build(new Controls(ui), ui.root());
            settle(ui, 1280, 1700);
        });
    }
}
