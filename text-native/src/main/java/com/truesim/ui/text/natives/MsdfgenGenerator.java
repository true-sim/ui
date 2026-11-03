package com.truesim.ui.text.natives;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.msdfgen.MSDFGen.MSDF_BITMAP_TYPE_MTSDF;
import static org.lwjgl.util.msdfgen.MSDFGen.MSDF_DISTANCE_CHECK_MODE_AT_EDGE;
import static org.lwjgl.util.msdfgen.MSDFGen.MSDF_ERROR_CORRECTION_MODE_EDGE_PRIORITY;
import static org.lwjgl.util.msdfgen.MSDFGen.MSDF_SEGMENT_TYPE_LINEAR;
import static org.lwjgl.util.msdfgen.MSDFGen.MSDF_SEGMENT_TYPE_QUADRATIC;
import static org.lwjgl.util.msdfgen.MSDFGen.MSDF_SUCCESS;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_bitmap_alloc;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_bitmap_free;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_bitmap_get_pixels;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_contour_add_edge;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_contour_reverse;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_generate_mtsdf_with_config;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_segment_alloc;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_segment_set_point;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_shape_add_contour;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_shape_alloc;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_shape_edge_colors_simple;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_shape_free;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_shape_get_contour;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_shape_get_contour_count;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_shape_normalize;
import static org.lwjgl.util.msdfgen.MSDFGen.msdf_shape_one_shot_distance;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.GlyphOutline;
import com.truesim.ui.text.MsdfGenerator;
import com.truesim.ui.text.MsdfGeometry;
import java.nio.DoubleBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.msdfgen.MSDFGenBitmap;
import org.lwjgl.util.msdfgen.MSDFGenMultichannelConfig;
import org.lwjgl.util.msdfgen.MSDFGenTransform;
import org.lwjgl.util.msdfgen.MSDFGenVector2;

/**
 * MTSDF glyphs by msdfgen (UI11), the pipeline of msdfgen's own command line: the outline becomes a msdfgen shape
 * (edges from {@link GlyphOutline}'s commands, cubics as quadratics, every contour closed), normalised; an inverted orientation is detected
 * by the distance at a point outside the shape and fixed by reversing the contours; edges are coloured by the simple
 * strategy (angle threshold 3 radians, msdfgen's default); the MTSDF is generated with overlap support and error
 * correction (edge priority, distance checked at edges). Bytes are {@code round(clamp(v) * 255)}. Thread-safe and
 * deterministic: build time and run time get the same bytes.
 */
@AllowAllocation("runs on generator threads, once per new glyph")
public final class MsdfgenGenerator implements MsdfGenerator {

    /** msdfgen's default angle below which an edge joint is a corner. */
    private static final double CORNER_ANGLE = 3.0;
    /** msdfgen's defaults for error correction. */
    private static final double MIN_DEVIATION_RATIO = 1.11111111111111111;
    private static final double MIN_IMPROVE_RATIO = 1.11111111111111111;
    /** How far a quadratic standing in for a cubic may stray, in ems: a fiftieth of a texel at 40 texels per em. */
    private static final double CUBIC_TOLERANCE = 0.0005;

    @Override
    public byte[] generate(GlyphOutline outline, MsdfGeometry g) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            check(msdf_shape_alloc(p), "shape");
            long shape = p.get(0);
            try {
                build(shape, outline, stack);
                check(msdf_shape_normalize(shape), "normalize");
                orient(shape, outline, stack);
                check(msdf_shape_edge_colors_simple(shape, CORNER_ANGLE), "edge colours");
                return render(shape, g, stack);
            } finally {
                msdf_shape_free(shape);
            }
        }
    }

    private static void build(long shape, GlyphOutline o, MemoryStack stack) {
        PointerBuffer p = stack.mallocPointer(1);
        MSDFGenVector2 v = MSDFGenVector2.malloc(stack);
        float[] pts = o.points();
        int pi = 0;
        long contour = 0;
        double sx = 0;
        double sy = 0;
        double cx = 0;
        double cy = 0;
        for (int i = 0; i < o.verbCount(); i++) {
            switch (o.verbs()[i]) {
                case GlyphOutline.MOVE -> {
                    close(contour, cx, cy, sx, sy, p, v);
                    check(msdf_shape_add_contour(shape, p), "contour");
                    contour = p.get(0);
                    sx = pts[pi];
                    sy = pts[pi + 1];
                    cx = sx;
                    cy = sy;
                    pi += 2;
                }
                case GlyphOutline.LINE -> {
                    if (pts[pi] != cx || pts[pi + 1] != cy) {
                        edge(contour, MSDF_SEGMENT_TYPE_LINEAR, p, v, cx, cy, pts[pi], pts[pi + 1]);
                        cx = pts[pi];
                        cy = pts[pi + 1];
                    }
                    pi += 2;
                }
                case GlyphOutline.QUAD -> {
                    edge(contour, MSDF_SEGMENT_TYPE_QUADRATIC, p, v, cx, cy, pts[pi], pts[pi + 1], pts[pi + 2],
                        pts[pi + 3]);
                    cx = pts[pi + 2];
                    cy = pts[pi + 3];
                    pi += 4;
                }
                default -> {
                    cubic(contour, p, v, cx, cy, pts[pi], pts[pi + 1], pts[pi + 2], pts[pi + 3], pts[pi + 4],
                        pts[pi + 5], 0);
                    cx = pts[pi + 4];
                    cy = pts[pi + 5];
                    pi += 6;
                }
            }
        }
        close(contour, cx, cy, sx, sy, p, v);
    }

    private static void close(long contour, double cx, double cy, double sx, double sy, PointerBuffer p,
        MSDFGenVector2 v) {
        if (contour != 0 && (cx != sx || cy != sy)) {
            edge(contour, MSDF_SEGMENT_TYPE_LINEAR, p, v, cx, cy, sx, sy);
        }
    }

    /**
     * A cubic as quadratic edges: the C API of LWJGL's msdfgen refuses cubic segments (invalid type), so cubics (CFF
     * fonts) are split in halves until a quadratic is within {@link #CUBIC_TOLERANCE} ems of the cubic (the bound
     * {@code |p3 - 3 p2 + 3 p1 - p0| * sqrt(3) / 36}). The pieces meet smoothly, so edge colouring sees no new
     * corners.
     */
    private static void cubic(long contour, PointerBuffer p, MSDFGenVector2 v, double x0, double y0, double x1,
        double y1, double x2, double y2, double x3, double y3, int depth) {
        double dx = x3 - 3 * x2 + 3 * x1 - x0;
        double dy = y3 - 3 * y2 + 3 * y1 - y0;
        if (depth >= 8 || Math.sqrt(dx * dx + dy * dy) * 0.0481125224324688 <= CUBIC_TOLERANCE) {
            double qx = (3 * (x1 + x2) - x0 - x3) * 0.25;
            double qy = (3 * (y1 + y2) - y0 - y3) * 0.25;
            edge(contour, MSDF_SEGMENT_TYPE_QUADRATIC, p, v, x0, y0, qx, qy, x3, y3);
            return;
        }
        double ax = (x0 + x1) * 0.5;
        double ay = (y0 + y1) * 0.5;
        double bx = (x1 + x2) * 0.5;
        double by = (y1 + y2) * 0.5;
        double cx = (x2 + x3) * 0.5;
        double cy = (y2 + y3) * 0.5;
        double abx = (ax + bx) * 0.5;
        double aby = (ay + by) * 0.5;
        double bcx = (bx + cx) * 0.5;
        double bcy = (by + cy) * 0.5;
        double mx = (abx + bcx) * 0.5;
        double my = (aby + bcy) * 0.5;
        cubic(contour, p, v, x0, y0, ax, ay, abx, aby, mx, my, depth + 1);
        cubic(contour, p, v, mx, my, bcx, bcy, cx, cy, x3, y3, depth + 1);
    }

    /** Adds an edge through {@code xy} (start, controls, end); the contour owns it from then on. */
    private static void edge(long contour, int type, PointerBuffer p, MSDFGenVector2 v, double... xy) {
        check(msdf_segment_alloc(type, p), "segment");
        long segment = p.get(0);
        for (int k = 0; k < xy.length / 2; k++) {
            v.set(xy[2 * k], xy[2 * k + 1]);
            check(msdf_segment_set_point(segment, k, v), "point");
        }
        check(msdf_contour_add_edge(contour, segment), "edge");
    }

    /**
     * msdfgen expects outer contours to wind so that the inside is positive; fonts of the other convention (CFF) and
     * some converters wind the other way. A point well outside the shape must measure negative; if it measures
     * positive, every contour is reversed (msdfgen's command line does the same).
     */
    private static void orient(long shape, GlyphOutline o, MemoryStack stack) {
        float[] bounds = new float[4];
        o.bounds(bounds);
        MSDFGenVector2 outside = MSDFGenVector2.malloc(stack)
            .set(bounds[0] - (bounds[2] - bounds[0]) - 1, bounds[1] - (bounds[3] - bounds[1]) - 1);
        DoubleBuffer distance = stack.mallocDouble(1);
        check(msdf_shape_one_shot_distance(shape, outside, distance), "orientation");
        if (distance.get(0) > 0) {
            PointerBuffer p = stack.mallocPointer(1);
            check(msdf_shape_get_contour_count(shape, p), "contours");
            long count = p.get(0);
            for (long i = 0; i < count; i++) {
                check(msdf_shape_get_contour(shape, i, p), "contour");
                check(msdf_contour_reverse(p.get(0)), "reverse");
            }
        }
    }

    private static byte[] render(long shape, MsdfGeometry g, MemoryStack stack) {
        int w = g.width();
        int h = g.height();
        MSDFGenBitmap bitmap = MSDFGenBitmap.malloc(stack);
        check(msdf_bitmap_alloc(MSDF_BITMAP_TYPE_MTSDF, w, h, bitmap), "bitmap");
        try {
            MSDFGenTransform transform = MSDFGenTransform.calloc(stack);
            transform.scale().set(g.scale(), g.scale());
            transform.translation().set(g.translateX(), g.translateY());
            transform.distance_mapping().set(-g.range() * 0.5, g.range() * 0.5);
            MSDFGenMultichannelConfig config = MSDFGenMultichannelConfig.calloc(stack)
                .overlap_support(1)
                .mode(MSDF_ERROR_CORRECTION_MODE_EDGE_PRIORITY)
                .distance_check_mode(MSDF_DISTANCE_CHECK_MODE_AT_EDGE)
                .min_deviation_ratio(MIN_DEVIATION_RATIO)
                .min_improve_ratio(MIN_IMPROVE_RATIO);
            check(msdf_generate_mtsdf_with_config(bitmap, shape, transform, config), "generate");
            PointerBuffer pixels = stack.mallocPointer(1);
            check(msdf_bitmap_get_pixels(bitmap, pixels), "pixels");
            long floats = pixels.get(0);
            byte[] out = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                long row = floats + 4L * 4 * w * y; // msdfgen's row 0 is the bottom
                int o = (h - 1 - y) * w * 4;
                for (int i = 0; i < w * 4; i++) {
                    out[o + i] = quantize(MemoryUtil.memGetFloat(row + 4L * i));
                }
            }
            return out;
        } finally {
            msdf_bitmap_free(bitmap);
        }
    }

    static byte quantize(float v) {
        float c = v < 0 ? 0 : (v > 1 ? 1 : v);
        return (byte) Math.round(c * 255);
    }

    private static void check(int result, String what) {
        if (result != MSDF_SUCCESS) {
            throw new IllegalStateException("msdfgen failed: " + what + " (" + result + ")");
        }
    }
}
