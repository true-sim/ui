package com.truesim.ui.controls.basic;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.controls.debug.DebuggerGallery;
import com.truesim.ui.controls.imm.ImmGallery;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.style.tss.Tss;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * CPU previews of the tools' galleries (the immediate-mode tool, the UI debugger over the controls gallery) in both
 * themes: runs with {@code TRUESIM_SCREENSHOTS=true}, writing PNGs to {@code TRUESIM_SCREENSHOTS_DIR} (default
 * {@code build/screenshots}). Here for the package's {@link CpuPreview}.
 */
class ToolsPreviewTest {

    private static Path dir() {
        String d = System.getenv("TRUESIM_SCREENSHOTS_DIR");
        return Path.of(d == null ? "build/screenshots" : d);
    }

    @Test
    void previews() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("TRUESIM_SCREENSHOTS")));
        for (String theme : new String[] {"default", "test"}) {
            long bg = theme.equals("default") ? Rgba.srgb(13, 17, 23, 1) : Rgba.srgb(43, 0, 64, 1);
            Ui ui = new Ui(theme.equals("default") ? Tss.defaultTheme() : Tss.testTheme(), UiTestAccess.nativeText());
            ImmGallery.build(ui, 1280, 720);
            new CpuPreview(1280, 720, bg).draw(ui.layers(), ui.layerCount(), ui.textures())
                .save(dir().resolve("imm-" + theme + ".png"));
            Ui debug = new Ui(theme.equals("default") ? Tss.defaultTheme() : Tss.testTheme(),
                UiTestAccess.nativeText());
            DebuggerGallery.build(debug, 1280, 800);
            new CpuPreview(1280, 800, bg).draw(debug.layers(), debug.layerCount(), debug.textures())
                .save(dir().resolve("debugger-" + theme + ".png"));
        }
    }
}
