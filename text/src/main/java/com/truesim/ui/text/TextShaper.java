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

    /**
     * Shapes {@code text[start, end)}, a part of the run {@code text[runStart, runEnd)}, as that run would shape it:
     * the run gives the script (its first character of a script of its own), so a part of only common characters
     * shapes as the run does. Incremental reshaping shapes parts of long runs again this way; the default ignores
     * the run.
     */
    default void shape(char[] text, int length, int runStart, int runEnd, int start, int end, FontFace face,
        float sizePx, boolean rightToLeft, String language, ShapedGlyphs out) {
        shape(text, length, start, end, face, sizePx, rightToLeft, language, out);
    }

    /**
     * Whether the glyphs carry {@link ShapedGlyphs#UNSAFE_TO_BREAK} and {@link ShapedGlyphs#UNSAFE_TO_CONCAT} where
     * they apply (HarfBuzz): a glyph without them starts a cluster where the text may be split and shaped apart.
     * Without them, incremental reshaping splits runs only after white space.
     */
    default boolean marksUnsafeBreaks() {
        return false;
    }
}
