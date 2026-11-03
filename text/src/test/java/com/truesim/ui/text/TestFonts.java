package com.truesim.ui.text;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.jdk.JdkTextBackend;
import java.util.List;

/**
 * System fonts for tests: nothing is bundled, so a test that needs a family skips (an assumption) where it is not
 * installed. The registry is made once per test class run, as discovery scans the font directories.
 */
final class TestFonts {

    static final JdkTextBackend BACKEND = new JdkTextBackend();
    private static FontRegistry registry;

    private TestFonts() {
    }

    static synchronized FontRegistry registry() {
        if (registry == null) {
            registry = FontRegistry.system(BACKEND);
        }
        return registry;
    }

    /** The first installed of {@code families}, or the test is skipped. */
    static List<String> family(String... families) {
        for (String family : families) {
            if (registry().match(family, 400, false) != null) {
                return List.of(family);
            }
        }
        assumeTrue(false, "none of " + List.of(families) + " is installed");
        return null;
    }

    static List<String> latin() {
        return family("Segoe UI", "Arial", "DejaVu Sans", "Noto Sans", "Liberation Sans");
    }

    /** A text system over the system fonts with a JDK-generated atlas built synchronously (no lane). */
    static TextSystem system(UiTextures textures, AtlasSettings settings) {
        GlyphAtlas atlas = new GlyphAtlas(textures, BACKEND.msdfGenerator(), null, null, settings);
        return new TextSystem(registry(), BACKEND.newShaper(), atlas);
    }
}
