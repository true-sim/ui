package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * A glyph's outline as path commands: contours of lines, quadratic and cubic Bézier curves, in ems, y up. Both
 * backends write it (FreeType's outline, the JDK's path iterator) and every {@link MsdfGenerator} reads it, so the
 * build-time and run-time atlases start from the same numbers. Reused: {@link #reset()} keeps the arrays.
 */
public final class GlyphOutline {

    /** Starts a contour at one point. */
    public static final byte MOVE = 0;
    /** A line to one point. */
    public static final byte LINE = 1;
    /** A quadratic curve: a control point and an end point. */
    public static final byte QUAD = 2;
    /** A cubic curve: two control points and an end point. */
    public static final byte CUBIC = 3;

    private byte[] verbs = new byte[64];
    private float[] points = new float[256];
    private int verbCount;
    private int pointCount; // floats in use

    /** Empties it, keeping its arrays. */
    public void reset() {
        verbCount = 0;
        pointCount = 0;
    }

    /** Starts a contour. */
    public void moveTo(float x, float y) {
        verb(MOVE);
        point(x, y);
    }

    /** A line from the current point. */
    public void lineTo(float x, float y) {
        verb(LINE);
        point(x, y);
    }

    /** A quadratic Bézier from the current point. */
    public void quadTo(float cx, float cy, float x, float y) {
        verb(QUAD);
        point(cx, cy);
        point(x, y);
    }

    /** A cubic Bézier from the current point. */
    public void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
        verb(CUBIC);
        point(c1x, c1y);
        point(c2x, c2y);
        point(x, y);
    }

    /** Commands in use. */
    public int verbCount() {
        return verbCount;
    }

    /** The commands: {@link #verbCount()} of {@link #MOVE}, {@link #LINE}, {@link #QUAD}, {@link #CUBIC}. */
    public byte[] verbs() {
        return verbs;
    }

    /** The points, x and y interleaved, in command order: one per move or line, two per quad, three per cubic. */
    public float[] points() {
        return points;
    }

    /** Floats of {@link #points()} in use. */
    public int pointFloats() {
        return pointCount;
    }

    /** Whether it has nothing to fill: no command but contour starts. */
    public boolean isEmpty() {
        for (int i = 0; i < verbCount; i++) {
            if (verbs[i] != MOVE) {
                return false;
            }
        }
        return true;
    }

    /**
     * The bounds of the points (control points included, so it may be a little larger than the ink) into
     * {@code out}: left, bottom, right, top. Zeros if empty.
     */
    public void bounds(float[] out) {
        if (pointCount == 0) {
            Arrays.fill(out, 0, 4, 0);
            return;
        }
        float l = Float.MAX_VALUE;
        float b = Float.MAX_VALUE;
        float r = -Float.MAX_VALUE;
        float t = -Float.MAX_VALUE;
        for (int i = 0; i < pointCount; i += 2) {
            l = Math.min(l, points[i]);
            r = Math.max(r, points[i]);
            b = Math.min(b, points[i + 1]);
            t = Math.max(t, points[i + 1]);
        }
        out[0] = l;
        out[1] = b;
        out[2] = r;
        out[3] = t;
    }

    /** A copy, for handing to another thread. */
    @AllowAllocation("one copy per glyph handed to a generator job")
    public GlyphOutline copy() {
        GlyphOutline c = new GlyphOutline();
        c.verbs = Arrays.copyOf(verbs, Math.max(1, verbCount));
        c.points = Arrays.copyOf(points, Math.max(2, pointCount));
        c.verbCount = verbCount;
        c.pointCount = pointCount;
        return c;
    }

    private void verb(byte v) {
        if (verbCount == verbs.length) {
            verbs = grow(verbs);
        }
        verbs[verbCount++] = v;
    }

    private void point(float x, float y) {
        if (pointCount + 2 > points.length) {
            points = grow(points);
        }
        points[pointCount++] = x;
        points[pointCount++] = y;
    }

    @AllowAllocation("growing, rarely")
    private static byte[] grow(byte[] a) {
        return Arrays.copyOf(a, a.length * 2);
    }

    @AllowAllocation("growing, rarely")
    private static float[] grow(float[] a) {
        return Arrays.copyOf(a, a.length * 2);
    }
}
