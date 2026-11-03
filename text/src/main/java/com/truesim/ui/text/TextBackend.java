package com.truesim.ui.text;

import java.io.IOException;

/**
 * The font technology behind the text system: FreeType, HarfBuzz and msdfgen ({@code ui-text-native}), or the JDK
 * ({@link com.truesim.ui.text.jdk.JdkTextBackend}, for headless tests and tools without natives).
 */
public interface TextBackend {

    /** Opens {@code source} as face number {@code id}. */
    FontFace open(FontSource source, int id) throws IOException;

    /** A new shaper for one thread. */
    TextShaper newShaper();

    /** The MTSDF generator; thread-safe. */
    MsdfGenerator msdfGenerator();

    /** A new rasterizer of hinted coverage glyphs for one thread. */
    GlyphRasterizer newRasterizer();
}
