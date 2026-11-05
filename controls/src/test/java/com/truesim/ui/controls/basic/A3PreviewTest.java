package com.truesim.ui.controls.basic;

import com.truesim.ui.controls.text.TextGallery;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.input.UiCommand;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * CPU previews of group A3's control additions in both themes ({@code TRUESIM_SCREENSHOTS=true}, into
 * {@code TRUESIM_SCREENSHOT_DIR}): the gallery with mnemonics shown, the File menu open by its mnemonic and the
 * eyedropper's magnifier over the screen; the text gallery (a password masked per cluster, spelling squiggles) and
 * its spelling menu; a touch selection handle held, its loupe magnifying the text.
 */
class A3PreviewTest {

    @Test
    void previews() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("TRUESIM_SCREENSHOTS")));
        String dir = System.getenv().getOrDefault("TRUESIM_SCREENSHOT_DIR", "build/screenshots");
        for (boolean garish : new boolean[] {false, true}) {
            String theme = garish ? "test-theme" : "default";
            long bg = garish ? Rgba.srgb(43, 0, 64, 1) : Rgba.srgb(13, 17, 23, 1);
            ControlsGallery.Gallery[] g = new ControlsGallery.Gallery[1];
            Fixture f = GalleryTest.gallery(garish ? Fixture.GARISH : Fixture.DEFAULT, g);
            f.synthetic.mnemonicsShown(true);
            f.synthetic.mnemonic('f');
            f.settle();
            g[0].openEyedropper(560, 200);
            f.settle();
            new CpuPreview((int) f.width, (int) f.height, bg).draw(f.ui.layers(), f.ui.layerCount(), f.ui.textures())
                .save(Path.of(dir, "a3-controls-" + theme + ".png"));

            Fixture t = new Fixture(garish ? Fixture.GARISH : Fixture.DEFAULT, Fixture.newJdkText());
            TextGallery tg = TextGallery.build(t.ui, 1280, 720);
            t.height = 720;
            t.settle();
            new CpuPreview(1280, 720, bg).draw(t.ui.layers(), t.ui.layerCount(), t.ui.textures())
                .save(Path.of(dir, "a3-text-" + theme + ".png"));
            tg.area.spelling(tg.area.spelling(), t.c);
            tg.area.focus();
            int at = tg.area.text().toString().indexOf("wrod") + 1;
            tg.area.editable().moveTo(at, false);
            t.run();
            t.synthetic.press(UiCommand.CONTEXT_MENU);
            t.settle();
            new CpuPreview(1280, 720, bg).draw(t.ui.layers(), t.ui.layerCount(), t.ui.textures())
                .save(Path.of(dir, "a3-spell-menu-" + theme + ".png"));

            Fixture h = new Fixture(garish ? Fixture.GARISH : Fixture.DEFAULT, Fixture.newJdkText());
            TextGallery.build(h.ui, 1280, 720).holdTouchHandle();
            new CpuPreview(1280, 720, bg).draw(h.ui.layers(), h.ui.layerCount(), h.ui.textures())
                .save(Path.of(dir, "text-loupe-" + theme + ".png"));
        }
    }
}
