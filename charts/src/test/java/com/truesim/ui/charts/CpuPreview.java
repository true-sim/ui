package com.truesim.ui.charts;

import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.SoftwareGlyphRenderer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * A CPU preview of draw lists for the charts (tests only; the engine's GPU renderer is the real one), as ui-controls'
 * but with what charts draw most: anti-aliased line segments with butt ends (as the engine's shader), triangles with
 * anti-aliased and hard edges (a hard edge covers by the half-open rule, so triangles sharing it never cover a pixel
 * twice), images with their texture regions, and clips. Linear light, written as sRGB PNG.
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
            DrawList list = layers[l];
            for (int i = 0; i < list.size(); i++) {
                switch (Primitive.kind(list.header(i))) {
                    case Primitive.BOX -> box(list, i);
                    case Primitive.SHADOW -> shadow(list, i);
                    case Primitive.IMAGE -> image(list, i, textures);
                    case Primitive.LINE -> line(list, i);
                    case Primitive.TRIANGLE -> triangle(list, i);
                    default -> {
                        // glyphs after the layer's shapes
                    }
                }
            }
            SoftwareGlyphRenderer.render(list, textures, rgb, w, h);
        }
        return this;
    }

    void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 3;
                image.setRGB(x, y, enc(rgb[o]) << 16 | enc(rgb[o + 1]) << 8 | enc(rgb[o + 2]));
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }

    private static int enc(float linear) {
        return Math.round(Rgba.toSrgb(Math.max(0, Math.min(1, linear))) * 255);
    }

    private void blend(int x, int y, long c, float coverage) {
        float a = Rgba.a(c) * coverage;
        if (a <= 0 || x < 0 || y < 0 || x >= w || y >= h) {
            return;
        }
        int o = (y * w + x) * 3;
        rgb[o] = rgb[o] * (1 - a) + Rgba.r(c) * a;
        rgb[o + 1] = rgb[o + 1] * (1 - a) + Rgba.g(c) * a;
        rgb[o + 2] = rgb[o + 2] * (1 - a) + Rgba.b(c) * a;
    }

    private static float half(int word, boolean high) {
        return Float.float16ToFloat((short) (high ? word >>> 16 : word));
    }

    private static float sdf(float px, float py, float x0, float y0, float x1, float y1, float[] radii) {
        float cx = (x0 + x1) * 0.5f;
        float cy = (y0 + y1) * 0.5f;
        float hx = (x1 - x0) * 0.5f;
        float hy = (y1 - y0) * 0.5f;
        float dx = px - cx;
        float dy = py - cy;
        float r = dx < 0 ? (dy < 0 ? radii[0] : radii[3]) : (dy < 0 ? radii[1] : radii[2]);
        r = Math.min(r, Math.min(hx, hy));
        float qx = Math.abs(dx) - hx + r;
        float qy = Math.abs(dy) - hy + r;
        float ox = Math.max(qx, 0);
        float oy = Math.max(qy, 0);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - r;
    }

    private static boolean clipped(DrawList list, int i, float px, float py) {
        int clip = Primitive.clip(list.header(i));
        if (clip == DrawList.NO_CLIP) {
            return false;
        }
        float[] c = list.clips();
        int o = clip * DrawList.CLIP_FLOATS;
        float[] radii = {c[o + 4], c[o + 4], c[o + 4], c[o + 4]};
        return sdf(px, py, c[o], c[o + 1], c[o + 2], c[o + 3], radii) > 0;
    }

    private void box(DrawList list, int i) {
        float x0 = list.floatAt(i, Primitive.X0);
        float y0 = list.floatAt(i, Primitive.Y0);
        float x1 = list.floatAt(i, Primitive.X1);
        float y1 = list.floatAt(i, Primitive.Y1);
        int ra = list.intAt(i, Primitive.RADII);
        int rb = list.intAt(i, Primitive.RADII + 1);
        float[] radii = {half(ra, false), half(ra, true), half(rb, false), half(rb, true)};
        float bw = list.floatAt(i, Primitive.WIDTH);
        long a = list.colorAt(i, Primitive.COLOR_A);
        long b = list.colorAt(i, Primitive.COLOR_B);
        long border = list.colorAt(i, Primitive.BORDER_COLOR);
        boolean gradient = (Primitive.flags(list.header(i)) & Primitive.FLAG_GRADIENT) != 0;
        for (int y = (int) Math.floor(y0); y < Math.ceil(y1); y++) {
            for (int x = (int) Math.floor(x0); x < Math.ceil(x1); x++) {
                float px = x + 0.5f;
                float py = y + 0.5f;
                if (clipped(list, i, px, py)) {
                    continue;
                }
                float d = sdf(px, py, x0, y0, x1, y1, radii);
                float cover = Math.max(0, Math.min(1, 0.5f - d));
                if (cover <= 0) {
                    continue;
                }
                long fill = a;
                if (gradient) {
                    float t = Math.max(0, Math.min(1, (px - x0) / Math.max(1, x1 - x0)));
                    fill = Rgba.pack(Rgba.r(a) + (Rgba.r(b) - Rgba.r(a)) * t, Rgba.g(a) + (Rgba.g(b) - Rgba.g(a)) * t,
                        Rgba.b(a) + (Rgba.b(b) - Rgba.b(a)) * t, Rgba.a(a) + (Rgba.a(b) - Rgba.a(a)) * t);
                }
                blend(x, y, bw > 0 && d > -bw ? border : fill, cover);
            }
        }
    }

    private void shadow(DrawList list, int i) {
        float x0 = list.floatAt(i, Primitive.X0);
        float y0 = list.floatAt(i, Primitive.Y0);
        float x1 = list.floatAt(i, Primitive.X1);
        float y1 = list.floatAt(i, Primitive.Y1);
        int ra = list.intAt(i, Primitive.RADII);
        int rb = list.intAt(i, Primitive.RADII + 1);
        float[] radii = {half(ra, false), half(ra, true), half(rb, false), half(rb, true)};
        float sigma = Math.max(0.5f, list.floatAt(i, Primitive.WIDTH));
        long c = list.colorAt(i, Primitive.COLOR_A);
        int pad = (int) Math.ceil(sigma * 3);
        for (int y = (int) Math.floor(y0) - pad; y < Math.ceil(y1) + pad; y++) {
            for (int x = (int) Math.floor(x0) - pad; x < Math.ceil(x1) + pad; x++) {
                float d = sdf(x + 0.5f, y + 0.5f, x0, y0, x1, y1, radii);
                float t = Math.max(0, Math.min(1, 0.5f - d / (2.5f * sigma)));
                blend(x, y, c, t * t * (3 - 2 * t));
            }
        }
    }

    private void image(DrawList list, int i, UiTextures textures) {
        UiTexture t = textures.get(list.intAt(i, Primitive.TEXTURE));
        if (t == null || t.format() != UiTexture.Format.RGBA8_SRGB) {
            return;
        }
        float x0 = list.floatAt(i, Primitive.X0);
        float y0 = list.floatAt(i, Primitive.Y0);
        float x1 = list.floatAt(i, Primitive.X1);
        float y1 = list.floatAt(i, Primitive.Y1);
        int uv0 = list.intAt(i, Primitive.UV);
        int uv1 = list.intAt(i, Primitive.UV + 1);
        float u0 = (uv0 & 0xFFFF) / 65535f;
        float v0 = (uv0 >>> 16) / 65535f;
        float u1 = (uv1 & 0xFFFF) / 65535f;
        float v1 = (uv1 >>> 16) / 65535f;
        long tint = list.colorAt(i, Primitive.COLOR_A);
        byte[] px = t.pixels();
        for (int y = (int) Math.floor(y0); y < Math.ceil(y1); y++) {
            for (int x = (int) Math.floor(x0); x < Math.ceil(x1); x++) {
                if (clipped(list, i, x + 0.5f, y + 0.5f)) {
                    continue;
                }
                float u = u0 + (u1 - u0) * (x + 0.5f - x0) / (x1 - x0);
                float v = v0 + (v1 - v0) * (y + 0.5f - y0) / (y1 - y0);
                int tx = Math.max(0, Math.min(t.width() - 1, (int) (u * t.width())));
                int ty = Math.max(0, Math.min(t.height() - 1, (int) (v * t.height())));
                int o = (ty * t.width() + tx) * 4;
                long c = Rgba.pack(Rgba.toLinear((px[o] & 255) / 255f) * Rgba.r(tint),
                    Rgba.toLinear((px[o + 1] & 255) / 255f) * Rgba.g(tint),
                    Rgba.toLinear((px[o + 2] & 255) / 255f) * Rgba.b(tint), (px[o + 3] & 255) / 255f * Rgba.a(tint));
                blend(x, y, c, 1);
            }
        }
    }

    /** A segment with butt ends: coverage by the distance to its axis and along it; at least a pixel wide. */
    private void line(DrawList list, int i) {
        float xa = list.floatAt(i, Primitive.X0);
        float ya = list.floatAt(i, Primitive.Y0);
        float xb = list.floatAt(i, Primitive.X1);
        float yb = list.floatAt(i, Primitive.Y1);
        float width = list.floatAt(i, Primitive.WIDTH);
        long c = list.colorAt(i, Primitive.COLOR_A);
        float fade = Math.min(1, width);
        float half = Math.max(1, width) * 0.5f;
        float dx = xb - xa;
        float dy = yb - ya;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (!(len > 0)) {
            return;
        }
        float ux = dx / len;
        float uy = dy / len;
        int x0 = (int) Math.floor(Math.min(xa, xb) - half - 1);
        int x1 = (int) Math.ceil(Math.max(xa, xb) + half + 1);
        int y0 = (int) Math.floor(Math.min(ya, yb) - half - 1);
        int y1 = (int) Math.ceil(Math.max(ya, yb) + half + 1);
        for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
                float px = x + 0.5f - xa;
                float py = y + 0.5f - ya;
                float along = px * ux + py * uy;
                float across = Math.abs(-px * uy + py * ux);
                float cover = Math.max(0, Math.min(1, half + 0.5f - across))
                    * Math.max(0, Math.min(1, Math.min(along + 0.5f, len - along + 0.5f)));
                if (cover > 0 && !clipped(list, i, x + 0.5f, y + 0.5f)) {
                    blend(x, y, c, cover * fade);
                }
            }
        }
    }

    /** A triangle: anti-aliased edges by distance, hard edges by the half-open rule. */
    private void triangle(DrawList list, int i) {
        float[] v = {list.floatAt(i, Primitive.XA), list.floatAt(i, Primitive.YA), list.floatAt(i, Primitive.XB),
            list.floatAt(i, Primitive.YB), list.floatAt(i, Primitive.XC), list.floatAt(i, Primitive.YC)};
        int edges = Primitive.flags(list.header(i));
        long c = list.colorAt(i, Primitive.COLOR_A);
        float area = (v[2] - v[0]) * (v[5] - v[1]) - (v[3] - v[1]) * (v[4] - v[0]);
        if (area == 0) {
            return;
        }
        float sign = area > 0 ? 1 : -1;
        int x0 = (int) Math.floor(list.floatAt(i, Primitive.X0)) - 1;
        int y0 = (int) Math.floor(list.floatAt(i, Primitive.Y0)) - 1;
        int x1 = (int) Math.ceil(list.floatAt(i, Primitive.X1)) + 1;
        int y1 = (int) Math.ceil(list.floatAt(i, Primitive.Y1)) + 1;
        for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
                float px = x + 0.5f;
                float py = y + 0.5f;
                float cover = 1;
                for (int e = 0; e < 3 && cover > 0; e++) {
                    float ax = v[e * 2];
                    float ay = v[e * 2 + 1];
                    float bx = v[(e * 2 + 2) % 6];
                    float by = v[(e * 2 + 3) % 6];
                    float ex = bx - ax;
                    float ey = by - ay;
                    float len = (float) Math.sqrt(ex * ex + ey * ey);
                    if (len == 0) {
                        continue;
                    }
                    // signed distance, positive inside
                    float d = sign * (ex * (py - ay) - ey * (px - ax)) / len;
                    if ((edges & (1 << e)) != 0) {
                        cover *= Math.max(0, Math.min(1, d + 0.5f));
                    } else if (d < 0 || d == 0 && !(ey > 0 || ey == 0 && ex < 0)) {
                        cover = 0;
                    }
                }
                if (cover > 0 && !clipped(list, i, px, py)) {
                    blend(x, y, c, cover);
                }
            }
        }
    }
}
