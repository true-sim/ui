package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * Heights of a text area's paragraphs with prefix sums (a Fenwick tree): the top of paragraph {@code i} and the
 * paragraph at a scroll position in O(log n), a changed height in O(log n), so a megabyte of text scrolls without
 * walking its lines. Heights of paragraphs not shaped yet are estimates, replaced as they come into view.
 */
final class HeightIndex {

    private int count;
    private float[] height = new float[16];
    private float[] tree = new float[17];

    /** Paragraphs. */
    int count() {
        return count;
    }

    /** Sets the count to {@code n} paragraphs (their new heights are then set and {@link #rebuild()} called). */
    void resize(int n) {
        ensure(n);
        count = n;
    }

    /** Makes room for {@code n} heights, keeping the count and the heights. */
    @AllowAllocation("growing with the text, rarely")
    void ensure(int n) {
        if (n > height.length) {
            int c = Math.max(n, height.length * 2);
            height = Arrays.copyOf(height, c);
            tree = new float[c + 1];
        }
    }

    /** The heights, to be changed in bulk before {@link #rebuild()}. */
    float[] heights() {
        return height;
    }

    /** Builds the sums from the heights: O(n). */
    void rebuild() {
        Arrays.fill(tree, 0, count + 1, 0);
        for (int i = 1; i <= count; i++) {
            tree[i] += height[i - 1];
            int parent = i + (i & -i);
            if (parent <= count) {
                tree[parent] += tree[i];
            }
        }
    }

    /** Paragraph {@code i}'s height. */
    float get(int i) {
        return height[i];
    }

    /** Sets paragraph {@code i}'s height. */
    void set(int i, float value) {
        float delta = value - height[i];
        if (delta == 0) {
            return;
        }
        height[i] = value;
        for (int k = i + 1; k <= count; k += k & -k) {
            tree[k] += delta;
        }
    }

    /** The sum of the heights before paragraph {@code i}: its top. */
    float top(int i) {
        float sum = 0;
        for (int k = Math.min(i, count); k > 0; k -= k & -k) {
            sum += tree[k];
        }
        return sum;
    }

    /** All heights. */
    float total() {
        return top(count);
    }

    /** The paragraph at {@code y} (its top at or above y), clamped to the first and last. */
    int at(float y) {
        if (count == 0) {
            return 0;
        }
        int pos = 0;
        float rest = y;
        int step = Integer.highestOneBit(count);
        for (; step > 0; step >>= 1) {
            int next = pos + step;
            if (next <= count && tree[next] <= rest) {
                pos = next;
                rest -= tree[next];
            }
        }
        return Math.min(pos, count - 1);
    }
}
