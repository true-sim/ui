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
 *  15   reserved (0): a transform and opacity index, later
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
 *
 * <p>A {@link #TRIANGLE} (charts: areas, pie slices, triangle and diamond markers) keeps its bounding box in words 1-4
 * (its quad, what clips and picking read), colour A as its fill, and its corners a, b, c as floats in words 9-14
 * ({@link #XA} to {@link #YC}), in the same space as the box (transformed alike, moved by its clip's offset alike).
 * Its flags say which edges are anti-aliased ({@link #EDGE_AB}, {@link #EDGE_BC}, {@link #EDGE_CA}): a polygon's
 * outline, not the edges triangles of one shape share (those are hard, so no seam shows between them).
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
    /** A filled triangle (corners in words 9-14), anti-aliased on the edges its flags name. */
    public static final int TRIANGLE = 7;
    /** How many kinds exist (kinds are below this). */
    public static final int KINDS = 8;

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

    /** Flag of a {@link #TRIANGLE}: its edge from a to b is anti-aliased. */
    public static final int EDGE_AB = 1;
    /** Flag of a {@link #TRIANGLE}: its edge from b to c is anti-aliased. */
    public static final int EDGE_BC = 2;
    /** Flag of a {@link #TRIANGLE}: its edge from c to a is anti-aliased. */
    public static final int EDGE_CA = 4;

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
    public static final int RESERVED = 15;
    /** A {@link #TRIANGLE}'s corners. */
    public static final int XA = 9;
    public static final int YA = 10;
    public static final int XB = 11;
    public static final int YB = 12;
    public static final int XC = 13;
    public static final int YC = 14;

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
