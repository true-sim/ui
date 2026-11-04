package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.Rgba;

/**
 * Colour glyph bitmaps (emoji) made ready for an {@code RGBA8_SRGB} atlas page: halved {@code level} times (a
 * pre-scaled size, so a 109 px strike drawn at 14 px is sampled from at most twice the pixels it covers: bilinear
 * filtering then shows no aliasing; the atlas cannot use mipmaps, whose texels would mix neighbouring glyphs), framed by
 * a transparent border, and with the colours of transparent texels bled from their visible neighbours, so filtering
 * across the edge (straight alpha) shows no dark fringe. Halving averages in linear light with alpha weights
 * (premultiplied), as the GPU's blending does.
 */
public final class ColorBitmaps {

    /** Transparent texels around every prepared bitmap: the bilinear footprint at the edge. */
    public static final int PAD = 1;

    private static final float[] LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            LINEAR[i] = Rgba.toLinear(i / 255f);
        }
    }

    private ColorBitmaps() {
    }

    /**
     * {@code bitmap} halved {@code level} times (0: as it is), with a {@link #PAD} border, colours bled.
     */
    @AllowAllocation("a bitmap per new colour glyph, on the atlas's job")
    public static Prepared prepare(GlyphRasterizer.ColorBitmap bitmap, int level) {
        int w = bitmap.width();
        int h = bitmap.height();
        // premultiplied linear floats
        float[] p = new float[w * h * 4];
        byte[] src = bitmap.pixels();
        for (int i = 0; i < w * h; i++) {
            float a = (src[i * 4 + 3] & 0xFF) / 255f;
            p[i * 4] = LINEAR[src[i * 4] & 0xFF] * a;
            p[i * 4 + 1] = LINEAR[src[i * 4 + 1] & 0xFF] * a;
            p[i * 4 + 2] = LINEAR[src[i * 4 + 2] & 0xFF] * a;
            p[i * 4 + 3] = a;
        }
        float left = bitmap.left();
        float top = bitmap.top();
        float strike = bitmap.strike();
        for (int l = 0; l < level; l++) {
            int hw = (w + 1) / 2;
            int hh = (h + 1) / 2;
            float[] q = new float[hw * hh * 4];
            for (int y = 0; y < hh; y++) {
                for (int x = 0; x < hw; x++) {
                    for (int c = 0; c < 4; c++) {
                        float sum = at(p, w, h, 2 * x, 2 * y, c) + at(p, w, h, 2 * x + 1, 2 * y, c)
                            + at(p, w, h, 2 * x, 2 * y + 1, c) + at(p, w, h, 2 * x + 1, 2 * y + 1, c);
                        q[(y * hw + x) * 4 + c] = sum * 0.25f;
                    }
                }
            }
            p = q;
            w = hw;
            h = hh;
            left *= 0.5f;
            top *= 0.5f;
            strike *= 0.5f;
        }
        int ow = w + 2 * PAD;
        int oh = h + 2 * PAD;
        float[] out = new float[ow * oh * 4];
        for (int y = 0; y < h; y++) {
            System.arraycopy(p, y * w * 4, out, ((y + PAD) * ow + PAD) * 4, w * 4);
        }
        bleed(out, ow, oh);
        byte[] bytes = new byte[ow * oh * 4];
        for (int i = 0; i < ow * oh; i++) {
            float a = out[i * 4 + 3];
            float k = a > 0 ? 1 / a : 1; // bled texels hold straight colour already
            bytes[i * 4] = encode(out[i * 4] * k);
            bytes[i * 4 + 1] = encode(out[i * 4 + 1] * k);
            bytes[i * 4 + 2] = encode(out[i * 4 + 2] * k);
            bytes[i * 4 + 3] = (byte) Math.round(Math.min(1, a) * 255);
        }
        return new Prepared(ow, oh, (left - PAD) / strike, (top + PAD) / strike, ow / strike, oh / strike, bytes);
    }

    /**
     * A prepared colour bitmap: {@code width x height} texels ({@code pixels}, four bytes each, sRGB, straight alpha),
     * whose top left lies {@code left} ems right of the pen and {@code top} ems above the baseline, and which covers
     * {@code emWidth x emHeight} ems.
     */
    public record Prepared(int width, int height, float left, float top, float emWidth, float emHeight,
        byte[] pixels) {
    }

    private static float at(float[] p, int w, int h, int x, int y, int c) {
        return x < w && y < h ? p[(y * w + x) * 4 + c] : 0;
    }

    /**
     * Gives each fully transparent texel the average straight colour of its visible 8-neighbours (two passes: the
     * bilinear footprint and one more), keeping its alpha 0. Premultiplied texels with alpha 0 then hold straight
     * colour in their colour channels.
     */
    @AllowAllocation("on the atlas's job")
    private static void bleed(float[] p, int w, int h) {
        boolean[] known = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            known[i] = p[i * 4 + 3] > 0;
        }
        for (int pass = 0; pass < 2; pass++) {
            boolean[] next = known.clone();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (known[i]) {
                        continue;
                    }
                    float r = 0;
                    float g = 0;
                    float b = 0;
                    int n = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h || !known[ny * w + nx]) {
                                continue;
                            }
                            int j = ny * w + nx;
                            float a = p[j * 4 + 3];
                            float k = a > 0 ? 1 / a : 1;
                            r += p[j * 4] * k;
                            g += p[j * 4 + 1] * k;
                            b += p[j * 4 + 2] * k;
                            n++;
                        }
                    }
                    if (n > 0) {
                        p[i * 4] = r / n;
                        p[i * 4 + 1] = g / n;
                        p[i * 4 + 2] = b / n;
                        p[i * 4 + 3] = 0;
                        next[i] = true;
                    }
                }
            }
            known = next;
        }
    }

    /** A linear value as the nearest sRGB byte. */
    private static byte encode(float linear) {
        int lo = 0;
        int hi = 255;
        while (lo < hi) { // the first code whose linear value is at least the value
            int mid = (lo + hi) >>> 1;
            if (LINEAR[mid] < linear) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo > 0 && linear - LINEAR[lo - 1] < LINEAR[lo] - linear) {
            lo--;
        }
        return (byte) lo;
    }

    /** An sRGB byte as linear light (0 to 1). */
    public static float linear(int srgb) {
        return LINEAR[srgb & 0xFF];
    }

    /**
     * How many times a colour glyph of {@code strike} pixels per em is halved to be drawn at {@code sizePx}: the most
     * halvings that keep at least one texel per pixel (at most 5).
     */
    public static int level(float strike, float sizePx) {
        int level = 0;
        float s = strike;
        while (level < 5 && s * 0.5f >= sizePx) {
            s *= 0.5f;
            level++;
        }
        return level;
    }
}
