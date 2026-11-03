package com.truesim.ui.core.draw;

/**
 * Colours as the draw list stores them: linear RGB with straight (not premultiplied) alpha, four 16-bit floats packed
 * in a {@code long} (red in the low 16 bits). Half floats keep HDR intensities above 1.0 (glows brighter than paper
 * white) that 8-bit colours would clip. Packing allocates nothing.
 */
public final class Rgba {

    /** Fully transparent. */
    public static final long TRANSPARENT = pack(0, 0, 0, 0);
    /** Opaque white: paper white once composited. */
    public static final long WHITE = pack(1, 1, 1, 1);
    /** Opaque black. */
    public static final long BLACK = pack(0, 0, 0, 1);

    private Rgba() {
    }

    /** Linear {@code r, g, b} and alpha, packed. */
    public static long pack(float r, float g, float b, float a) {
        return (Float.floatToFloat16(r) & 0xFFFFL)
            | (Float.floatToFloat16(g) & 0xFFFFL) << 16
            | (Float.floatToFloat16(b) & 0xFFFFL) << 32
            | (Float.floatToFloat16(a) & 0xFFFFL) << 48;
    }

    /** An sRGB-encoded colour (0 to 255 per channel, as written in style sheets), packed as linear. */
    public static long srgb(int r, int g, int b, float a) {
        return pack(toLinear(r / 255f), toLinear(g / 255f), toLinear(b / 255f), a);
    }

    /** The red channel. */
    public static float r(long rgba) {
        return Float.float16ToFloat((short) rgba);
    }

    /** The green channel. */
    public static float g(long rgba) {
        return Float.float16ToFloat((short) (rgba >>> 16));
    }

    /** The blue channel. */
    public static float b(long rgba) {
        return Float.float16ToFloat((short) (rgba >>> 32));
    }

    /** Alpha. */
    public static float a(long rgba) {
        return Float.float16ToFloat((short) (rgba >>> 48));
    }

    /** The same colour with alpha multiplied by {@code factor}. */
    public static long fade(long rgba, float factor) {
        return pack(r(rgba), g(rgba), b(rgba), a(rgba) * factor);
    }

    /**
     * The sRGB transfer function's inverse: an encoded value in 0 to 1 as linear light. Exact to the standard's
     * piecewise definition; the power is computed by {@link #pow24} so it is the same on every platform.
     */
    public static float toLinear(float encoded) {
        if (encoded <= 0.04045f) {
            return encoded / 12.92f;
        }
        return (float) pow24((encoded + 0.055) / 1.055);
    }

    /** Linear light in 0 to 1 as an sRGB-encoded value. */
    public static float toSrgb(float linear) {
        if (linear <= 0.0031308f) {
            return linear * 12.92f;
        }
        return (float) (1.055 * root24(linear) - 0.055);
    }

    /** {@code x^2.4} for x in 0 to 1: {@code x^2 * x^0.4}, with {@code x^0.4 = (x^2)^(1/5)} by Newton's method. */
    static double pow24(double x) {
        return x * x * fifthRoot(x * x);
    }

    /** {@code x^(1/2.4)} for x in 0 to 1: {@code x^(5/12)}. */
    static double root24(double x) {
        // x^(5/12) = (x^5)^(1/12) = sqrt(sqrt((x^5)^(1/3)))
        double x5 = x * x * x * x * x;
        return Math.sqrt(Math.sqrt(cubeRoot(x5)));
    }

    private static double fifthRoot(double v) {
        if (v <= 0) {
            return 0;
        }
        double y = v > 1 ? v : 1; // start above the root: Newton then converges from above
        for (int i = 0; i < 200; i++) {
            double y4 = y * y * y * y;
            double next = y - (y4 * y - v) / (5 * y4);
            if (next >= y) {
                break;
            }
            y = next;
        }
        return y;
    }

    private static double cubeRoot(double v) {
        if (v <= 0) {
            return 0;
        }
        double y = v > 1 ? v : 1;
        for (int i = 0; i < 200; i++) {
            double next = y - (y * y * y - v) / (3 * y * y);
            if (next >= y) {
                break;
            }
            y = next;
        }
        return y;
    }
}
