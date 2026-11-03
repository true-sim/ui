package com.truesim.ui.text;

import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.text.jdk.JdkTextBackend;
import java.util.List;

/**
 * The bundled fonts (Inter, Noto Sans Arabic, Devanagari, SC and JP) through the JDK backend, so the tests run the same
 * on every machine; installed fonts are not used. The registry is made once per test class run.
 */
final class TestFonts {

    static final JdkTextBackend BACKEND = new JdkTextBackend();
    private static FontRegistry registry;

    private TestFonts() {
    }

    static synchronized FontRegistry registry() {
        if (registry == null) {
            registry = BundledCjkFonts.registry(BACKEND);
        }
        return registry;
    }

    static List<String> latin() {
        return List.of("Inter");
    }

    /** A text system over the bundled fonts with a JDK-generated atlas built synchronously (no lane). */
    static TextSystem system(UiTextures textures, AtlasSettings settings) {
        GlyphAtlas atlas = new GlyphAtlas(textures, BACKEND.msdfGenerator(), null, null, settings);
        return new TextSystem(registry(), BACKEND.newShaper(), atlas);
    }
}
