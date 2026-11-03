package com.truesim.ui.text.jdk;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.GlyphOutline;
import com.truesim.ui.text.MsdfGenerator;
import com.truesim.ui.text.MsdfGeometry;
import java.util.Arrays;

/**
 * A plain (single-channel) signed distance field in all four channels, by brute force over the flattened outline:
 * the JDK fallback for headless tests and tools. Its median is the distance itself, so it draws through the MTSDF
 * shader unchanged, with rounded instead of sharp corners. Thread-safe and deterministic; msdfgen
 * ({@code ui-text-native}) is the real generator.
 */
@AllowAllocation("the JDK fallback: allocates per glyph, on generator threads")
public final class JdkSdfGenerator implements MsdfGenerator {

    private static final int QUAD_STEPS = 16;
    private static final int CUBIC_STEPS = 24;

    @Override
    public byte[] generate(GlyphOutline outline, MsdfGeometry g) {
        double[] segments = flatten(outline);
        int n = segments.length / 4;
        int w = g.width();
        int h = g.height();
        byte[] out = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            double py = (y + 0.5) / g.scale() - g.translateY();
            int row = (h - 1 - y) * w * 4; // texture rows run top to bottom
            for (int x = 0; x < w; x++) {
                double px = (x + 0.5) / g.scale() - g.translateX();
                double best = Double.MAX_VALUE;
                int winding = 0;
                for (int s = 0; s < n; s++) {
                    double x0 = segments[s * 4];
                    double y0 = segments[s * 4 + 1];
                    double x1 = segments[s * 4 + 2];
                    double y1 = segments[s * 4 + 3];
                    best = Math.min(best, distanceSquared(px, py, x0, y0, x1, y1));
                    if (y0 <= py && py < y1 && cross(px, py, x0, y0, x1, y1) > 0) {
                        winding++;
                    } else if (y1 <= py && py < y0 && cross(px, py, x0, y0, x1, y1) < 0) {
                        winding--;
                    }
                }
                double distance = winding != 0 ? Math.sqrt(best) : -Math.sqrt(best);
                byte v = quantize(0.5 + distance / g.range());
                int o = row + x * 4;
                out[o] = v;
                out[o + 1] = v;
                out[o + 2] = v;
                out[o + 3] = v;
            }
        }
        return out;
    }

    /** Which side of the segment the point is on: positive left of it, looking from start to end. */
    private static double cross(double px, double py, double x0, double y0, double x1, double y1) {
        return (x1 - x0) * (py - y0) - (px - x0) * (y1 - y0);
    }

    private static double distanceSquared(double px, double py, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double len = dx * dx + dy * dy;
        double t = len > 0 ? ((px - x0) * dx + (py - y0) * dy) / len : 0;
        t = Math.max(0, Math.min(1, t));
        double ex = x0 + t * dx - px;
        double ey = y0 + t * dy - py;
        return ex * ex + ey * ey;
    }

    static byte quantize(double v) {
        double c = Math.max(0, Math.min(1, v));
        return (byte) (int) Math.round(c * 255);
    }

    /** The outline as line segments (x0, y0, x1, y1), every contour closed. */
    static double[] flatten(GlyphOutline o) {
        double[] s = new double[256];
        int n = 0;
        float[] p = o.points();
        int pi = 0;
        double sx = 0;
        double sy = 0;
        double cx = 0;
        double cy = 0;
        for (int v = 0; v < o.verbCount(); v++) {
            byte verb = o.verbs()[v];
            if (verb == GlyphOutline.MOVE) {
                if (cx != sx || cy != sy) {
                    s = add(s, n, cx, cy, sx, sy);
                    n += 4;
                }
                sx = p[pi];
                sy = p[pi + 1];
                cx = sx;
                cy = sy;
                pi += 2;
            } else if (verb == GlyphOutline.LINE) {
                s = add(s, n, cx, cy, p[pi], p[pi + 1]);
                n += 4;
                cx = p[pi];
                cy = p[pi + 1];
                pi += 2;
            } else if (verb == GlyphOutline.QUAD) {
                double qx = p[pi];
                double qy = p[pi + 1];
                double ex = p[pi + 2];
                double ey = p[pi + 3];
                for (int i = 1; i <= QUAD_STEPS; i++) {
                    double t = (double) i / QUAD_STEPS;
                    double u = 1 - t;
                    double x = u * u * cx + 2 * u * t * qx + t * t * ex;
                    double y = u * u * cy + 2 * u * t * qy + t * t * ey;
                    double lx = i == 1 ? cx : s[n - 2];
                    double ly = i == 1 ? cy : s[n - 1];
                    s = add(s, n, lx, ly, x, y);
                    n += 4;
                }
                cx = ex;
                cy = ey;
                pi += 4;
            } else {
                double ax = p[pi];
                double ay = p[pi + 1];
                double bx = p[pi + 2];
                double by = p[pi + 3];
                double ex = p[pi + 4];
                double ey = p[pi + 5];
                for (int i = 1; i <= CUBIC_STEPS; i++) {
                    double t = (double) i / CUBIC_STEPS;
                    double u = 1 - t;
                    double x = u * u * u * cx + 3 * u * u * t * ax + 3 * u * t * t * bx + t * t * t * ex;
                    double y = u * u * u * cy + 3 * u * u * t * ay + 3 * u * t * t * by + t * t * t * ey;
                    double lx = i == 1 ? cx : s[n - 2];
                    double ly = i == 1 ? cy : s[n - 1];
                    s = add(s, n, lx, ly, x, y);
                    n += 4;
                }
                cx = ex;
                cy = ey;
                pi += 6;
            }
        }
        if (cx != sx || cy != sy) {
            s = add(s, n, cx, cy, sx, sy);
            n += 4;
        }
        return Arrays.copyOf(s, n);
    }

    private static double[] add(double[] s, int n, double x0, double y0, double x1, double y1) {
        double[] a = n + 4 > s.length ? Arrays.copyOf(s, s.length * 2) : s;
        a[n] = x0;
        a[n + 1] = y0;
        a[n + 2] = x1;
        a[n + 3] = y1;
        return a;
    }
}
