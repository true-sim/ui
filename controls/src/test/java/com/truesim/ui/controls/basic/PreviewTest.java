package com.truesim.ui.controls.basic;

import com.truesim.ui.core.draw.Rgba;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * CPU previews of the gallery in both themes, with and without its overlays: runs with
 * {@code TRUESIM_SCREENSHOTS=true}, writing PNGs to {@code TRUESIM_SCREENSHOT_DIR} (default
 * {@code build/screenshots}). The integrator renders the same gallery through the engine's GPU renderer.
 */
class PreviewTest {

    @Test
    void previews() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("TRUESIM_SCREENSHOTS")));
        String dir = System.getenv().getOrDefault("TRUESIM_SCREENSHOT_DIR", "build/screenshots");
        for (boolean garish : new boolean[] {false, true}) {
            ControlsGallery.Gallery[] g = new ControlsGallery.Gallery[1];
            Fixture f = GalleryTest.gallery(garish ? Fixture.GARISH : Fixture.DEFAULT, g);
            String name = garish ? "basic-gallery-test-theme" : "basic-gallery";
            long bg = garish ? Rgba.srgb(43, 0, 64, 1) : Rgba.srgb(13, 17, 23, 1);
            new CpuPreview((int) f.width, (int) f.height, bg).draw(f.ui.layers(), f.ui.layerCount(), f.ui.textures())
                .save(Path.of(dir, name + ".png"));
            g[0].openOverlays();
            f.settle();
            new CpuPreview((int) f.width, (int) f.height, bg).draw(f.ui.layers(), f.ui.layerCount(), f.ui.textures())
                .save(Path.of(dir, name + "-overlays.png"));
        }
    }
}
