package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * Rows of different lengths (design 5.6): each row's measured length, or an estimate until it was measured, in a
 * Fenwick (binary indexed) tree of prefix sums, so where a row starts and which row covers an offset take O(log n),
 * and a row measured anew updates in O(log n). The estimate is the mean of the rows measured so far (or the one
 * given), so the scroll range converges as rows are seen.
 */
public final class MeasuredExtents implements Extents {

    private double[] tree = new double[1];
    private double[] sizes = new double[0];
    private boolean[] measured = new boolean[0];
    private int count;
    private int highBit;
    private double estimate;
    private double measuredSum;
    private int measuredCount;
    private double total;

    /** No rows yet; unmeasured rows are {@code estimate} long until the first is measured. */
    public MeasuredExtents(double estimate) {
        this.estimate = estimate;
    }

    /**
     * Sets the number of rows, all estimated again (a structural change: indices moved, so measurements no longer
     * belong to their rows). O(n).
     */
    @AllowAllocation("a structural change of the model: the tree is rebuilt")
    public void reset(int newCount) {
        if (measuredCount > 0) {
            estimate = measuredSum / measuredCount; // what rows measured so far suggest
        }
        if (newCount > sizes.length) {
            int capacity = Math.max(newCount, sizes.length * 2);
            sizes = new double[capacity];
            measured = new boolean[capacity];
            tree = new double[capacity + 1];
        } else {
            Arrays.fill(measured, 0, count, false);
        }
        count = newCount;
        measuredSum = 0;
        measuredCount = 0;
        double e = estimate;
        Arrays.fill(sizes, 0, count, e);
        // O(n) build: each node adds itself to its parent
        Arrays.fill(tree, 0, count + 1, 0);
        for (int i = 1; i <= count; i++) {
            tree[i] += e;
            int parent = i + (i & -i);
            if (parent <= count) {
                tree[parent] += tree[i];
            }
        }
        total = count * e;
        highBit = Integer.highestOneBit(Math.max(1, count));
    }

    /** The length of unmeasured rows. */
    public double estimate() {
        return estimate;
    }

    /** Whether row {@code index} was measured. */
    public boolean measured(int index) {
        return measured[index];
    }

    /** Row {@code index} measured {@code size} long; returns whether its length changed. O(log n). */
    public boolean measure(int index, double size) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(index);
        }
        if (measured[index]) {
            measuredSum -= sizes[index];
        } else {
            measured[index] = true;
            measuredCount++;
        }
        measuredSum += size;
        double delta = size - sizes[index];
        if (delta == 0) {
            return false;
        }
        sizes[index] = size;
        total += delta;
        for (int i = index + 1; i <= count; i += i & -i) {
            tree[i] += delta;
        }
        return true;
    }

    /** The mean measured length (the estimate before any row was measured). */
    public double meanMeasured() {
        return measuredCount == 0 ? estimate : measuredSum / measuredCount;
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public double total() {
        return total;
    }

    @Override
    public double offset(int index) {
        double sum = 0;
        for (int i = index; i > 0; i -= i & -i) {
            sum += tree[i];
        }
        return sum;
    }

    @Override
    public double size(int index) {
        return sizes[index];
    }

    @Override
    public int indexAt(double offset) {
        if (count == 0) {
            return -1;
        }
        // descend the tree: the most rows whose total length stays at or below the offset
        int position = 0;
        double rest = offset;
        for (int step = highBit; step > 0; step >>= 1) {
            int next = position + step;
            if (next <= count && tree[next] <= rest) {
                position = next;
                rest -= tree[next];
            }
        }
        return position >= count ? count - 1 : position;
    }
}
