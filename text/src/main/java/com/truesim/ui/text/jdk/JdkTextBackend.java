package com.truesim.ui.text.jdk;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.FontFace;
import com.truesim.ui.text.FontSource;
import com.truesim.ui.text.GlyphRasterizer;
import com.truesim.ui.text.MsdfGenerator;
import com.truesim.ui.text.TextBackend;
import com.truesim.ui.text.TextShaper;
import java.io.IOException;

/**
 * The text backend of the JDK alone: {@code java.awt.Font} faces, the JDK's layout for shaping, a brute-force SDF
 * generator and Java2D coverage. Headless tests and tools run on it without natives; games use
 * {@code NativeTextBackend} (FreeType, HarfBuzz, msdfgen).
 */
public final class JdkTextBackend implements TextBackend {

    private final JdkSdfGenerator generator = new JdkSdfGenerator();

    /** The backend. */
    @AllowAllocation("made once")
    public JdkTextBackend() {
    }

    @Override
    @AllowAllocation("a face per font, when it is first used")
    public FontFace open(FontSource source, int id) throws IOException {
        return new JdkFontFace(source, id);
    }

    @Override
    @AllowAllocation("a shaper per thread, once")
    public TextShaper newShaper() {
        return new JdkShaper();
    }

    @Override
    public MsdfGenerator msdfGenerator() {
        return generator;
    }

    @Override
    @AllowAllocation("a rasterizer per thread, once")
    public GlyphRasterizer newRasterizer() {
        return new JdkRasterizer();
    }
}
