package com.truesim.ui.controls.basic;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.data.DataGallery;
import com.truesim.ui.controls.files.FilesGallery;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * CPU previews of the data gallery (posed) and the files gallery (with and without its dialogs) in both themes, through
 * the bundled fonts: runs with {@code TRUESIM_SCREENSHOTS=true}, writing PNGs to {@code TRUESIM_SCREENSHOT_DIR}
 * (default {@code build/screenshots}).
 */
class DataFilesPreviewTest {

    private static long nanos = 50_000_000_000L;

    private static Ui ui(StyleSheet sheet) {
        NativeTextBackend backend = new NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        UiTextures textures = new UiTextures();
        GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
            new AtlasSettings());
        return new Ui(sheet, new TextSystem(fonts, backend.newShaper(), atlas), textures);
    }

    private static void settle(Ui ui, int w, int h) {
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

    private static void shoot(String name, int w, int h, Consumer<Ui> scene) throws Exception {
        String dir = System.getenv().getOrDefault("TRUESIM_SCREENSHOT_DIR", "build/screenshots");
        for (boolean garish : new boolean[] {false, true}) {
            Ui ui = ui(garish ? Tss.testTheme() : Tss.defaultTheme());
            scene.accept(ui);
            settle(ui, w, h);
            long bg = garish ? Rgba.srgb(43, 0, 64, 1) : Rgba.srgb(13, 17, 23, 1);
            new CpuPreview(w, h, bg).draw(ui.layers(), ui.layerCount(), ui.textures())
                .save(Path.of(dir, name + (garish ? "-test-theme" : "") + ".png"));
        }
    }

    @Test
    void previews() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("TRUESIM_SCREENSHOTS")));
        shoot("data-gallery", 1600, 1000, ui -> {
            DataGallery g = DataGallery.build(ui);
            DataGallery.settle(ui, 1600, 1000);
            g.pose(ui, 1600, 1000);
        });
        shoot("files-gallery", 1280, 720, ui -> {
            FilesGallery.registerIcons(ui);
            FilesGallery.build(ui);
        });
        shoot("files-dialogs", 1280, 720, ui -> {
            FilesGallery.registerIcons(ui);
            FilesGallery g = FilesGallery.build(ui);
            settle(ui, 1280, 720);
            g.openDialog();
            settle(ui, 1280, 720);
            g.confirmOverwrite();
        });
    }
}
