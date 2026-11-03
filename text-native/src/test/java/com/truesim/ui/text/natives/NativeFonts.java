package com.truesim.ui.text.natives;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.ui.text.FontRegistry;
import java.util.List;

/**
 * The native backend over the system's fonts, shared by the tests (opening faces costs). Nothing is bundled: a test
 * that needs a family skips where it is not installed, and a golden pinned to a font file skips where that file is
 * another version (its {@code FontSource.key()}: name, index, size).
 */
final class NativeFonts {

    static final NativeTextBackend BACKEND = new NativeTextBackend();
    static final FontRegistry REGISTRY = FontRegistry.system(BACKEND);

    private NativeFonts() {
    }

    /** The regular face of {@code family}, or the test is skipped. */
    static NativeFontFace face(String family) {
        NativeFontFace face = (NativeFontFace) REGISTRY.match(family, 400, false);
        assumeTrue(face != null, family + " is not installed");
        return face;
    }

    /** The regular face of {@code family} if it is the very file a golden was made with; else skipped. */
    static NativeFontFace golden(String family, String key) {
        NativeFontFace face = face(family);
        assumeTrue(face.source().key().equals(key), family + " is another version: " + face.source().key());
        return face;
    }

    /** The first installed of {@code families}, or the test is skipped. */
    static List<String> family(String... families) {
        for (String family : families) {
            if (REGISTRY.match(family, 400, false) != null) {
                return List.of(family);
            }
        }
        assumeTrue(false, "none of " + List.of(families) + " is installed");
        return null;
    }

    static List<String> latin() {
        return family("Segoe UI", "Arial", "DejaVu Sans", "Noto Sans", "Liberation Sans");
    }
}
