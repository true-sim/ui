package com.truesim.ui.text;

/**
 * Renders a glyph at one pixel size into 8-bit coverage, hinted where the backend can (FreeType): the alternative to
 * MTSDF for small text that the open decision UI16 weighs. Used from one thread at a time.
 */
public interface GlyphRasterizer {

    /**
     * Glyph {@code glyph} of {@code face} at {@code sizePx} pixels per em: coverage, one byte per pixel, rows top to
     * bottom, with the bitmap's offset from the pen position. Null if the glyph has no ink.
     */
    Coverage rasterize(FontFace face, int glyph, int sizePx);

    /**
     * A coverage bitmap.
     *
     * @param width pixels
     * @param height pixels
     * @param left the bitmap's left edge, pixels right of the pen position
     * @param top the bitmap's top edge, pixels above the baseline
     * @param pixels {@code width * height} coverage bytes, rows top to bottom
     */
    record Coverage(int width, int height, int left, int top, byte[] pixels) {
    }
}
