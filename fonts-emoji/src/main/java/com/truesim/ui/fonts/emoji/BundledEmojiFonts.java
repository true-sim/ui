package com.truesim.ui.fonts.emoji;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.fonts.BundledFonts;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.FontSource;
import com.truesim.ui.text.TextBackend;
import java.nio.file.Path;

/**
 * The optional colour emoji font: Noto Color Emoji (CBDT colour bitmaps, a 109 px strike; Unicode 18.0) under the SIL
 * Open Font License 1.1. Registered, it is the first of the {@linkplain FontRegistry#emoji emoji families}, so emoji
 * (ZWJ sequences, skin tones, flags, keycaps, U+FE0F) draw in colour through the atlas's colour pages where the
 * text backend renders colour bitmaps (FreeType); installed emoji fonts follow it.
 *
 * <pre>{@code
 * FontRegistry fonts = BundledCjkFonts.registry(backend);
 * BundledEmojiFonts.register(fonts);
 * }</pre>
 */
public final class BundledEmojiFonts {

    /** The family name. */
    public static final String FAMILY = "Noto Color Emoji";

    private static final String ROOT = "com/truesim/ui/fonts/emoji/";
    private static final Object[][] FACES = {
        {"NotoColorEmoji.ttf", FAMILY, 400, false},
    };

    private BundledEmojiFonts() {
    }

    /** A registry of the bundled base fonts and the emoji font. */
    @AllowAllocation("fonts are registered at start-up")
    public static FontRegistry registry(TextBackend backend) {
        FontRegistry registry = BundledFonts.registry(backend);
        register(registry);
        return registry;
    }

    /** Adds the emoji face to {@code registry} (after {@link BundledFonts#register}) as the first emoji family. */
    @AllowAllocation("fonts are registered at start-up")
    public static void register(FontRegistry registry) {
        Path dir = BundledFonts.extract(BundledEmojiFonts.class.getClassLoader(), ROOT, "emoji", FACES);
        for (Object[] face : FACES) {
            registry.register(FontSource.of(dir.resolve((String) face[0]), 0, (String) face[1], (Integer) face[2],
                (Boolean) face[3]));
        }
        registry.emoji(FAMILY, "Segoe UI Emoji", "Apple Color Emoji", "Noto Emoji");
        BundledFonts.lastResort(registry);
    }
}
