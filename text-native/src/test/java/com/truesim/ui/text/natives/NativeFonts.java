package com.truesim.ui.text.natives;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.text.FontRegistry;
import java.util.List;

/**
 * The native backend over the bundled fonts (Inter, Noto Sans Arabic, Devanagari, SC and JP), shared by the tests
 * (opening faces costs). Bundled fonts make the goldens the same on every machine; installed fonts are never used.
 */
final class NativeFonts {

    static final NativeTextBackend BACKEND = new NativeTextBackend();
    static final FontRegistry REGISTRY = BundledCjkFonts.registry(BACKEND);

    /** The Latin family. */
    static final List<String> LATIN = List.of("Inter");

    private NativeFonts() {
    }

    /** The regular face of the bundled {@code family}. */
    static NativeFontFace face(String family) {
        NativeFontFace face = (NativeFontFace) REGISTRY.match(family, 400, false);
        assertThat(face).as(family + " is bundled").isNotNull();
        return face;
    }

    /** The regular face of {@code family}, checked to be the very file a golden was made with. */
    static NativeFontFace golden(String family, String key) {
        NativeFontFace face = face(family);
        assertThat(face.source().key()).as("the bundled " + family + " changed: make the goldens again")
            .isEqualTo(key);
        return face;
    }
}
