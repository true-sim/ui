package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;

/**
 * Draws a draw list's GLYPH primitives on the CPU with exactly the formula the engine's MTSDF shader uses: the
 * reference for the shader, CPU previews and image goldens without a GPU.
 *
 * <p><b>The shader's decode</b> (per fragment, {@code uv} interpolated over the quad, bilinear sampling, no sRGB
 * decode: the page is {@code RGBA8_UNORM}):
 * <pre>
 * float4 s     = page.Sample(linearClamp, uv);                  // r, g, b: multi-channel distance; a: true distance
 * float  sd    = max(min(s.r, s.g), min(max(s.r, s.g), s.b));   // median: the edge distance with sharp corners
 * float  range = max(prim.pixelRange, 1.0);                     // distance range in screen pixels at this size
 * float  fill  = saturate(range * (sd - 0.5) + prim.weight + 0.5);
 * float  line  = saturate(range * (s.a - 0.5) + prim.weight + prim.outlineWidth + 0.5);  // only if outlineWidth > 0
 * float4 color = lerp(prim.outlineColor, prim.color, fill)      // straight alpha
 *              with alpha = lerp(outlineColor.a * line, color.a, fill)   (outline 0: color with alpha color.a * fill)
 * </pre>
 * {@code pixelRange} is the atlas range in screen pixels: atlas pixel range × font size ÷ atlas em size, which
 * {@link TextBlock#record} writes. A raster glyph ({@link Primitive#FLAG_RASTER}, small text) is coverage instead:
 * {@code alpha = color.a * s.a}, the quad on whole pixels sampling texel centres. A colour glyph
 * ({@link Primitive#FLAG_COLOR}, an emoji on an {@code RGBA8_SRGB} page) is the page's colour, decoded from sRGB per
 * texel before filtering (as the hardware does), times colour A: {@code rgb * color.rgb, alpha = s.a * color.a}.
 * Colours are blended over the target in linear light.
 */
public final class SoftwareGlyphRenderer {

    private SoftwareGlyphRenderer() {
    }

    /** The median of three: the MTSDF edge distance. */
    public static float median(float r, float g, float b) {
        return Math.max(Math.min(r, g), Math.min(Math.max(r, g), b));
    }

    /**
     * Composites every GLYPH primitive of {@code list} over {@code rgb}: linear RGB triples, {@code width} by
     * {@code height} pixels, rows top to bottom, opaque. Glyphs draw where the list draws them: their transforms and
     * clip offsets applied ({@link DrawList#baked()}).
     */
    @AllowAllocation("a software preview")
    public static void render(DrawList drawn, UiTextures textures, float[] rgb, int width, int height) {
        DrawList list = drawn.baked();
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.GLYPH) {
                glyph(list, i, textures, rgb, width, height);
            }
        }
    }

    private static void glyph(DrawList list, int i, UiTextures textures, float[] rgb, int width, int height) {
        float x0 = list.floatAt(i, Primitive.X0);
        float y0 = list.floatAt(i, Primitive.Y0);
        float x1 = list.floatAt(i, Primitive.X1);
        float y1 = list.floatAt(i, Primitive.Y1);
        if (!(x1 > x0) || !(y1 > y0)) {
            return; // a placeholder: no pixels
        }
        int uvA = list.intAt(i, Primitive.UV);
        int uvB = list.intAt(i, Primitive.UV + 1);
        float u0 = (uvA & 0xFFFF) / 65535f;
        float v0 = (uvA >>> 16) / 65535f;
        float u1 = (uvB & 0xFFFF) / 65535f;
        float v1 = (uvB >>> 16) / 65535f;
        UiTexture page = textures.get(list.intAt(i, Primitive.TEXTURE));
        float range = Math.max(list.floatAt(i, Primitive.PIXEL_RANGE), 1);
        float outline = list.floatAt(i, Primitive.OUTLINE);
        float weight = list.floatAt(i, Primitive.WEIGHT);
        long color = list.colorAt(i, Primitive.COLOR_A);
        long outlineColor = list.colorAt(i, Primitive.COLOR_B);
        boolean raster = (Primitive.flags(list.header(i)) & Primitive.FLAG_RASTER) != 0;
        boolean colorGlyph = (Primitive.flags(list.header(i)) & Primitive.FLAG_COLOR) != 0;
        float[] s = new float[4];
        int px0 = Math.max(0, (int) Math.floor(x0));
        int py0 = Math.max(0, (int) Math.floor(y0));
        int px1 = Math.min(width, (int) Math.ceil(x1));
        int py1 = Math.min(height, (int) Math.ceil(y1));
        int clip = Primitive.clip(list.header(i));
        if (clip != DrawList.NO_CLIP) { // the clip's rectangle (its rounded corners are left out here)
            float[] c = list.clips();
            int o = clip * DrawList.CLIP_FLOATS;
            px0 = Math.max(px0, (int) Math.floor(c[o]));
            py0 = Math.max(py0, (int) Math.floor(c[o + 1]));
            px1 = Math.min(px1, (int) Math.ceil(c[o + 2]));
            py1 = Math.min(py1, (int) Math.ceil(c[o + 3]));
        }
        for (int py = py0; py < py1; py++) {
            float v = v0 + (py + 0.5f - y0) / (y1 - y0) * (v1 - v0);
            for (int px = px0; px < px1; px++) {
                float u = u0 + (px + 0.5f - x0) / (x1 - x0) * (u1 - u0);
                sample(page, u, v, s);
                if (colorGlyph) {
                    blend(rgb, (py * width + px) * 3, s[0] * Rgba.r(color), s[1] * Rgba.g(color),
                        s[2] * Rgba.b(color), s[3] * Rgba.a(color));
                    continue;
                }
                if (raster) {
                    blend(rgb, (py * width + px) * 3, Rgba.r(color), Rgba.g(color), Rgba.b(color),
                        Rgba.a(color) * s[3]);
                    continue;
                }
                float sd = median(s[0], s[1], s[2]);
                float fill = saturate(range * (sd - 0.5f) + weight + 0.5f);
                float r;
                float g;
                float b;
                float a;
                if (outline > 0) {
                    float line = saturate(range * (s[3] - 0.5f) + weight + outline + 0.5f);
                    a = lerp(Rgba.a(outlineColor) * line, Rgba.a(color), fill);
                    r = lerp(Rgba.r(outlineColor), Rgba.r(color), fill);
                    g = lerp(Rgba.g(outlineColor), Rgba.g(color), fill);
                    b = lerp(Rgba.b(outlineColor), Rgba.b(color), fill);
                } else {
                    a = Rgba.a(color) * fill;
                    r = Rgba.r(color);
                    g = Rgba.g(color);
                    b = Rgba.b(color);
                }
                blend(rgb, (py * width + px) * 3, r, g, b, a);
            }
        }
    }

    private static void blend(float[] rgb, int o, float r, float g, float b, float a) {
        rgb[o] = rgb[o] * (1 - a) + r * a;
        rgb[o + 1] = rgb[o + 1] * (1 - a) + g * a;
        rgb[o + 2] = rgb[o + 2] * (1 - a) + b * a;
    }

    /** Bilinear sample of {@code page} at {@code (u, v)}, clamped to the edge, channels 0 to 1. */
    static void sample(UiTexture page, float u, float v, float[] out) {
        int w = page.width();
        int h = page.height();
        float x = u * w - 0.5f;
        float y = v * h - 0.5f;
        int xa = (int) Math.floor(x);
        int ya = (int) Math.floor(y);
        float fx = x - xa;
        float fy = y - ya;
        byte[] p = page.pixels();
        boolean srgb = page.format() == UiTexture.Format.RGBA8_SRGB;
        for (int c = 0; c < 4; c++) {
            boolean decode = srgb && c < 3;
            float t00 = texel(p, w, h, xa, ya, c, decode);
            float t10 = texel(p, w, h, xa + 1, ya, c, decode);
            float t01 = texel(p, w, h, xa, ya + 1, c, decode);
            float t11 = texel(p, w, h, xa + 1, ya + 1, c, decode);
            out[c] = lerp(lerp(t00, t10, fx), lerp(t01, t11, fx), fy);
        }
    }

    private static float texel(byte[] p, int w, int h, int x, int y, int c, boolean decode) {
        int cx = Math.max(0, Math.min(w - 1, x));
        int cy = Math.max(0, Math.min(h - 1, y));
        int v = p[(cy * w + cx) * 4 + c] & 0xFF;
        return decode ? ColorBitmaps.linear(v) : v / 255f;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float saturate(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
