package com.truesim.ui.controls.basic;

import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.props.PropsGallery;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * CPU previews of the props gallery (ui-props) in both themes ({@code TRUESIM_SCREENSHOTS=true}, PNGs into
 * {@code TRUESIM_SCREENSHOT_DIR}, default {@code build/screenshots}): to look at, not compared.
 */
class PropsPreviewTest {

    @Test
    void previews() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("TRUESIM_SCREENSHOTS")));
        String dir = System.getenv().getOrDefault("TRUESIM_SCREENSHOT_DIR", "build/screenshots");
        for (boolean garish : new boolean[] {false, true}) {
            Fixture f = new Fixture(garish ? Fixture.GARISH : Fixture.DEFAULT, Fixture.jdkText());
            f.width = 1280;
            f.height = 1700;
            PropsGallery.build(f.c, f.ui.root());
            f.settle();
            long bg = garish ? Rgba.srgb(43, 0, 64, 1) : Rgba.srgb(13, 17, 23, 1);
            new CpuPreview((int) f.width, (int) f.height, bg).draw(f.ui.layers(), f.ui.layerCount(), f.ui.textures())
                .save(Path.of(dir, garish ? "props-gallery-test-theme.png" : "props-gallery.png"));
        }
    }
}
