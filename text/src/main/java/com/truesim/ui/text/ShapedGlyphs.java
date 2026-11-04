package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * Glyphs a {@link TextShaper} produced: per glyph its id in the face, the cluster (index of the first character it
 * came from), its advance and its offset from the pen, all in pixels (y up), and the shaper's flags. Grows as needed
 * and is reused.
 */
public final class ShapedGlyphs {

    /**
     * Flag: the text may not be broken before this glyph's cluster and the two sides shaped apart (HarfBuzz's
     * {@code HB_GLYPH_FLAG_UNSAFE_TO_BREAK}).
     */
    public static final int UNSAFE_TO_BREAK = 1;

    /**
     * Flag: changing the text on one side of this glyph's cluster may change the shaping of the other side (HarfBuzz's
     * {@code HB_GLYPH_FLAG_UNSAFE_TO_CONCAT}).
     */
    public static final int UNSAFE_TO_CONCAT = 2;

    private int size;
    private int[] glyphs = new int[64];
    private int[] clusters = new int[64];
    private float[] advances = new float[64];
    private float[] offsetsX = new float[64];
    private float[] offsetsY = new float[64];
    private byte[] flags = new byte[64];

    /** Forgets the glyphs, keeping the arrays. */
    public void clear() {
        size = 0;
    }

    /** Appends a glyph without flags. */
    public void add(int glyph, int cluster, float advance, float offsetX, float offsetY) {
        add(glyph, cluster, advance, offsetX, offsetY, 0);
    }

    /** Appends a glyph with {@code glyphFlags} ({@link #UNSAFE_TO_BREAK}, {@link #UNSAFE_TO_CONCAT}). */
    public void add(int glyph, int cluster, float advance, float offsetX, float offsetY, int glyphFlags) {
        if (size == glyphs.length) {
            grow(size + 1);
        }
        glyphs[size] = glyph;
        clusters[size] = cluster;
        advances[size] = advance;
        offsetsX[size] = offsetX;
        offsetsY[size] = offsetY;
        flags[size] = (byte) glyphFlags;
        size++;
    }

    @AllowAllocation("growing, rarely")
    private void grow(int n) {
        int c = Math.max(n, glyphs.length * 2);
        glyphs = Arrays.copyOf(glyphs, c);
        clusters = Arrays.copyOf(clusters, c);
        advances = Arrays.copyOf(advances, c);
        offsetsX = Arrays.copyOf(offsetsX, c);
        offsetsY = Arrays.copyOf(offsetsY, c);
        flags = Arrays.copyOf(flags, c);
    }

    /** Glyphs held. */
    public int size() {
        return size;
    }

    /** Glyph {@code i}'s id in its face. */
    public int glyph(int i) {
        return glyphs[i];
    }

    /** The index of the first character glyph {@code i} came from. */
    public int cluster(int i) {
        return clusters[i];
    }

    /** How far glyph {@code i} moves the pen, pixels. */
    public float advance(int i) {
        return advances[i];
    }

    /** Glyph {@code i}'s offset right of the pen, pixels. */
    public float offsetX(int i) {
        return offsetsX[i];
    }

    /** Glyph {@code i}'s offset above the baseline, pixels. */
    public float offsetY(int i) {
        return offsetsY[i];
    }

    /** Glyph {@code i}'s flags ({@link #UNSAFE_TO_BREAK}, {@link #UNSAFE_TO_CONCAT}); 0 from shapers without them. */
    public int flags(int i) {
        return flags[i];
    }

    /**
     * Appends {@code src}'s glyphs {@code [from, to)}, their clusters moved by {@code delta} where they are at least
     * {@code atLeast}.
     */
    void append(ShapedGlyphs src, int from, int to, int atLeast, int delta) {
        int n = to - from;
        if (size + n > glyphs.length) {
            grow(size + n);
        }
        System.arraycopy(src.glyphs, from, glyphs, size, n);
        System.arraycopy(src.clusters, from, clusters, size, n);
        System.arraycopy(src.advances, from, advances, size, n);
        System.arraycopy(src.offsetsX, from, offsetsX, size, n);
        System.arraycopy(src.offsetsY, from, offsetsY, size, n);
        System.arraycopy(src.flags, from, flags, size, n);
        moveClusters(size, size + n, atLeast, delta);
        size += n;
    }

    /** Replaces glyphs {@code [from, to)} by all of {@code src}'s, moving the glyphs after them. */
    void replace(int from, int to, ShapedGlyphs src) {
        int n = src.size;
        int after = size - to;
        int newSize = from + n + after;
        if (newSize > glyphs.length) {
            grow(newSize);
        }
        System.arraycopy(glyphs, to, glyphs, from + n, after);
        System.arraycopy(clusters, to, clusters, from + n, after);
        System.arraycopy(advances, to, advances, from + n, after);
        System.arraycopy(offsetsX, to, offsetsX, from + n, after);
        System.arraycopy(offsetsY, to, offsetsY, from + n, after);
        System.arraycopy(flags, to, flags, from + n, after);
        System.arraycopy(src.glyphs, 0, glyphs, from, n);
        System.arraycopy(src.clusters, 0, clusters, from, n);
        System.arraycopy(src.advances, 0, advances, from, n);
        System.arraycopy(src.offsetsX, 0, offsetsX, from, n);
        System.arraycopy(src.offsetsY, 0, offsetsY, from, n);
        System.arraycopy(src.flags, 0, flags, from, n);
        size = newSize;
    }

    /** Moves the clusters of glyphs {@code [from, to)} that are at least {@code atLeast} by {@code delta}. */
    void moveClusters(int from, int to, int atLeast, int delta) {
        if (delta == 0) {
            return;
        }
        for (int i = from; i < to; i++) {
            if (clusters[i] >= atLeast) {
                clusters[i] += delta;
            }
        }
    }

    /** Whether glyph {@code i} matches {@code other}'s glyph {@code j} with its cluster moved by {@code delta}. */
    boolean same(int i, ShapedGlyphs other, int j, int delta) {
        return glyphs[i] == other.glyphs[j] && clusters[i] == other.clusters[j] + delta
            && advances[i] == other.advances[j] && offsetsX[i] == other.offsetsX[j]
            && offsetsY[i] == other.offsetsY[j];
    }
}
