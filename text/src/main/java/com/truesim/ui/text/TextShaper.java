package com.truesim.ui.text;

/**
 * Turns characters into positioned glyphs for one run: one face, one script, one direction. HarfBuzz in
 * {@code ui-text-native}, the JDK's layout in the fallback. A shaper keeps scratch state and is used from one thread.
 */
public interface TextShaper {

    /**
     * Shapes {@code text[start, end)} with {@code face} at {@code sizePx} pixels per em, right to left if
     * {@code rightToLeft}, for {@code language} (a BCP 47 tag, or null for none). The rest of {@code text[0, length)} is
     * context (Arabic joining across a font change). Appends the glyphs to {@code out} in visual order, left to right,
     * with clusters as indices into {@code text}.
     */
    void shape(char[] text, int length, int start, int end, FontFace face, float sizePx, boolean rightToLeft,
        String language, ShapedGlyphs out);
}
