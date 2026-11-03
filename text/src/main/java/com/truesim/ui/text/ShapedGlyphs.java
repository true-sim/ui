package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * Glyphs a {@link TextShaper} produced: per glyph its id in the face, the cluster (index of the first character it
 * came from), its advance and its offset from the pen, all in pixels (y up). Grows as needed and is reused.
 */
public final class ShapedGlyphs {

    private int size;
    private int[] glyphs = new int[64];
    private int[] clusters = new int[64];
    private float[] advances = new float[64];
    private float[] offsetsX = new float[64];
    private float[] offsetsY = new float[64];

    /** Forgets the glyphs, keeping the arrays. */
    public void clear() {
        size = 0;
    }

    /** Appends a glyph. */
    public void add(int glyph, int cluster, float advance, float offsetX, float offsetY) {
        if (size == glyphs.length) {
            grow();
        }
        glyphs[size] = glyph;
        clusters[size] = cluster;
        advances[size] = advance;
        offsetsX[size] = offsetX;
        offsetsY[size] = offsetY;
        size++;
    }

    @AllowAllocation("growing, rarely")
    private void grow() {
        int n = glyphs.length * 2;
        glyphs = Arrays.copyOf(glyphs, n);
        clusters = Arrays.copyOf(clusters, n);
        advances = Arrays.copyOf(advances, n);
        offsetsX = Arrays.copyOf(offsetsX, n);
        offsetsY = Arrays.copyOf(offsetsY, n);
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
}
