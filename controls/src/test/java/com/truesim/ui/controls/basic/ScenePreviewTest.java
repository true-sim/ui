package com.truesim.ui.controls.basic;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.gizmo.SceneGallery;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.text.natives.NativeTextBackend;
import com.truesim.ui.core.draw.UiTextures;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * CPU previews of the scene and gizmo gallery (ui-gizmo's test fixture) in both themes: runs with
 * {@code TRUESIM_SCREENSHOTS=true}, writing PNGs to {@code TRUESIM_SCREENSHOT_DIR} (default {@code build/screenshots}).
 * The 3D scene itself is the engine's to draw: the previews show what the UI draws over it.
 */
class ScenePreviewTest {

    @Test
    void previews() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("TRUESIM_SCREENSHOTS")));
        String dir = System.getenv().getOrDefault("TRUESIM_SCREENSHOT_DIR", "build/screenshots");
        for (boolean garish : new boolean[] {false, true}) {
            NativeTextBackend backend = new NativeTextBackend();
            FontRegistry fonts = BundledCjkFonts.registry(backend);
            UiTextures textures = new UiTextures();
            GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
                new AtlasSettings());
            Ui ui = new Ui(garish ? Tss.testTheme() : Tss.defaultTheme(), new TextSystem(fonts, backend.newShaper(),
                atlas), textures);
            SceneGallery g = SceneGallery.build(ui, 1280, 720);
            long bg = garish ? Rgba.srgb(43, 0, 64, 1) : Rgba.srgb(13, 17, 23, 1);
            new CpuPreview(1280, 720, bg).draw(ui.layers(), ui.layerCount(), ui.textures())
                .save(Path.of(dir, garish ? "scene-gallery-test-theme.png" : "scene-gallery.png"));
            g.frames(1);
        }
    }
}
