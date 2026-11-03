package com.truesim.ui.text.jdk;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.FontFace;
import com.truesim.ui.text.GlyphOutline;
import com.truesim.ui.text.GlyphRasterizer;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * Coverage glyphs by Java2D's anti-aliased fill of the outline: unhinted, the JDK fallback of the hinted FreeType
 * rasterizer. Thread-safe (no state).
 */
@AllowAllocation("the JDK fallback for headless tests and tools: java.awt allocates")
public final class JdkRasterizer implements GlyphRasterizer {

    @Override
    public Coverage rasterize(FontFace face, int glyph, float sizePx, float offsetX) {
        GlyphOutline outline = new GlyphOutline();
        float[] bounds = new float[4];
        if (!face.outline(glyph, outline)) {
            return null;
        }
        outline.bounds(bounds);
        int left = (int) Math.floor(bounds[0] * sizePx + offsetX);
        int top = (int) Math.ceil(bounds[3] * sizePx);
        int w = (int) Math.ceil(bounds[2] * sizePx + offsetX) - left;
        int h = top - (int) Math.floor(bounds[1] * sizePx);
        if (w <= 0 || h <= 0) {
            return null;
        }
        Path2D.Float path = new Path2D.Float(Path2D.WIND_NON_ZERO);
        float[] p = outline.points();
        int pi = 0;
        for (int v = 0; v < outline.verbCount(); v++) {
            switch (outline.verbs()[v]) {
                case GlyphOutline.MOVE -> {
                    path.moveTo(x(p[pi], sizePx, left, offsetX), y(p[pi + 1], sizePx, top));
                    pi += 2;
                }
                case GlyphOutline.LINE -> {
                    path.lineTo(x(p[pi], sizePx, left, offsetX), y(p[pi + 1], sizePx, top));
                    pi += 2;
                }
                case GlyphOutline.QUAD -> {
                    path.quadTo(x(p[pi], sizePx, left, offsetX), y(p[pi + 1], sizePx, top),
                        x(p[pi + 2], sizePx, left, offsetX), y(p[pi + 3], sizePx, top));
                    pi += 4;
                }
                default -> {
                    path.curveTo(x(p[pi], sizePx, left, offsetX), y(p[pi + 1], sizePx, top),
                        x(p[pi + 2], sizePx, left, offsetX), y(p[pi + 3], sizePx, top),
                        x(p[pi + 4], sizePx, left, offsetX), y(p[pi + 5], sizePx, top));
                    pi += 6;
                }
            }
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.fill(path);
        g.dispose();
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData().clone();
        return new Coverage(w, h, left, top, pixels);
    }

    private static float x(float em, float size, int left, float offset) {
        return em * size + offset - left;
    }

    private static float y(float em, float size, int top) {
        return top - em * size;
    }
}
