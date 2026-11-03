package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * Packs rectangles into a fixed page, one at a time, by the skyline bottom-left rule (Jylänki, "A Thousand Ways to Pack
 * the Bin"): the page's filled outline is a list of horizontal segments, and each rectangle goes where its top lands
 * highest (y grows down), leftmost on ties. It is the rule {@code stb_rect_pack} uses by default; the benchmark in
 * {@code ui-text-native} measures both (this one stays: same packing, no native calls). Allocation-free once grown.
 */
public final class SkylinePacker {

    private int width;
    private int height;
    private int[] nodeX = new int[64];
    private int[] nodeY = new int[64];
    private int[] nodeW = new int[64];
    private int count;
    private int resultX;
    private int resultY;
    private long usedArea;

    /** An empty page of {@code width} by {@code height}. */
    public SkylinePacker(int width, int height) {
        reset(width, height);
    }

    /** Empties the page and sets its size. */
    public void reset(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a page of " + width + " x " + height);
        }
        this.width = width;
        this.height = height;
        count = 1;
        nodeX[0] = 0;
        nodeY[0] = 0;
        nodeW[0] = width;
        usedArea = 0;
    }

    /**
     * Places a {@code w} by {@code h} rectangle: true and its corner in {@link #x()}, {@link #y()}; false if it does not
     * fit any more (the page is full for it).
     */
    public boolean pack(int w, int h) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("a rectangle of " + w + " x " + h);
        }
        if (w > width || h > height) {
            return false;
        }
        int best = -1;
        int bestY = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int y = fit(i, w, h, bestY);
            if (y >= 0) {
                best = i;
                bestY = y;
            }
        }
        if (best < 0) {
            return false;
        }
        resultX = nodeX[best];
        resultY = bestY;
        insert(best, resultX, bestY + h, w);
        usedArea += (long) w * h;
        return true;
    }

    /**
     * The top of a {@code w} by {@code h} rectangle whose left edge is node {@code i}'s, or -1 if it does not fit or
     * lands no higher than {@code bestY} (then it cannot win, so the scan stops early).
     */
    private int fit(int i, int w, int h, int bestY) {
        int x = nodeX[i];
        if (x + w > width) {
            return -1;
        }
        int y = 0;
        int left = w;
        for (int j = i; left > 0; j++) {
            y = Math.max(y, nodeY[j]);
            if (y + h > height || y >= bestY) {
                return -1;
            }
            left -= nodeW[j];
        }
        return y;
    }

    private void insert(int at, int x, int y, int w) {
        ensure(count + 1);
        System.arraycopy(nodeX, at, nodeX, at + 1, count - at);
        System.arraycopy(nodeY, at, nodeY, at + 1, count - at);
        System.arraycopy(nodeW, at, nodeW, at + 1, count - at);
        nodeX[at] = x;
        nodeY[at] = y;
        nodeW[at] = w;
        count++;
        // the new segment covers the start of the ones after it: shorten or drop them
        for (int i = at + 1; i < count; i++) {
            int end = nodeX[i - 1] + nodeW[i - 1];
            if (nodeX[i] >= end) {
                break;
            }
            int shrink = end - nodeX[i];
            nodeX[i] += shrink;
            nodeW[i] -= shrink;
            if (nodeW[i] > 0) {
                break;
            }
            remove(i);
            i--;
        }
        // only the new segment can now be level with a neighbour: merge locally, not over the whole skyline
        if (at + 1 < count && nodeY[at + 1] == y) {
            nodeW[at] += nodeW[at + 1];
            remove(at + 1);
        }
        if (at > 0 && nodeY[at - 1] == y) {
            nodeW[at - 1] += nodeW[at];
            remove(at);
        }
    }

    private void remove(int i) {
        System.arraycopy(nodeX, i + 1, nodeX, i, count - i - 1);
        System.arraycopy(nodeY, i + 1, nodeY, i, count - i - 1);
        System.arraycopy(nodeW, i + 1, nodeW, i, count - i - 1);
        count--;
    }

    private void ensure(int n) {
        if (n > nodeX.length) {
            grow(n * 2);
        }
    }

    @AllowAllocation("growing, rarely")
    private void grow(int n) {
        nodeX = Arrays.copyOf(nodeX, n);
        nodeY = Arrays.copyOf(nodeY, n);
        nodeW = Arrays.copyOf(nodeW, n);
    }

    /** The last packed rectangle's left edge. */
    public int x() {
        return resultX;
    }

    /** The last packed rectangle's top edge. */
    public int y() {
        return resultY;
    }

    /** The share of the page the packed rectangles cover, 0 to 1. */
    public float occupancy() {
        return (float) ((double) usedArea / ((double) width * height));
    }
}
