package com.truesim.ui.core.draw;

/**
 * The layout of one primitive of a {@link DrawList}: 16 words (64 bytes), {@code int}s and {@code float}s by bits.
 * The engine's UI shader reads the same layout; this class is its one definition (a layout shared by Java and a
 * shader is defined once, docs/pitfalls.md).
 *
 * <pre>
 * word  all kinds
 *  0    header: kind (bits 0-7), flags (8-15), clip index (16-31)
 *  1-4  x0, y0, x1, y1: the rectangle in pixels, y down (a line's two end points)
 *  5-6  colour A (linear, straight alpha, four half floats, {@link Rgba}): fill, glyph or tint colour
 *  7-8  colour B: a gradient's end colour, a glyph's outline colour
 *  15   transform: the index of its entry in the list's transform table (0: none), which scales, moves and fades it
 *
 * word  BOX                     SHADOW                 GLYPH                      IMAGE, NINE_SLICE         LINE
 *  9-10 radii (4 half floats:   radii                  uv (4 x unorm16:           uv (4 x unorm16)          -
 *       top left, top right,                           u0, v0, u1, v1)
 *       bottom right, bottom left)
 *  11   border width (px)       blur sigma (px)        texture id                 texture id                width (px)
 *  12-13 border colour          -                      screen pixel range         nine slice: destination   -
 *                                                      (12), outline width (13)   insets (4 half floats)
 *  14   gradient angle          -                      weight offset (bold)       nine slice: source insets  -
 *       (radians, if GRADIENT)                                                    (4 x 8-bit texels l t r b)
 * </pre>
 */
public final class Primitive {

    /** Words per primitive. */
    public static final int WORDS = 16;
    /** Bytes per primitive. */
    public static final int BYTES = WORDS * 4;

    /** Nothing: a padding slot of an element's range, drawn as no pixels. */
    public static final int EMPTY = 0;
    /** A rounded box with four radii, a fill (solid or a linear gradient) and a border, by its signed distance. */
    public static final int BOX = 1;
    /** A soft shadow of a rounded box (the closed-form blurred rounded rectangle); its quad covers the blur. */
    public static final int SHADOW = 2;
    /** A glyph from an MTSDF atlas page. */
    public static final int GLYPH = 3;
    /** An image (a texture's region), tinted. */
    public static final int IMAGE = 4;
    /** A nine-slice image: corners kept, edges and centre stretched. */
    public static final int NINE_SLICE = 5;
    /** An anti-aliased line segment. */
    public static final int LINE = 6;
    /** How many kinds exist (kinds are below this). */
    public static final int KINDS = 7;

    /** Flag: a box's fill is a linear gradient from colour A to colour B. */
    public static final int FLAG_GRADIENT = 1;
    /** Flag: a glyph's texels are coverage (a hinted raster glyph), not an MTSDF. */
    public static final int FLAG_RASTER = 2;
    /**
     * Flag: a glyph's texels are colour (an emoji bitmap on an {@code RGBA8_SRGB} page, straight alpha, colours bled
     * into transparent texels so filtering shows no dark fringes): the shader samples the page (hardware sRGB decode)
     * and multiplies it by colour A, {@code rgb * a.rgb, alpha * a.a}: white with the text's opacity keeps the emoji's
     * own colours. Pixel range, outline and weight are 0.
     */
    public static final int FLAG_COLOR = 4;

    /** Word offsets. */
    public static final int HEADER = 0;
    public static final int X0 = 1;
    public static final int Y0 = 2;
    public static final int X1 = 3;
    public static final int Y1 = 4;
    public static final int COLOR_A = 5;
    public static final int COLOR_B = 7;
    public static final int RADII = 9;
    public static final int UV = 9;
    public static final int WIDTH = 11;
    public static final int TEXTURE = 11;
    public static final int BORDER_COLOR = 12;
    public static final int PIXEL_RANGE = 12;
    public static final int OUTLINE = 13;
    public static final int INSETS = 12;
    public static final int GRADIENT_ANGLE = 14;
    public static final int WEIGHT = 14;
    public static final int SOURCE_INSETS = 14;
    public static final int TRANSFORM = 15;

    private Primitive() {
    }

    /** A header word. */
    public static int header(int kind, int flags, int clip) {
        return kind & 0xFF | (flags & 0xFF) << 8 | clip << 16;
    }

    /** The kind of a header word. */
    public static int kind(int header) {
        return header & 0xFF;
    }

    /** The flags of a header word. */
    public static int flags(int header) {
        return header >>> 8 & 0xFF;
    }

    /** The clip index of a header word. */
    public static int clip(int header) {
        return header >>> 16;
    }

    /** Two values in 0 to 1 as unsigned 16-bit fixed point, the first in the low bits. */
    public static int unorm16(float a, float b) {
        return unorm16(a) | unorm16(b) << 16;
    }

    private static int unorm16(float v) {
        float c = v < 0 ? 0 : (v > 1 ? 1 : v);
        return (int) (c * 65535f + 0.5f);
    }

    /** Two floats as halves, the first in the low bits. */
    public static int halves(float a, float b) {
        return Float.floatToFloat16(a) & 0xFFFF | (Float.floatToFloat16(b) & 0xFFFF) << 16;
    }
}
