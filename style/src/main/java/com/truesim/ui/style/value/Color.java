package com.truesim.ui.style.value;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.Rgba;

/**
 * A colour as a style holds it: sRGB-encoded channels (what style sheets write, and what colour functions such as
 * {@link Colors#lighten} work in) with straight alpha, plus the packed linear form the draw list takes
 * ({@link #rgba()}), converted once on first use so drawing never converts. Value equality over the encoded
 * channels, so a colour built in Java and the same colour from a {@code .tss} file are equal.
 *
 * <p>Channels may exceed 1 (HDR intensities above paper white).
 */
public final class Color {

    /** Fully transparent black. */
    public static final Color TRANSPARENT = new Color(0, 0, 0, 0);
    /** Opaque black. */
    public static final Color BLACK = new Color(0, 0, 0, 1);
    /** Opaque white. */
    public static final Color WHITE = new Color(1, 1, 1, 1);

    private static final String[] NAMES = {"transparent", "black", "white", "red", "green", "blue", "yellow", "cyan",
        "magenta", "gray", "grey", "orange", "purple", "pink", "lime", "navy", "teal", "silver", "maroon", "olive"};
    private static final int[] NAMED = {0x00000000, 0x000000FF, 0xFFFFFFFF, 0xFF0000FF, 0x008000FF, 0x0000FFFF,
        0xFFFF00FF, 0x00FFFFFF, 0xFF00FFFF, 0x808080FF, 0x808080FF, 0xFFA500FF, 0x800080FF, 0xFFC0CBFF, 0x00FF00FF,
        0x000080FF, 0x008080FF, 0xC0C0C0FF, 0x800000FF, 0x808000FF};

    private final float r;
    private final float g;
    private final float b;
    private final float a;
    // packed on first use, then kept (like String's hash): the colours a sheet computes on the way (inside lighten,
    // mix, ...) are never drawn, so they never pay for the conversion; a race only computes the same value twice
    private long rgba;
    private boolean packed;

    private Color(float r, float g, float b, float a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    private static float linear(float encoded) {
        // the transfer function is defined on 0 to 1; HDR values above 1 scale linearly, negatives clamp
        if (encoded <= 0) {
            return 0;
        }
        return encoded > 1 ? encoded : Rgba.toLinear(encoded);
    }

    private static float encoded(float linear) {
        if (linear <= 0) {
            return 0;
        }
        return linear > 1 ? linear : Rgba.toSrgb(linear);
    }

    /** An sRGB-encoded colour, channels 0 to 1 (more for HDR), alpha 0 to 1. */
    public static Color srgb(float r, float g, float b, float a) {
        return new Color(r, g, b, clampAlpha(a));
    }

    /** An opaque sRGB colour from 0 to 255 per channel. */
    public static Color rgb(int r, int g, int b) {
        return rgba(r, g, b, 1);
    }

    /** An sRGB colour from 0 to 255 per channel and alpha 0 to 1. */
    public static Color rgba(int r, int g, int b, float a) {
        return new Color(r / 255f, g / 255f, b / 255f, clampAlpha(a));
    }

    /** A colour from linear channels: converted to the encoded form, so it rounds slightly. */
    public static Color linear(float r, float g, float b, float a) {
        return new Color(encoded(r), encoded(g), encoded(b), clampAlpha(a));
    }

    private static float clampAlpha(float a) {
        return a < 0 ? 0 : (a > 1 ? 1 : a);
    }

    /**
     * A colour written as hex: {@code #rgb}, {@code #rgba}, {@code #rrggbb} or {@code #rrggbbaa} (the {@code #} is
     * optional).
     *
     * @throws IllegalArgumentException if it is not one
     */
    public static Color hex(String text) {
        int start = text.startsWith("#") ? 1 : 0;
        int length = text.length() - start;
        int[] v = new int[4];
        v[3] = 255;
        if (length == 3 || length == 4) {
            for (int i = 0; i < length; i++) {
                int d = digit(text, start + i);
                v[i] = d * 17;
            }
        } else if (length == 6 || length == 8) {
            for (int i = 0; i < length / 2; i++) {
                v[i] = digit(text, start + 2 * i) * 16 + digit(text, start + 2 * i + 1);
            }
        } else {
            throw new IllegalArgumentException("not a hex colour: " + text);
        }
        return rgba(v[0], v[1], v[2], v[3] / 255f);
    }

    private static int digit(String text, int index) {
        int d = Character.digit(text.charAt(index), 16);
        if (d < 0) {
            throw new IllegalArgumentException("not a hex colour: " + text);
        }
        return d;
    }

    /** A CSS colour name ({@code black}, {@code red}, {@code transparent}, ...), or null if it names none. */
    public static Color named(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                int v = NAMED[i];
                return rgba(v >>> 24, (v >>> 16) & 0xFF, (v >>> 8) & 0xFF, (v & 0xFF) / 255f);
            }
        }
        return null;
    }

    /** The sRGB-encoded red channel. */
    public float r() {
        return r;
    }

    /** The sRGB-encoded green channel. */
    public float g() {
        return g;
    }

    /** The sRGB-encoded blue channel. */
    public float b() {
        return b;
    }

    /** Alpha, 0 to 1. */
    public float a() {
        return a;
    }

    /** The colour as the draw list takes it: linear, four half floats ({@link Rgba}); converted once, on first use. */
    public long rgba() {
        if (!packed) {
            rgba = Rgba.pack(linear(r), linear(g), linear(b), a);
            packed = true;
        }
        return rgba;
    }

    /** The same colour with alpha {@code alpha}. */
    public Color withAlpha(float alpha) {
        return new Color(r, g, b, clampAlpha(alpha));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Color c && Float.floatToIntBits(r) == Float.floatToIntBits(c.r)
            && Float.floatToIntBits(g) == Float.floatToIntBits(c.g)
            && Float.floatToIntBits(b) == Float.floatToIntBits(c.b)
            && Float.floatToIntBits(a) == Float.floatToIntBits(c.a);
    }

    @Override
    public int hashCode() {
        return ((Float.floatToIntBits(r) * 31 + Float.floatToIntBits(g)) * 31 + Float.floatToIntBits(b)) * 31
            + Float.floatToIntBits(a);
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        if (r >= 0 && r <= 1 && g >= 0 && g <= 1 && b >= 0 && b <= 1) {
            int ri = Math.round(r * 255);
            int gi = Math.round(g * 255);
            int bi = Math.round(b * 255);
            int ai = Math.round(a * 255);
            boolean exact = Math.abs(ri - r * 255) < 1e-3f && Math.abs(gi - g * 255) < 1e-3f
                && Math.abs(bi - b * 255) < 1e-3f && Math.abs(ai - a * 255) < 1e-3f;
            if (exact) {
                return a == 1 ? String.format("#%02x%02x%02x", ri, gi, bi)
                    : String.format("#%02x%02x%02x%02x", ri, gi, bi, ai);
            }
        }
        return "rgba(" + r * 255 + ", " + g * 255 + ", " + b * 255 + ", " + a + ")";
    }
}
