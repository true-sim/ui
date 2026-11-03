package com.truesim.ui.text;

/**
 * Renders a glyph at one pixel size into 8-bit coverage, hinted where the backend can (FreeType): the glyph atlas's
 * small text (UI16, {@link AtlasSettings#smallText()}). Thread-safe: the atlas calls it from its jobs.
 */
public interface GlyphRasterizer {

    /**
     * Glyph {@code glyph} of {@code face} at {@code sizePx} pixels per em (to 1/64 pixel): coverage, one byte per
     * pixel, rows top to bottom, with the bitmap's offset from the pen position. Null if the glyph has no ink.
     */
    default Coverage rasterize(FontFace face, int glyph, float sizePx) {
        return rasterize(face, glyph, sizePx, 0);
    }

    /**
     * As {@link #rasterize(FontFace, int, float)}, the glyph moved {@code offsetX} pixels right (0 to 1) before it is
     * rendered: subpixel positioning; the bitmap's offset stays in whole pixels from the unmoved pen.
     */
    Coverage rasterize(FontFace face, int glyph, float sizePx, float offsetX);

    /**
     * Glyph {@code glyph}'s colour bitmap at its face's {@linkplain FontFace#colorStrike() strike}, or null if it has
     * none (an outline face, a missing glyph); the default has none.
     */
    default ColorBitmap color(FontFace face, int glyph) {
        return null;
    }

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

    /**
     * A colour bitmap (an emoji) at its strike's size.
     *
     * @param width pixels
     * @param height pixels
     * @param left the bitmap's left edge, pixels right of the pen position
     * @param top the bitmap's top edge, pixels above the baseline
     * @param strike pixels per em the bitmap is drawn at
     * @param pixels {@code width * height} texels of four bytes, red, green, blue, alpha: sRGB-encoded colour with
     *     straight (not premultiplied) alpha, rows top to bottom
     */
    record ColorBitmap(int width, int height, int left, int top, float strike, byte[] pixels) {
    }
}
