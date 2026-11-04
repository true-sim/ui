package com.truesim.ui.profiler;

import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;

/**
 * The coverage of a primitive over vertices ({@link Primitive#PATH}, {@link Primitive#COLUMNS}) of a baked list by the
 * rules of the engine's shader (ui/draw.slang), for the CPU previews and tests: a path is the union of its segments
 * (each with its caps and the join at its end), a filled envelope its columns' spans; a stroked envelope is pieces
 * drawn as lines ({@link #pieces}).
 */
final class VertexCoverage {

    private final int w;
    private final int h;
    private final float[] cover;

    VertexCoverage(int width, int height) {
        w = width;
        h = height;
        cover = new float[w * h];
    }

    /** The coverage of pixel {@code x, y}. */
    float at(int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? 0 : cover[y * w + x];
    }

    VertexCoverage of(DrawList list, int i) {
        switch (Primitive.kind(list.header(i))) {
            case Primitive.PATH -> path(list, i);
            case Primitive.COLUMNS -> columns(list, i);
            default -> throw new IllegalArgumentException("not a primitive over vertices");
        }
        return this;
    }

    private static float sat(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private void max(int x, int y, float c) {
        if (x >= 0 && y >= 0 && x < w && y < h && c > cover[y * w + x]) {
            cover[y * w + x] = c;
        }
    }

    // ---------------------------------------------------------------- paths

    /** Whether segment {@code j} (vertices j, j + 1 of the path's {@code count}) has two finite, distinct ends. */
    private static boolean segment(DrawList list, int first, int count, int j) {
        if (j < 0 || j + 1 >= count) {
            return false;
        }
        float ax = list.vertexAt(first + j, 0);
        float ay = list.vertexAt(first + j, 1);
        float bx = list.vertexAt(first + j + 1, 0);
        float by = list.vertexAt(first + j + 1, 1);
        return finite(ax) && finite(ay) && finite(bx) && finite(by) && (ax != bx || ay != by);
    }

    private void path(DrawList list, int i) {
        int first = list.intAt(i, Primitive.FIRST_VERTEX);
        int count = list.intAt(i, Primitive.VERTEX_COUNT);
        float width = list.floatAt(i, Primitive.WIDTH);
        float hw = Math.max(width, 1) * 0.5f;
        float reach = hw * Math.max(2, list.floatAt(i, Primitive.MITER_LIMIT)) + 2;
        for (int j = 0; j + 1 < count; j++) {
            if (!segment(list, first, count, j)) {
                continue;
            }
            float ax = list.vertexAt(first + j, 0);
            float ay = list.vertexAt(first + j, 1);
            float bx = list.vertexAt(first + j + 1, 0);
            float by = list.vertexAt(first + j + 1, 1);
            int x0 = (int) Math.floor(Math.min(ax, bx) - reach);
            int x1 = (int) Math.ceil(Math.max(ax, bx) + reach);
            int y0 = (int) Math.floor(Math.min(ay, by) - reach);
            int y1 = (int) Math.ceil(Math.max(ay, by) + reach);
            for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
                for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
                    max(x, y, segmentCover(list, i, first, count, j, x + 0.5f, y + 0.5f));
                }
            }
        }
    }

    /** Coverage of pixel centre {@code px, py} by segment {@code j}: its body, its caps and the join at its end. */
    static float segmentCover(DrawList list, int i, int first, int count, int j, float px, float py) {
        float width = list.floatAt(i, Primitive.WIDTH);
        float fade = Math.min(1, width);
        float hw = Math.max(width, 1) * 0.5f;
        int stroke = list.intAt(i, Primitive.STROKE);
        int join = stroke & 3;
        int cap = stroke & 12;
        float ax = list.vertexAt(first + j, 0);
        float ay = list.vertexAt(first + j, 1);
        float bx = list.vertexAt(first + j + 1, 0);
        float by = list.vertexAt(first + j + 1, 1);
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        dx /= len;
        dy /= len;
        float qx = px - ax;
        float qy = py - ay;
        float t = qx * dx + qy * dy;
        float u = -qx * dy + qy * dx;
        boolean starts = !segment(list, first, count, j - 1); // a cap at A, else a join (the segment before owns it)
        boolean ends = !segment(list, first, count, j + 1);
        float sA = list.vertexAt(first + j, 2);
        float sB = list.vertexAt(first + j + 1, 2);
        // the body: across by the distance to the axis, along with butt (or square) ends
        float e0 = starts && cap == Primitive.CAP_SQUARE ? hw : 0;
        float e1 = ends && cap == Primitive.CAP_SQUARE ? hw : 0;
        float from = sat(t + e0 + 0.5f);
        float to = sat(len + e1 - t + 0.5f);
        // where segments meet, their ends are not edges: whole up to the end, past it only on the bend's outer side
        if (!starts) {
            float bx0 = list.vertexAt(first + j - 1, 0);
            float by0 = list.vertexAt(first + j - 1, 1);
            from = t >= 0 ? 1 : qx * (ax - bx0) + qy * (ay - by0) > 0 ? from : 0;
        }
        if (!ends) {
            float cx = list.vertexAt(first + j + 2, 0);
            float cy = list.vertexAt(first + j + 2, 1);
            to = t <= len ? 1 : (px - bx) * (cx - bx) + (py - by) * (cy - by) < 0 ? to : 0;
        }
        float body = sat(hw + 0.5f - Math.abs(u)) * Math.min(from, to);
        if (starts && cap == Primitive.CAP_ROUND && t < 0) {
            body = sat(hw + 0.5f - (float) Math.sqrt(qx * qx + qy * qy));
        }
        if (ends && cap == Primitive.CAP_ROUND && t > len) {
            float rx = px - bx;
            float ry = py - by;
            body = sat(hw + 0.5f - (float) Math.sqrt(rx * rx + ry * ry));
        }
        float c = body * dash(list, i, sA + Math.max(0, Math.min(len, t)));
        if (!ends && join != Primitive.JOIN_NONE) {
            c = Math.max(c, wedge(list, i, first, j, bx, by, dx, dy, px, py, hw, join) * dash(list, i, sB));
        }
        return c * fade;
    }

    /** The join at the end {@code b} of segment {@code j} (direction {@code d}), on its outer side. */
    private static float wedge(DrawList list, int i, int first, int j, float bx, float by, float dx, float dy,
        float px, float py, float hw, int join) {
        float cx = list.vertexAt(first + j + 2, 0);
        float cy = list.vertexAt(first + j + 2, 1);
        float ex = cx - bx;
        float ey = cy - by;
        float len = (float) Math.sqrt(ex * ex + ey * ey);
        ex /= len;
        ey /= len;
        float turn = dx * ey - dy * ex;
        float rx = px - bx;
        float ry = py - by;
        if (Math.abs(turn) < 1e-6f || rx * dx + ry * dy < 0 || rx * ex + ry * ey > 0) {
            return 0; // straight on, or outside the wedge between the two segments' ends
        }
        float sign = turn > 0 ? -1 : 1; // the outer side: away from the turn
        float o1x = -dy * sign;
        float o1y = dx * sign;
        float o2x = -ey * sign;
        float o2y = ex * sign;
        if (join == Primitive.JOIN_ROUND) {
            return sat(hw + 0.5f - (float) Math.sqrt(rx * rx + ry * ry));
        }
        float c = Math.min(sat(hw + 0.5f - (rx * o1x + ry * o1y)), sat(hw + 0.5f - (rx * o2x + ry * o2y)));
        float cosHalf = (float) Math.sqrt(Math.max(0, (1 + dx * ex + dy * ey) * 0.5f));
        float limit = list.floatAt(i, Primitive.MITER_LIMIT);
        if (join == Primitive.JOIN_BEVEL || cosHalf * limit < 1) { // a miter longer than the limit: beveled
            float mx = o1x + o2x;
            float my = o1y + o2y;
            float ml = (float) Math.sqrt(mx * mx + my * my);
            mx /= ml;
            my /= ml;
            c = Math.min(c, sat(hw * (mx * o1x + my * o1y) + 0.5f - (rx * mx + ry * my)));
        }
        return c;
    }

    /** How much of the dash pattern covers the point {@code s} pixels along the path (1 when solid). */
    static float dash(DrawList list, int i, float s) {
        int a = list.intAt(i, Primitive.DASHES);
        int b = list.intAt(i, Primitive.DASHES + 1);
        float d1 = Float.float16ToFloat((short) a);
        float g1 = Float.float16ToFloat((short) (a >>> 16));
        float d2 = Float.float16ToFloat((short) b);
        float g2 = Float.float16ToFloat((short) (b >>> 16));
        float total = d1 + g1 + d2 + g2;
        if (!(total > 0) || g1 + g2 <= 0) {
            return 1;
        }
        float x = s + list.floatAt(i, Primitive.DASH_PHASE);
        x -= (float) Math.floor(x / total) * total;
        float best = -Float.MAX_VALUE;
        for (int k = -1; k <= 1; k++) {
            float o = k * total;
            best = Math.max(best, Math.min(x - o, o + d1 - x));
            best = Math.max(best, Math.min(x - (o + d1 + g1), o + d1 + g1 + d2 - x));
        }
        return sat(best + 0.5f);
    }

    // ---------------------------------------------------------------- columns

    private void columns(DrawList list, int i) {
        int first = list.intAt(i, Primitive.FIRST_VERTEX);
        int count = list.intAt(i, Primitive.VERTEX_COUNT);
        float x0 = list.floatAt(i, Primitive.COLUMN_X);
        float step = list.floatAt(i, Primitive.COLUMN_STEP);
        for (int k = 0; k < count; k++) {
            float cx = x0 + k * step;
            int xa = (int) Math.floor(cx - Math.abs(step) * 0.5f);
            int xb = (int) Math.ceil(cx + Math.abs(step) * 0.5f);
            for (int x = Math.max(0, xa); x < Math.min(w, xb); x++) {
                float px = x + 0.5f;
                if (px < cx - Math.abs(step) * 0.5f || px >= cx + Math.abs(step) * 0.5f) {
                    continue; // a column's pixels: half-open, so neighbours never share one
                }
                for (int y = 0; y < h; y++) {
                    max(x, y, columnCover(list, i, first, count, k, px, y + 0.5f));
                }
            }
        }
    }

    /** A stroked piece of columns: a line from a to b, {@code width} wide. */
    interface Piece {
        void line(float xa, float ya, float xb, float yb, float width);
    }

    /**
     * The pieces of stroked columns {@code i}, as the shader draws them: each column's vertical stroke over its span
     * grown by half the width (when it spans half a pixel), then from the column before a connector (its last sample to
     * this one's first) where their spans do not touch, or stairs (level, then up or down).
     */
    static void pieces(DrawList list, int i, Piece out) {
        int first = list.intAt(i, Primitive.FIRST_VERTEX);
        int count = list.intAt(i, Primitive.VERTEX_COUNT);
        float x0 = list.floatAt(i, Primitive.COLUMN_X);
        float step = list.floatAt(i, Primitive.COLUMN_STEP);
        float width = list.floatAt(i, Primitive.WIDTH);
        boolean steps = (list.intAt(i, Primitive.STROKE) & Primitive.COLUMNS_STEPS) != 0;
        for (int k = 0; k < count; k++) {
            float cx = x0 + k * step;
            float firstY = list.vertexAt(first + k, 0);
            float lo = list.vertexAt(first + k, 2);
            float hi = list.vertexAt(first + k, 3);
            if (!finite(firstY)) {
                continue;
            }
            boolean tall = hi - lo >= 0.5f;
            if (tall) {
                out.line(cx, lo - width * 0.5f, cx, hi + width * 0.5f, width);
            }
            if (k == 0 || !finite(list.vertexAt(first + k - 1, 0))) {
                continue;
            }
            float pLast = list.vertexAt(first + k - 1, 1);
            float pLo = list.vertexAt(first + k - 1, 2);
            float pHi = list.vertexAt(first + k - 1, 3);
            boolean touching = tall && pHi - pLo >= 0.5f && pLo <= hi + width && lo <= pHi + width
                && Math.abs(step) <= 1.5f;
            if (touching) {
                continue;
            }
            if (steps) {
                out.line(cx - step, pLast, cx, pLast, width);
                out.line(cx, pLast, cx, firstY, width);
            } else {
                out.line(cx - step, pLast, cx, firstY, width);
            }
        }
    }

    /** Coverage of {@code px, py} by filled column {@code k}: from the baseline to its farthest sample. */
    static float columnCover(DrawList list, int i, int first, int count, int k, float px, float py) {
        float firstY = list.vertexAt(first + k, 0);
        if (!finite(firstY)) {
            return 0;
        }
        float base = list.floatAt(i, Primitive.BASELINE);
        float top = Math.min(base, list.vertexAt(first + k, 2));
        float bottom = Math.max(base, list.vertexAt(first + k, 3));
        return sat(py - top + 0.5f) * sat(bottom - py + 0.5f);
    }
}
