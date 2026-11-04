package com.truesim.ui.controls.text;

import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.ColorBitmaps;
import com.truesim.ui.text.SoftwareGlyphRenderer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * A CPU preview of draw lists for screenshots without a GPU (tests only): rounded boxes with borders by their signed
 * distance, images sampled bilinearly, glyphs through {@link SoftwareGlyphRenderer} (the shader's formula, after the
 * boxes and images of the same list), blended in linear light, clipped to clip rectangles. Shadows, gradients and
 * lines are left out. The engine renders the real screenshots.
 */
final class CpuPreview {

    private final int w;
    private final int h;
    private final float[] rgb;

    CpuPreview(int width, int height, long background) {
        w = width;
        h = height;
        rgb = new float[w * h * 3];
        for (int i = 0; i < w * h; i++) {
            rgb[i * 3] = Rgba.r(background);
            rgb[i * 3 + 1] = Rgba.g(background);
            rgb[i * 3 + 2] = Rgba.b(background);
        }
    }

    CpuPreview draw(DrawList[] layers, int count, UiTextures textures) {
        for (int l = 0; l < count; l++) {
            DrawList list = layers[l].baked(); // where its primitives draw: transforms and clip offsets applied
            for (int i = 0; i < list.size(); i++) {
                int kind = Primitive.kind(list.header(i));
                if (kind == Primitive.BOX) {
                    box(list, i);
                } else if (kind == Primitive.IMAGE) {
                    image(list, i, textures);
                }
            }
            SoftwareGlyphRenderer.render(list, textures, rgb, w, h);
        }
        return this;
    }

    private float[] clip(DrawList list, int i) {
        int c = Primitive.clip(list.header(i));
        if (c == DrawList.NO_CLIP) {
            return new float[] {0, 0, w, h};
        }
        float[] all = list.clips();
        int o = c * DrawList.CLIP_FLOATS;
        return new float[] {all[o], all[o + 1], all[o + 2], all[o + 3]};
    }

    private static float half(int bits) {
        return Float.float16ToFloat((short) bits);
    }

    private void box(DrawList list, int i) {
        float x0 = list.floatAt(i, Primitive.X0);
        float y0 = list.floatAt(i, Primitive.Y0);
        float x1 = list.floatAt(i, Primitive.X1);
        float y1 = list.floatAt(i, Primitive.Y1);
        long fill = list.colorAt(i, Primitive.COLOR_A);
        int ra = list.intAt(i, Primitive.RADII);
        int rb = list.intAt(i, Primitive.RADII + 1);
        float[] radii = {half(ra), half(ra >>> 16), half(rb), half(rb >>> 16)}; // tl, tr, br, bl
        float bw = list.floatAt(i, Primitive.WIDTH);
        long border = list.colorAt(i, Primitive.BORDER_COLOR);
        float[] c = clip(list, i);
        int px0 = (int) Math.max(Math.floor(x0) - 1, Math.floor(c[0]));
        int py0 = (int) Math.max(Math.floor(y0) - 1, Math.floor(c[1]));
        int px1 = (int) Math.min(Math.ceil(x1) + 1, Math.ceil(c[2]));
        int py1 = (int) Math.min(Math.ceil(y1) + 1, Math.ceil(c[3]));
        float cx = (x0 + x1) / 2;
        float cy = (y0 + y1) / 2;
        float hx = (x1 - x0) / 2;
        float hy = (y1 - y0) / 2;
        for (int py = Math.max(0, py0); py < Math.min(h, py1); py++) {
            for (int px = Math.max(0, px0); px < Math.min(w, px1); px++) {
                float x = px + 0.5f - cx;
                float y = py + 0.5f - cy;
                float r = x < 0 ? (y < 0 ? radii[0] : radii[3]) : (y < 0 ? radii[1] : radii[2]);
                r = Math.min(r, Math.min(hx, hy));
                float qx = Math.abs(x) - hx + r;
                float qy = Math.abs(y) - hy + r;
                float d = (float) Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r;
                float inside = clamp(0.5f - d);
                if (inside <= 0) {
                    continue;
                }
                float fillA = Rgba.a(fill) * clamp(0.5f - (d + bw));
                blend(px, py, Rgba.r(fill), Rgba.g(fill), Rgba.b(fill), fillA);
                if (bw > 0) {
                    float edge = inside - clamp(0.5f - (d + bw));
                    blend(px, py, Rgba.r(border), Rgba.g(border), Rgba.b(border), Rgba.a(border) * edge);
                }
            }
        }
    }

    private void image(DrawList list, int i, UiTextures textures) {
        UiTexture t = textures.get(list.intAt(i, Primitive.TEXTURE));
        float x0 = list.floatAt(i, Primitive.X0);
        float y0 = list.floatAt(i, Primitive.Y0);
        float x1 = list.floatAt(i, Primitive.X1);
        float y1 = list.floatAt(i, Primitive.Y1);
        long tint = list.colorAt(i, Primitive.COLOR_A);
        float[] c = clip(list, i);
        for (int py = (int) Math.max(0, Math.max(y0, c[1])); py < Math.min(h, Math.min(y1, c[3])); py++) {
            for (int px = (int) Math.max(0, Math.max(x0, c[0])); px < Math.min(w, Math.min(x1, c[2])); px++) {
                int tx = (int) Math.min(t.width() - 1, (px + 0.5f - x0) / (x1 - x0) * t.width());
                int ty = (int) Math.min(t.height() - 1, (py + 0.5f - y0) / (y1 - y0) * t.height());
                byte[] p = t.pixels();
                int o = (ty * t.width() + tx) * 4;
                float a = (p[o + 3] & 0xFF) / 255f * Rgba.a(tint);
                blend(px, py, ColorBitmaps.linear(p[o]) * Rgba.r(tint), ColorBitmaps.linear(p[o + 1]) * Rgba.g(tint),
                    ColorBitmaps.linear(p[o + 2]) * Rgba.b(tint), a);
            }
        }
    }

    private void blend(int px, int py, float r, float g, float b, float a) {
        int o = (py * w + px) * 3;
        rgb[o] = rgb[o] * (1 - a) + r * a;
        rgb[o + 1] = rgb[o + 1] * (1 - a) + g * a;
        rgb[o + 2] = rgb[o + 2] * (1 - a) + b * a;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    void save(Path file) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 3;
                image.setRGB(x, y, enc(rgb[o]) << 16 | enc(rgb[o + 1]) << 8 | enc(rgb[o + 2]));
            }
        }
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    private static int enc(float linear) {
        return Math.round(Rgba.toSrgb(Math.max(0, Math.min(1, linear))) * 255);
    }

    /** Where screenshots go: {@code TRUESIM_SCREENSHOTS_DIR}, or null when screenshots are off. */
    static Path dir() {
        String d = System.getenv("TRUESIM_SCREENSHOTS_DIR");
        if (!"true".equals(System.getenv("TRUESIM_SCREENSHOTS"))) {
            return null;
        }
        return Path.of(d == null ? "build/screenshots" : d);
    }
}
