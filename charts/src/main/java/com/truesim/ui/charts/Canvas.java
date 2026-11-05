package com.truesim.ui.charts;

import com.truesim.ui.controls.charts.ChartStyles;
import com.truesim.ui.controls.charts.LineJoin;
import com.truesim.ui.controls.charts.Marker;
import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.text.TextBlock;
import java.util.Arrays;
import java.util.List;

/**
 * What series draw with: the draw list's primitives in pixels of the chart's content box (moved to where the chart's
 * element records), faded by the element's opacity, plus what the primitives lack: polylines recorded as paths the
 * GPU strokes ({@link Primitive#PATH}: joins, dashes carried along the polyline, one primitive and a vertex per point),
 * min-max envelopes as pixel columns ({@link Primitive#COLUMNS}: stroked or filled, a vertex per column), segments
 * clipped to the plot (so zoomed-in data never makes huge or invisible primitives), markers of every {@link Marker}
 * shape, triangles and quads for areas, and pie wedges. Allocates nothing once its buffers and vertex blocks have
 * grown.
 *
 * <p><b>Vertices.</b> Each painter owns a block of the draw list's vertex table ({@link Vertices}), written from its
 * start each time the painter records: unchanged points upload nothing. A painter calls {@link #end()} when it is done,
 * which records the polyline still open.
 */
public final class Canvas {

    /**
     * A painter's block of its draw list's vertex table: taken when first needed, again (larger) when a recording
     * needs more or the list was rebuilt.
     */
    static final class Vertices {
        private DrawList list;
        private long generation;
        private int first;
        private int capacity;
        private int used;

        /** How many vertices the block holds. */
        int capacity() {
            return capacity;
        }
    }

    private DrawList list;
    private float ox;
    private float oy;
    private float opacity = 1;
    private float scale = 1;
    // where drawing shows: the plot, in content-box pixels
    private float x0;
    private float y0;
    private float x1;
    private float y1;

    // the stroke
    private float width = 1;
    private long color;
    private final float[] dash = new float[16];
    private int dashCount;
    private float dashTotal;
    private int dashIndex;
    private float dashLeft;
    private int join = Primitive.JOIN_NONE;
    private float miterLimit = 4;
    private boolean hasPoint;
    private float lastX;
    private float lastY;
    private int primitives;
    // the open polyline: x, y and its length from the polyline's start, per point (content-box pixels)
    private float[] points = new float[3 * 256];
    private int pointCount;
    private double pathLength; // along the polyline since its moveTo: dashes carry on across clipped-away parts
    private double subPathStart;
    private Vertices block = new Vertices();
    // the rotating entries of the list's transform table this canvas turned text by, per angle (text(..., radians))
    private DrawList rotationList;
    private long rotationGeneration;
    private float[] rotationAngles = new float[4];
    private int[] rotationEntries = new int[4];
    private int rotationCount;

    /**
     * Starts drawing into {@code target}: content-box pixels plus {@code ox, oy}; culled to {@code x0..y1}; vertices
     * into {@code vertices}, the painter's block.
     */
    void begin(DrawList target, float offsetX, float offsetY, float cullX0, float cullY0, float cullX1, float cullY1,
        float fade, float uiScale, Vertices vertices) {
        list = target;
        block = vertices;
        block.used = 0;
        pointCount = 0;
        hasPoint = false;
        ox = offsetX;
        oy = offsetY;
        x0 = cullX0;
        y0 = cullY0;
        x1 = cullX1;
        y1 = cullY1;
        opacity = fade;
        scale = uiScale;
        primitives = 0;
    }

    /** Sets the cull rectangle (content-box pixels). */
    void cull(float cullX0, float cullY0, float cullX1, float cullY1) {
        x0 = cullX0;
        y0 = cullY0;
        x1 = cullX1;
        y1 = cullY1;
    }

    /** The UI's scale: style sizes (panel units) times this are pixels. */
    public float scale() {
        return scale;
    }

    /** The element's opacity colours are faded by. */
    public float opacity() {
        return opacity;
    }

    /** Primitives recorded since {@link #begin}. */
    public int primitives() {
        return primitives;
    }

    /** The cull rectangle's left edge (the plot's, in content-box pixels). */
    public float left() {
        return x0;
    }

    /** The cull rectangle's top edge. */
    public float top() {
        return y0;
    }

    /** The cull rectangle's right edge. */
    public float right() {
        return x1;
    }

    /** The cull rectangle's bottom edge. */
    public float bottom() {
        return y1;
    }

    /** Fades what follows by {@code value} instead (a hidden legend entry's look). */
    void opacity(float value) {
        opacity = value;
    }

    /** {@code c} faded by the element's opacity. */
    public long fade(long c) {
        return Rgba.fade(c, opacity);
    }

    // ---------------------------------------------------------------- strokes

    /** Records what is still open (a polyline): call when the painter is done. */
    void end() {
        flushPath();
    }

    /**
     * Strokes from now on with {@code style}'s {@code line-width}, {@code line-dash}, {@code line-join} and
     * {@code line-miter-limit}.
     */
    public void stroke(ResolvedStyle style, long strokeColor) {
        LineJoin j = style.get(ChartStyles.LINE_JOIN);
        stroke(style.getFloat(ChartStyles.LINE_WIDTH) * scale, strokeColor, style.get(ChartStyles.LINE_DASH),
            j == LineJoin.ROUND);
        join = switch (j) {
            case ROUND -> Primitive.JOIN_ROUND;
            case MITER -> Primitive.JOIN_MITER;
            case BEVEL -> Primitive.JOIN_BEVEL;
            default -> Primitive.JOIN_NONE;
        };
        miterLimit = style.getFloat(ChartStyles.LINE_MITER_LIMIT);
    }

    /** Strokes from now on {@code lineWidth} pixels wide in {@code strokeColor}, dashed by {@code pattern}. */
    public void stroke(float lineWidth, long strokeColor, List<Float> pattern, boolean roundJoins) {
        flushPath();
        join = roundJoins ? Primitive.JOIN_ROUND : Primitive.JOIN_NONE;
        width = lineWidth;
        color = Rgba.fade(strokeColor, opacity);
        dashCount = 0;
        dashTotal = 0;
        int n = Math.min(dash.length, pattern.size()) & ~1; // dashes and gaps in pairs
        for (int i = 0; i < n; i++) {
            float d = Math.max(0, pattern.get(i)) * scale;
            dash[i] = d;
            dashTotal += d;
        }
        if (dashTotal > 0.5f) {
            dashCount = n;
        }
        hasPoint = false;
    }

    /** Whether the stroke draws anything. */
    public boolean stroking() {
        return width > 0 && Rgba.a(color) > 0;
    }

    /** The stroke's width in pixels. */
    public float strokeWidth() {
        return width;
    }

    /** Starts a new polyline at {@code x, y} (the dash pattern starts over). */
    public void moveTo(float x, float y) {
        flushPath();
        lastX = x;
        lastY = y;
        hasPoint = x == x && y == y;
        pathLength = 0;
        dashIndex = 0;
        dashLeft = dashCount > 0 ? dash[0] : Float.POSITIVE_INFINITY;
    }

    /**
     * Continues the polyline to {@code x, y} (a NaN point breaks it). The polyline is recorded as paths: one for each
     * part within the plot (grown by the stroke), cut where it leaves.
     */
    public void lineTo(float x, float y) {
        if (x != x || y != y) {
            flushPath();
            hasPoint = false;
            return;
        }
        if (!hasPoint) {
            moveTo(x, y);
            return;
        }
        float dx = x - lastX;
        float dy = y - lastY;
        double length = Math.sqrt((double) dx * dx + (double) dy * dy);
        clip(lastX, lastY, x, y, margin());
        float t0 = tLow;
        float t1 = tHigh;
        if (t1 >= t0) {
            if (pointCount == 0 || t0 > 0) { // (re-)entering the plot: a path of its own, the dashes carried on
                flushPath();
                point(lastX + dx * t0, lastY + dy * t0, pathLength + t0 * length);
            }
            point(lastX + dx * t1, lastY + dy * t1, pathLength + t1 * length);
            if (t1 < 1) {
                flushPath(); // leaving it
            }
        } else {
            flushPath();
        }
        pathLength += length;
        lastX = x;
        lastY = y;
    }

    /** How far beyond the plot a path's segments are kept: its width and miters. */
    private float margin() {
        return Math.max(width, 1) * (join == Primitive.JOIN_MITER ? Math.max(1, miterLimit) : 1) + 2;
    }

    /** Appends a point to the open polyline (not a repeat of the last). */
    private void point(float x, float y, double length) {
        int o = pointCount * 3;
        if (pointCount > 0 && points[o - 3] == x && points[o - 2] == y) {
            return;
        }
        if (o + 3 > points.length) {
            points = grow(points);
        }
        points[o] = x;
        points[o + 1] = y;
        if (pointCount == 0) {
            subPathStart = length;
        }
        points[o + 2] = (float) (length - subPathStart);
        pointCount++;
    }

    @AllowAllocation("a longer polyline than ever before")
    private static float[] grow(float[] a) {
        return Arrays.copyOf(a, a.length * 2);
    }

    /** Records the open polyline as a path, if it has a segment. */
    private void flushPath() {
        int n = pointCount;
        pointCount = 0;
        if (n < 2 || list == null) {
            return;
        }
        int at = reserve(n);
        float phase = 0;
        float d1 = 0;
        float g1 = 0;
        float d2 = 0;
        float g2 = 0;
        if (dashCount > 0) {
            phase = (float) (subPathStart % dashTotal);
            d1 = dash[0];
            g1 = dash[1];
            if (dashCount > 2) { // the GPU's pattern holds two dashes and their gaps
                d2 = dash[2];
                g2 = dash[3];
            }
        }
        for (int k = 0; k < n; k++) {
            list.pathVertex(at + k, points[k * 3] + ox, points[k * 3 + 1] + oy, points[k * 3 + 2]);
        }
        list.path(at, n, width, color, join, miterLimit, d1, g1, d2, g2, phase);
        primitives++;
    }

    /** {@code n} vertices of the painter's block: the first one's index in the list's vertex table. */
    int reserve(int n) {
        Vertices v = block;
        if (v.list != list || v.generation != list.generation()) { // first use, or the list was rebuilt
            v.list = list;
            v.generation = list.generation();
            v.capacity = Math.max(256, Math.max(n, v.capacity));
            v.first = list.allocateVertices(v.capacity);
            v.used = 0;
        } else if (v.used + n > v.capacity) { // more than ever: a larger block from here on (this one stays drawn)
            v.capacity = Math.max(v.capacity * 2, v.used + n * 2);
            v.first = list.allocateVertices(v.capacity);
            v.used = 0;
        }
        int at = v.first + v.used;
        v.used += n;
        return at;
    }

    // ---------------------------------------------------------------- streams

    /**
     * A block of at least {@code capacity} vertices kept by a streamed series ({@code v}, its own, across recordings):
     * its first index, or -1 when the block was (re)made just now (the list was rebuilt, or it is larger), so the
     * caller writes every vertex again.
     */
    int streamBlock(Vertices v, int capacity) {
        flushPath();
        if (v.list == list && v.generation == list.generation() && v.capacity >= capacity) {
            return v.first;
        }
        v.list = list;
        v.generation = list.generation();
        v.capacity = Math.max(256, Math.max(capacity, v.capacity * 2));
        v.first = list.allocateVertices(v.capacity);
        v.used = 0;
        return -1;
    }

    /** Writes vertex {@code index} of a streamed path at {@code x, y} (content-box pixels), {@code length} along it. */
    void streamVertex(int index, float x, float y, float length) {
        list.pathVertex(index, x + ox, y + oy, length);
    }

    /** Strokes {@code count} vertices from {@code first} as a path with the current (solid) stroke. */
    void streamPath(int first, int count) {
        if (count >= 2 && stroking() && dashCount == 0) {
            list.path(first, count, width, color, join, miterLimit, 0, 0, 0, 0, 0);
            primitives++;
        }
    }

    // ---------------------------------------------------------------- envelope columns

    /** {@code count} envelope columns' vertices (written by {@link #column}): their first index. */
    int columns(int count) {
        flushPath();
        return reserve(count);
    }

    /** Column {@code k} of those at {@code at}: its first, last, lowest and highest sample's y (NaN: none). */
    void column(int at, int k, float first, float last, float low, float high) {
        list.columnVertex(at + k, first + oy, last + oy, low + oy, high + oy);
    }

    /**
     * Strokes {@code count} columns at {@code at} with the current stroke, the first centred at {@code x}, the next
     * {@code step} pixels on each (1, or -1 on an inverted axis); {@code steps}: connectors as stairs.
     */
    void strokeColumns(int at, int count, float x, float step, boolean steps) {
        if (count > 0 && stroking()) {
            list.columns(at, count, x + ox, step, width, color, 0, steps);
            primitives++;
        }
    }

    /** Fills {@code count} columns at {@code at} from {@code base} to their farthest samples in {@code fill}. */
    void fillColumns(int at, int count, float x, float step, float base, long fill) {
        if (count > 0 && Rgba.a(fill) > 0) {
            list.columns(at, count, x + ox, step, 0, fill, base + oy, false);
            primitives++;
        }
    }

    /** One stroked segment (dashed with the current phase; the polyline's position is not changed). */
    public void line(float xa, float ya, float xb, float yb) {
        flushPath();
        if (dashCount == 0) {
            clipped(xa, ya, xb, yb);
            return;
        }
        dashIndex = 0;
        dashLeft = dash[0];
        segment(xa, ya, xb, yb);
    }

    private void segment(float xa, float ya, float xb, float yb) {
        if (dashCount == 0) {
            clipped(xa, ya, xb, yb);
            return;
        }
        float dx = xb - xa;
        float dy = yb - ya;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (!(length > 0)) {
            return;
        }
        if (length > 4 * Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) + dashTotal * 4) {
            // a segment far longer than the plot: dash only what can show
            clipDashed(xa, ya, xb, yb, length);
            return;
        }
        float at = 0;
        while (at < length) {
            float piece = Math.min(dashLeft, length - at);
            if ((dashIndex & 1) == 0 && piece > 0) {
                float t0 = at / length;
                float t1 = (at + piece) / length;
                clipped(xa + dx * t0, ya + dy * t0, xa + dx * t1, ya + dy * t1);
            }
            at += piece;
            dashLeft -= piece;
            if (dashLeft <= 0) {
                dashIndex = (dashIndex + 1) % dashCount;
                dashLeft = dash[dashIndex];
            }
        }
    }

    /** Dashes only the part of a long segment within the plot, keeping the phase as if all were drawn. */
    private void clipDashed(float xa, float ya, float xb, float yb, float length) {
        clip(xa, ya, xb, yb, width + 1);
        float t0 = tLow;
        float t1 = tHigh;
        if (!(t1 > t0)) {
            return;
        }
        float skip = t0 * length;
        float phase = (float) Math.IEEEremainder(skip, dashTotal);
        if (phase < 0) {
            phase += dashTotal;
        }
        // advance the pattern by the skipped length
        float rest = phase;
        while (rest > 0) {
            float piece = Math.min(dashLeft, rest);
            rest -= piece;
            dashLeft -= piece;
            if (dashLeft <= 0) {
                dashIndex = (dashIndex + 1) % dashCount;
                dashLeft = dash[dashIndex];
            }
        }
        float dx = xb - xa;
        float dy = yb - ya;
        float cxa = xa + dx * t0;
        float cya = ya + dy * t0;
        float cxb = xa + dx * t1;
        float cyb = ya + dy * t1;
        float inner = (t1 - t0) * length;
        float at = 0;
        while (at < inner) {
            float piece = Math.min(dashLeft, inner - at);
            if ((dashIndex & 1) == 0 && piece > 0) {
                float a = at / inner;
                float b = (at + piece) / inner;
                clipped(cxa + (cxb - cxa) * a, cya + (cyb - cya) * a, cxa + (cxb - cxa) * b,
                    cya + (cyb - cya) * b);
            }
            at += piece;
            dashLeft -= piece;
            if (dashLeft <= 0) {
                dashIndex = (dashIndex + 1) % dashCount;
                dashLeft = dash[dashIndex];
            }
        }
    }

    /** Records the segment clipped to the plot grown by the stroke (nothing if it misses it). */
    private void clipped(float xa, float ya, float xb, float yb) {
        clip(xa, ya, xb, yb, width + 1);
        float t0 = tLow;
        float t1 = tHigh;
        if (!(t1 >= t0)) {
            return;
        }
        float dx = xb - xa;
        float dy = yb - ya;
        if (t0 == 0 && t1 == 1) {
            emitLine(xa, ya, xb, yb);
        } else {
            emitLine(xa + dx * t0, ya + dy * t0, xa + dx * t1, ya + dy * t1);
        }
    }

    private float tLow;
    private float tHigh;

    /** Liang-Barsky against the cull rectangle grown by {@code m}: the kept part is {@code tLow..tHigh}. */
    private void clip(float xa, float ya, float xb, float yb, float m) {
        float lo = 0;
        float hi = 1;
        float dx = xb - xa;
        float dy = yb - ya;
        for (int edge = 0; edge < 4 && lo <= hi; edge++) { // left, right, top, bottom
            float p = edge == 0 ? -dx : edge == 1 ? dx : edge == 2 ? -dy : dy;
            float q = edge == 0 ? xa - (x0 - m) : edge == 1 ? x1 + m - xa : edge == 2 ? ya - (y0 - m) : y1 + m - ya;
            if (p == 0) {
                if (q < 0) {
                    lo = 1;
                    hi = 0;
                }
            } else {
                float r = q / p;
                if (p < 0) {
                    if (r > lo) {
                        lo = r;
                    }
                } else if (r < hi) {
                    hi = r;
                }
            }
        }
        tLow = lo;
        tHigh = hi;
    }

    private void emitLine(float xa, float ya, float xb, float yb) {
        list.line(xa + ox, ya + oy, xb + ox, yb + oy, width, color);
        primitives++;
    }

    private boolean inside(float x, float y, float margin) {
        return x >= x0 - margin && x <= x1 + margin && y >= y0 - margin && y <= y1 + margin;
    }

    // ---------------------------------------------------------------- fills and shapes

    /** A box {@code bx0..by1} (content-box pixels) in {@code fill} (already faded), corners rounded by {@code r}. */
    public void box(float bx0, float by0, float bx1, float by1, long fill, float r, float border, long borderColor) {
        flushPath();
        float ax = Math.min(bx0, bx1);
        float bx = Math.max(bx0, bx1);
        float ay = Math.min(by0, by1);
        float by = Math.max(by0, by1);
        if (bx < x0 - 2 || ax > x1 + 2 || by < y0 - 2 || ay > y1 + 2) {
            return; // outside the plot
        }
        // keep coordinates sane: a box far beyond the plot is cut to it (rounding only shows within)
        float m = r + border + 2;
        ax = Math.max(ax, x0 - m);
        bx = Math.min(bx, x1 + m);
        ay = Math.max(ay, y0 - m);
        by = Math.min(by, y1 + m);
        list.box(ax + ox, ay + oy, bx + ox, by + oy, fill, r, r, r, r, border, borderColor);
        primitives++;
    }

    /** A disc of radius {@code r} at {@code x, y} in {@code fill} (already faded). */
    public void disc(float x, float y, float r, long fill) {
        flushPath();
        list.box(x - r + ox, y - r + oy, x + r + ox, y + r + oy, fill, r, r, r, r, 0, 0);
        primitives++;
    }

    /** A triangle (content-box pixels) in {@code fill} (already faded), anti-aliased on {@code edges}. */
    public void triangle(float xa, float ya, float xb, float yb, float xc, float yc, long fill, int edges) {
        flushPath();
        float minX = Math.min(xa, Math.min(xb, xc));
        float maxX = Math.max(xa, Math.max(xb, xc));
        float minY = Math.min(ya, Math.min(yb, yc));
        float maxY = Math.max(ya, Math.max(yb, yc));
        if (maxX < x0 - 1 || minX > x1 + 1 || maxY < y0 - 1 || minY > y1 + 1 || !(maxX >= minX && maxY >= minY)) {
            return;
        }
        list.triangle(xa + ox, ya + oy, xb + ox, yb + oy, xc + ox, yc + oy, fill, edges);
        primitives++;
    }

    /**
     * A quad {@code a b c d} (in order round it) as two triangles split along {@code a c}, anti-aliased on the sides
     * {@code edges} names ({@code 1}: ab, {@code 2}: bc, {@code 4}: cd, {@code 8}: da).
     */
    public void quad(float xa, float ya, float xb, float yb, float xc, float yc, float xd, float yd, long fill,
        int edges) {
        triangle(xa, ya, xb, yb, xc, yc, fill, (edges & 1) | (edges & 2));
        triangle(xa, ya, xc, yc, xd, yd, fill, ((edges & 4) != 0 ? Primitive.EDGE_BC : 0)
            | ((edges & 8) != 0 ? Primitive.EDGE_CA : 0));
    }

    /**
     * A ring sector (a pie slice: inner radius 0) around {@code cx, cy} from angle {@code a0} to {@code a1} (radians,
     * clockwise from 12 o'clock on screen), in {@code fill}, with arc segments at most {@code arcPixels} long.
     */
    public void wedge(float cx, float cy, float r0, float r1, double a0, double a1, long fill, float arcPixels) {
        double sweep = a1 - a0;
        if (!(sweep > 0) || !(r1 > r0)) {
            return;
        }
        int segments = (int) Math.max(1, Math.min(1024, Math.ceil(sweep * r1 / Math.max(0.5f, arcPixels))));
        double step = sweep / segments;
        float px0 = cx + (float) (Maths.sin(a0) * r1);
        float py0 = cy - (float) (Maths.cos(a0) * r1);
        float qx0 = cx + (float) (Maths.sin(a0) * r0);
        float qy0 = cy - (float) (Maths.cos(a0) * r0);
        for (int i = 1; i <= segments; i++) {
            double a = a0 + step * i;
            float s = (float) Maths.sin(a);
            float c = (float) Maths.cos(a);
            float px1 = cx + s * r1;
            float py1 = cy - c * r1;
            int outer = Primitive.EDGE_AB;
            if (r0 <= 0) {
                int sides = (i == 1 ? Primitive.EDGE_CA : 0) | (i == segments ? Primitive.EDGE_BC : 0);
                triangle(px0, py0, px1, py1, cx, cy, fill, outer | sides);
            } else {
                float qx1 = cx + s * r0;
                float qy1 = cy - c * r0;
                int sides = (i == 1 ? 8 : 0) | (i == segments ? 2 : 0);
                quad(px0, py0, px1, py1, qx1, qy1, qx0, qy0, fill, 1 | 4 | sides);
                qx0 = qx1;
                qy0 = qy1;
            }
            px0 = px1;
            py0 = py1;
        }
    }

    /**
     * A marker of {@code shape} centred at {@code x, y}, {@code size} pixels across: filled with {@code fill} and
     * outlined {@code outline} pixels wide in {@code outlineColor} (both already faded); line shapes use the outline
     * colour (or the fill when there is no outline) and at least a pixel.
     */
    public void marker(Marker shape, float x, float y, float size, long fill, float outline, long outlineColor) {
        flushPath();
        if (shape == Marker.NONE || !(size > 0) || !inside(x, y, size)) {
            return;
        }
        float r = size * 0.5f;
        boolean filled = Rgba.a(fill) > 0;
        boolean outlined = outline > 0 && Rgba.a(outlineColor) > 0;
        switch (shape) {
            case CIRCLE -> {
                list.box(x - r + ox, y - r + oy, x + r + ox, y + r + oy, filled ? fill : Rgba.TRANSPARENT, r, r, r, r,
                    outlined ? outline : 0, outlineColor);
                primitives++;
            }
            case SQUARE -> {
                float h = r * 0.886f; // the same area as the disc
                list.box(x - h + ox, y - h + oy, x + h + ox, y + h + oy, filled ? fill : Rgba.TRANSPARENT, 0, 0, 0,
                    0, outlined ? outline : 0, outlineColor);
                primitives++;
            }
            case DIAMOND -> polygon(x, y, r, 4, 0, filled, fill, outlined, outline, outlineColor);
            case UP -> polygon(x, y, r, 3, 0, filled, fill, outlined, outline, outlineColor);
            case DOWN -> polygon(x, y, r, 3, Math.PI, filled, fill, outlined, outline, outlineColor);
            case LEFT -> polygon(x, y, r, 3, -Math.PI / 2, filled, fill, outlined, outline, outlineColor);
            case RIGHT -> polygon(x, y, r, 3, Math.PI / 2, filled, fill, outlined, outline, outlineColor);
            default -> {
                long c = outlined ? outlineColor : fill;
                float w = Math.max(1, outlined ? outline : Math.max(1, size / 6));
                int spokes = shape == Marker.ASTERISK ? 3 : 2;
                double turn = shape == Marker.CROSS ? Math.PI / 4 : shape == Marker.ASTERISK ? Math.PI / 2 : 0;
                for (int i = 0; i < spokes; i++) {
                    double a = turn + Math.PI * i / spokes;
                    float sx = (float) Maths.sin(a) * r;
                    float sy = (float) Maths.cos(a) * r;
                    list.line(x - sx + ox, y - sy + oy, x + sx + ox, y + sy + oy, w, c);
                    primitives++;
                }
            }
        }
    }

    // corner angles of the regular polygons markers use, from 12 o'clock clockwise
    private void polygon(float x, float y, float r, int corners, double rotation, boolean filled, long fill,
        boolean outlined, float outline, long outlineColor) {
        float scaleR = corners == 3 ? r * 1.2f : r; // triangles look small at the disc's radius
        float ax = x + (float) Maths.sin(rotation) * scaleR;
        float ay = y - (float) Maths.cos(rotation) * scaleR;
        if (filled) {
            float px = ax;
            float py = ay;
            for (int i = 1; i < corners - 1; i++) {
                double a1 = rotation + 2 * Math.PI * i / corners;
                double a2 = rotation + 2 * Math.PI * (i + 1) / corners;
                float bx = x + (float) Maths.sin(a1) * scaleR;
                float by = y - (float) Maths.cos(a1) * scaleR;
                float cx = x + (float) Maths.sin(a2) * scaleR;
                float cy = y - (float) Maths.cos(a2) * scaleR;
                int edges = Primitive.EDGE_BC | (i == 1 ? Primitive.EDGE_AB : 0)
                    | (i == corners - 2 ? Primitive.EDGE_CA : 0);
                list.triangle(px + ox, py + oy, bx + ox, by + oy, cx + ox, cy + oy, fill, edges);
                primitives++;
            }
        }
        if (outlined) {
            float px = ax;
            float py = ay;
            for (int i = 1; i <= corners; i++) {
                double a = rotation + 2 * Math.PI * i / corners;
                float bx = x + (float) Maths.sin(a) * scaleR;
                float by = y - (float) Maths.cos(a) * scaleR;
                list.line(px + ox, py + oy, bx + ox, by + oy, outline, outlineColor);
                primitives++;
                px = bx;
                py = by;
            }
        }
    }

    /** Text at {@code x, y} (the block's top left, content-box pixels) in {@code textColor} (already faded). */
    public void text(TextBlock text, float x, float y, long textColor) {
        flushPath();
        text.record(list, x + ox, y + oy, textColor, 0, 0, 0);
        primitives += text.glyphCount();
    }

    /**
     * Text turned by {@code radians} (clockwise on the screen) about its block's point {@code (pivotX, pivotY)} (shares
     * of its width and height from its top left), that point at {@code (x, y)} (content-box pixels). Drawn through a
     * rotating entry of the draw list's transform table ({@link DrawList#rotated}), one per angle, kept while the list
     * is not rebuilt: the glyphs keep their shapes and pixel ranges, the renderer turns them.
     */
    public void text(TextBlock text, float x, float y, long textColor, float radians, float pivotX, float pivotY) {
        float px = pivotX * text.width();
        float py = pivotY * text.height();
        if (radians == 0) {
            text(text, x - px, y - py, textColor);
            return;
        }
        flushPath();
        int entry = list.rotated(rotationEntry(radians), radians);
        rememberRotation(radians, entry);
        // the list turns about the origin of the recording space: the block is recorded where turning puts it right
        float c = (float) Maths.cos(radians);
        float s = (float) Maths.sin(radians);
        float ax = x + ox;
        float ay = y + oy;
        text.record(list, c * ax + s * ay - px, -s * ax + c * ay - py, textColor, 0, 0, 0);
        list.unrotated();
        primitives += text.glyphCount();
    }

    /** The entry this canvas turned {@code radians} by in its list's current generation (0: none yet). */
    private int rotationEntry(float radians) {
        if (rotationList != list || rotationGeneration != list.generation()) {
            rotationList = list;
            rotationGeneration = list.generation();
            rotationCount = 0;
        }
        for (int i = 0; i < rotationCount; i++) {
            if (rotationAngles[i] == radians) {
                return rotationEntries[i];
            }
        }
        return 0;
    }

    private void rememberRotation(float radians, int entry) {
        for (int i = 0; i < rotationCount; i++) {
            if (rotationAngles[i] == radians) {
                return;
            }
        }
        if (rotationCount == rotationAngles.length) {
            growRotations();
        }
        rotationAngles[rotationCount] = radians;
        rotationEntries[rotationCount++] = entry;
    }

    @AllowAllocation("more angles than a chart usually turns text by, once")
    private void growRotations() {
        rotationAngles = Arrays.copyOf(rotationAngles, rotationAngles.length * 2);
        rotationEntries = Arrays.copyOf(rotationEntries, rotationEntries.length * 2);
    }

    /** An image of texture {@code texture}'s region {@code u0..v1} over the box (content-box pixels). */
    public void image(float bx0, float by0, float bx1, float by1, float u0, float v0, float u1, float v1, int texture,
        long tint) {
        flushPath();
        list.image(bx0 + ox, by0 + oy, bx1 + ox, by1 + oy, u0, v0, u1, v1, texture, tint);
        primitives++;
    }

    /** The draw list (for what the helpers above do not cover). */
    DrawList list() {
        flushPath();
        return list;
    }

    /** The horizontal offset from content-box pixels to the list's. */
    float offsetX() {
        return ox;
    }

    /** The vertical offset from content-box pixels to the list's. */
    float offsetY() {
        return oy;
    }
}
