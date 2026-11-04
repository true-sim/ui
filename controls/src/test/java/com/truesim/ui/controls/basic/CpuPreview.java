package com.truesim.ui.controls.basic;

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
 * A rough CPU preview of draw lists (tests only; the engine's GPU renderer is the real one): rounded boxes with
 * borders and linear gradients, soft shadows, images, lines, triangles and clips per layer, then the layer's glyphs
 * through {@link SoftwareGlyphRenderer} (so text draws over its layer's boxes). Linear light, written as sRGB PNG.
 * Depth tested primitives ({@link Primitive#FLAG_DEPTH}: a gizmo's) are faded as the engine's shader fades them where
 * a {@link SceneDepth} given by {@link #depth} is nearer; without one they draw unfaded.
 */
final class CpuPreview {

    private final int w;
    private final int h;
    private final float[] rgb;
    private SceneDepth scene;
    private boolean orthographic;

    /** The view depth of the scene under UI pixel {@code x, y}: infinite where it shows nothing. */
    @FunctionalInterface
    interface SceneDepth {
        double at(float x, float y);
    }

    /** As the engine's default {@code ui.gizmo.depthBias}: how much farther (relative) a part must be to be hidden. */
    static final float DEPTH_BIAS = 1e-4f;

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

    /** Fades depth tested primitives where {@code depth}, the scene under the UI, is nearer; returns this. */
    CpuPreview depth(SceneDepth depth, boolean orthographicView) {
        scene = depth;
        orthographic = orthographicView;
        return this;
    }

    /** As the engine's shader: the occluded opacity of primitive {@code i} where the scene is nearer, else 1. */
    private float depthTest(DrawList list, int i, float px, float py) {
        if (scene == null || (Primitive.flags(list.header(i)) & Primitive.FLAG_DEPTH) == 0) {
            return 1;
        }
        float z = list.depthAt(i, px, py, orthographic);
        return z > scene.at(px, py) * (1 + DEPTH_BIAS)
            ? Primitive.occludedOpacityOf(list.intAt(i, Primitive.DEPTH)) : 1;
    }

    /** Draws the layers back to front. */
    CpuPreview draw(DrawList[] layers, int count, UiTextures textures) {
        for (int l = 0; l < count; l++) {
            DrawList list = layers[l].baked(); // where its primitives draw: transforms and clip offsets applied
            for (int i = 0; i < list.size(); i++) {
                int kind = Primitive.kind(list.header(i));
                switch (kind) {
                    case Primitive.BOX -> box(list, i);
                    case Primitive.SHADOW -> shadow(list, i);
                    case Primitive.IMAGE -> image(list, i, textures);
                    case Primitive.LINE -> line(list, i);
                    case Primitive.TRIANGLE -> triangle(list, i);
                    default -> {
                        // glyphs after the layer's shapes; nine-slices are not previewed
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
        float c = Math.max(0, Math.min(1, linear));
        return Math.round(Rgba.toSrgb(c) * 255);
    }

    private void blend(int x, int y, float r, float g, float b, float a) {
        if (a <= 0 || x < 0 || y < 0 || x >= w || y >= h) {
            return;
        }
        int o = (y * w + x) * 3;
        rgb[o] = rgb[o] * (1 - a) + r * a;
        rgb[o + 1] = rgb[o + 1] * (1 - a) + g * a;
        rgb[o + 2] = rgb[o + 2] * (1 - a) + b * a;
    }

    private static float half(int word, boolean high) {
        return Float.float16ToFloat((short) (high ? word >>> 16 : word));
    }

    /** Signed distance to a rounded box (negative inside). */
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

    private boolean clipped(DrawList list, int i, float px, float py) {
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
        boolean depthTested = (Primitive.flags(list.header(i)) & Primitive.FLAG_DEPTH) != 0;
        long b = depthTested ? 0 : list.colorAt(i, Primitive.COLOR_B);
        long border = list.colorAt(i, Primitive.BORDER_COLOR);
        boolean gradient = (Primitive.flags(list.header(i)) & Primitive.FLAG_GRADIENT) != 0;
        float angle = list.floatAt(i, Primitive.GRADIENT_ANGLE);
        float gx = (float) Math.cos(angle);
        float gy = (float) Math.sin(angle);
        float half = Math.abs(gx) * (x1 - x0) * 0.5f + Math.abs(gy) * (y1 - y0) * 0.5f;
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
                if (gradient && half > 0) {
                    float t = ((px - (x0 + x1) * 0.5f) * gx + (py - (y0 + y1) * 0.5f) * gy) / (2 * half) + 0.5f;
                    t = Math.max(0, Math.min(1, t));
                    fill = Rgba.pack(lerp(Rgba.r(a), Rgba.r(b), t), lerp(Rgba.g(a), Rgba.g(b), t),
                        lerp(Rgba.b(a), Rgba.b(b), t), lerp(Rgba.a(a), Rgba.a(b), t));
                }
                boolean inBorder = bw > 0 && d > -bw;
                long c = inBorder ? border : fill;
                blend(x, y, Rgba.r(c), Rgba.g(c), Rgba.b(c), Rgba.a(c) * cover * depthTest(list, i, px, py));
            }
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
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
                blend(x, y, Rgba.r(c), Rgba.g(c), Rgba.b(c), Rgba.a(c) * t * t * (3 - 2 * t));
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
        long tint = list.colorAt(i, Primitive.COLOR_A);
        int uv0 = list.intAt(i, Primitive.UV);
        int uv1 = list.intAt(i, Primitive.UV + 1);
        float u0 = (uv0 & 0xffff) / 65535f;
        float v0 = (uv0 >>> 16) / 65535f;
        float u1 = (uv1 & 0xffff) / 65535f;
        float v1 = (uv1 >>> 16) / 65535f;
        byte[] px = t.pixels();
        for (int y = (int) Math.floor(y0); y < Math.ceil(y1); y++) {
            for (int x = (int) Math.floor(x0); x < Math.ceil(x1); x++) {
                if (clipped(list, i, x + 0.5f, y + 0.5f)) {
                    continue;
                }
                int tx = (int) ((u0 + (x + 0.5f - x0) / (x1 - x0) * (u1 - u0)) * t.width());
                int ty = (int) ((v0 + (y + 0.5f - y0) / (y1 - y0) * (v1 - v0)) * t.height());
                tx = Math.max(0, Math.min(t.width() - 1, tx));
                ty = Math.max(0, Math.min(t.height() - 1, ty));
                int o = (ty * t.width() + tx) * 4;
                float r = Rgba.toLinear((px[o] & 255) / 255f) * Rgba.r(tint);
                float g = Rgba.toLinear((px[o + 1] & 255) / 255f) * Rgba.g(tint);
                float b = Rgba.toLinear((px[o + 2] & 255) / 255f) * Rgba.b(tint);
                float a = (px[o + 3] & 255) / 255f * Rgba.a(tint);
                blend(x, y, r, g, b, a);
            }
        }
    }

    private void line(DrawList list, int i) {
        float xa = list.floatAt(i, Primitive.X0);
        float ya = list.floatAt(i, Primitive.Y0);
        float xb = list.floatAt(i, Primitive.X1);
        float yb = list.floatAt(i, Primitive.Y1);
        float width = list.floatAt(i, Primitive.WIDTH);
        long c = list.colorAt(i, Primitive.COLOR_A);
        // as the engine's shader: an anti-aliased segment with butt ends, at least a pixel wide (fainter)
        float dx = xb - xa;
        float dy = yb - ya;
        float len = (float) Math.hypot(dx, dy);
        float ax = len > 0 ? dx / len : 1;
        float ay = len > 0 ? dy / len : 0;
        float extent = Math.max(width, 1) * 0.5f;
        float faint = Math.min(width, 1);
        int x0 = (int) Math.floor(Math.min(xa, xb) - extent - 1);
        int x1 = (int) Math.ceil(Math.max(xa, xb) + extent + 1);
        int y0 = (int) Math.floor(Math.min(ya, yb) - extent - 1);
        int y1 = (int) Math.ceil(Math.max(ya, yb) + extent + 1);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (clipped(list, i, x + 0.5f, y + 0.5f)) {
                    continue;
                }
                float lx = x + 0.5f - xa;
                float ly = y + 0.5f - ya;
                float distance = Math.max(Math.abs(lx * ax + ly * ay - len * 0.5f) - len * 0.5f,
                    Math.abs(-lx * ay + ly * ax) - extent);
                float coverage = Math.max(0, Math.min(1, 0.5f - distance)) * faint;
                blend(x, y, Rgba.r(c), Rgba.g(c), Rgba.b(c),
                    Rgba.a(c) * coverage * depthTest(list, i, x + 0.5f, y + 0.5f));
            }
        }
    }

    /** A filled triangle by its three edges' distances, every edge anti-aliased (shared edges may show a faint seam). */
    private void triangle(DrawList list, int i) {
        float[] c = {list.floatAt(i, Primitive.XA), list.floatAt(i, Primitive.YA), list.floatAt(i, Primitive.XB),
            list.floatAt(i, Primitive.YB), list.floatAt(i, Primitive.XC), list.floatAt(i, Primitive.YC)};
        float area = (c[2] - c[0]) * (c[5] - c[1]) - (c[3] - c[1]) * (c[4] - c[0]);
        if (area == 0) {
            return;
        }
        float winding = area > 0 ? 1 : -1;
        int edges = Primitive.flags(list.header(i));
        long color = list.colorAt(i, Primitive.COLOR_A);
        for (int y = (int) Math.floor(list.floatAt(i, Primitive.Y0)) - 1; y <= Math.ceil(list.floatAt(i, Primitive.Y1));
            y++) {
            for (int x = (int) Math.floor(list.floatAt(i, Primitive.X0)) - 1;
                x <= Math.ceil(list.floatAt(i, Primitive.X1)); x++) {
                float px = x + 0.5f;
                float py = y + 0.5f;
                if (clipped(list, i, px, py)) {
                    continue;
                }
                float coverage = 1;
                for (int e = 0; e < 3; e++) {
                    float ax = c[2 * e];
                    float ay = c[2 * e + 1];
                    float dx = c[(2 * e + 2) % 6] - ax;
                    float dy = c[(2 * e + 3) % 6] - ay;
                    float d = (dx * (py - ay) - dy * (px - ax)) * winding / (float) Math.hypot(dx, dy);
                    boolean smooth = (edges & 1 << e) != 0;
                    coverage *= smooth ? Math.max(0, Math.min(1, d + 0.5f)) : (d >= 0 ? 1 : 0);
                }
                blend(x, y, Rgba.r(color), Rgba.g(color), Rgba.b(color),
                    Rgba.a(color) * coverage * depthTest(list, i, px, py));
            }
        }
    }
}
