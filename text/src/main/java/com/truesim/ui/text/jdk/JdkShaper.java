package com.truesim.ui.text.jdk;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.FontFace;
import com.truesim.ui.text.ShapedGlyphs;
import com.truesim.ui.text.TextShaper;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;

/**
 * Shaping by the JDK's {@link Font#layoutGlyphVector}, which runs the JDK's own OpenType layout (kerning, ligatures,
 * Arabic joining, Indic reordering): good enough for headless tests. HarfBuzz ({@code ui-text-native}) is the real
 * shaper; this one ignores the language.
 */
@AllowAllocation("the JDK fallback for headless tests and tools: java.awt allocates")
public final class JdkShaper implements TextShaper {

    /** The JDK's id for a glyph that layout removed (a joiner): nothing to draw, no advance. */
    private static final int INVISIBLE = 0xFFFF;

    private final FontRenderContext frc = new FontRenderContext(null, true, true);
    /** Characters of context passed on each side of a run, as the HarfBuzz shaper does. */
    static final int CONTEXT = 8;

    private float[] positions = new float[64];
    private char[] context = new char[256];

    @Override
    public void shape(char[] text, int length, int start, int end, FontFace face, float sizePx,
        boolean rightToLeft, String language, ShapedGlyphs out) {
        // The JDK reads the whole array as context: pass the run with CONTEXT characters on each side, within length
        // (the array may hold stale characters past it)
        int from = Math.max(0, start - CONTEXT);
        int to = Math.min(length, end + CONTEXT);
        if (context.length < to - from) {
            context = new char[Math.max(to - from, context.length * 2)];
        }
        System.arraycopy(text, from, context, 0, to - from);
        java.util.Arrays.fill(context, to - from, context.length, (char) 0);
        Font font = ((JdkFontFace) face).shapingFont(sizePx);
        GlyphVector gv = font.layoutGlyphVector(frc, context, start - from, end - from,
            rightToLeft ? Font.LAYOUT_RIGHT_TO_LEFT : Font.LAYOUT_LEFT_TO_RIGHT);
        int n = gv.getNumGlyphs();
        positions = gv.getGlyphPositions(0, n + 1, positions.length >= 2 * n + 2 ? positions : null);
        for (int i = 0; i < n; i++) {
            int code = gv.getGlyphCode(i);
            if (code >= INVISIBLE - 1) {
                continue;
            }
            float x = positions[2 * i];
            out.add(code, start + gv.getGlyphCharIndex(i), positions[2 * i + 2] - x, 0, -positions[2 * i + 1]);
        }
    }
}
