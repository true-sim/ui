package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.RangeVisitor;

/**
 * The minimum and maximum of any index range of a {@link Column} in O(block + log n) (design UI23): a segment tree
 * over blocks of {@code block} values (a power of two), the values of partial blocks at a range's ends read directly.
 * With blocks the tree is small (two doubles per block and level: a 10-million-point column with blocks of 64 keeps
 * about 5 MB), and a pixel column's envelope costs two short scans and a walk up the tree.
 *
 * <p>For a ring, the tree is laid over the ring's slots (index {@code i} in slot {@code i mod capacity}): appending a
 * sample overwrites the oldest one's slot, so {@link #changed} recomputes only the blocks the appended samples landed
 * in and their ancestors, O(log n) each. Values outside the column's current window (a ring not yet full) and NaN are
 * left out. Queries and updates allocate nothing.
 */
public final class MinMaxPyramid implements RangeVisitor {

    private Column column;
    private int block = 64;
    private int shift = 6;
    private int capacity;
    private int blocks;
    private double[] min = new double[0];
    private double[] max = new double[0];
    private double resultMin;
    private double resultMax;
    private long lastBlock = -1;

    /** An empty pyramid with blocks of {@code block} values (rounded up to a power of two, at least 2). */
    public MinMaxPyramid(int block) {
        blockSize(block);
    }

    /** The block size (a power of two). */
    public int blockSize() {
        return block;
    }

    private void blockSize(int value) {
        int b = Math.max(2, Math.min(1 << 20, value));
        if (Integer.bitCount(b) != 1) {
            b = Integer.highestOneBit(b) << 1;
        }
        block = b;
        shift = Integer.numberOfTrailingZeros(b);
    }

    /** The column it was built over. */
    public Column column() {
        return column;
    }

    /** Builds the tree over {@code values} with blocks of {@code blockSize} values. O(n). */
    public void rebuild(Column values, int blockSize) {
        column = values;
        blockSize(blockSize);
        long c = values.capacity();
        if (c > Integer.MAX_VALUE - block) {
            throw new IllegalArgumentException("a pyramid holds at most 2^31 values: " + c);
        }
        capacity = (int) Math.max(0, c);
        blocks = (capacity + block - 1) >> shift;
        ensure(blocks);
        for (int b = 0; b < blocks; b++) {
            leaf(b);
        }
        for (int i = blocks - 1; i >= 1; i--) {
            min[i] = Math.min(min[2 * i], min[2 * i + 1]);
            max[i] = Math.max(max[2 * i], max[2 * i + 1]);
        }
    }

    @AllowAllocation("the tree grows with its column, rarely")
    private void ensure(int n) {
        if (min.length < 2 * n + 2) {
            min = new double[2 * n + 2];
            max = new double[2 * n + 2];
        }
    }

    /** The bytes the tree takes. */
    public long bytes() {
        return (long) min.length * 16;
    }

    /** Whether the column's slots no longer match the tree (its capacity changed): rebuild. */
    public boolean stale() {
        return column == null || column.capacity() != capacity;
    }

    /** The absolute index slot {@code slot} holds now (the column's window ends at {@code end}). */
    private long indexOf(int slot, long end) {
        long base = end - capacity;
        return base + Math.floorMod(slot - base, (long) capacity);
    }

    /** Recomputes block {@code b}'s leaf from the column's current values. */
    private void leaf(int b) {
        long first = column.first();
        long end = column.end();
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        int s0 = b << shift;
        int s1 = Math.min(capacity, s0 + block);
        if (capacity > 0) {
            long index = indexOf(s0, end);
            for (int s = s0; s < s1; s++, index++) {
                if (index >= end) {
                    index -= capacity; // wrapped around the ring
                }
                if (index >= first) {
                    double v = column.get(index);
                    if (v < lo) {
                        lo = v;
                    }
                    if (v > hi) {
                        hi = v;
                    }
                }
            }
        }
        min[blocks + b] = lo;
        max[blocks + b] = hi;
    }

    /** Recomputes block {@code b} and its ancestors. */
    private void update(int b) {
        leaf(b);
        for (int i = (blocks + b) >> 1; i >= 1; i >>= 1) {
            min[i] = Math.min(min[2 * i], min[2 * i + 1]);
            max[i] = Math.max(max[2 * i], max[2 * i + 1]);
        }
    }

    /**
     * Samples {@code from} to {@code to} (absolute, exclusive) were appended or set: their blocks and ancestors are
     * recomputed (everything when they cover the whole ring). A {@link RangeVisitor} for {@link Column#appendedSince}.
     */
    @Override
    public void changed(long from, long to) {
        if (capacity == 0 || to <= from) {
            return;
        }
        if (to - from >= capacity) {
            rebuild(column, block);
            return;
        }
        for (long i = from; i < to; i++) {
            int b = (int) (Math.floorMod(i, (long) capacity) >> shift);
            if (b != lastBlock) {
                update(b);
                lastBlock = b;
            }
        }
        lastBlock = -1;
    }

    /**
     * The minimum and maximum of absolute indices {@code from} to {@code to} (exclusive, clamped to the column's
     * window): {@link #min()} and {@link #max()}. False when the range holds no number (both are then infinite).
     */
    public boolean query(long from, long to) {
        resultMin = Double.POSITIVE_INFINITY;
        resultMax = Double.NEGATIVE_INFINITY;
        long a = Math.max(from, column.first());
        long b = Math.min(to, column.end());
        if (b <= a || capacity == 0) {
            return false;
        }
        int slot = (int) Math.floorMod(a, (long) capacity);
        long length = b - a;
        if (slot + length <= capacity) {
            segment(a, slot, (int) length);
        } else {
            int head = capacity - slot;
            segment(a, slot, head);
            segment(a + head, 0, (int) (length - head));
        }
        return resultMin <= resultMax;
    }

    /** Folds {@code length} values from absolute index {@code index}, stored from slot {@code slot} on, in. */
    private void segment(long index, int slot, int length) {
        int s0 = slot;
        int s1 = slot + length;
        int b0 = (s0 + block - 1) >> shift; // first whole block
        int b1 = s1 >> shift; // after the last whole block
        if (b0 >= b1) {
            scan(index, length);
            return;
        }
        int headEnd = b0 << shift;
        scan(index, headEnd - s0);
        tree(b0, b1);
        int tailStart = b1 << shift;
        scan(index + (tailStart - s0), s1 - tailStart);
    }

    private void scan(long index, int length) {
        double lo = resultMin;
        double hi = resultMax;
        Column c = column;
        for (long i = index, end = index + length; i < end; i++) {
            double v = c.get(i);
            if (v < lo) {
                lo = v;
            }
            if (v > hi) {
                hi = v;
            }
        }
        resultMin = lo;
        resultMax = hi;
    }

    private void tree(int b0, int b1) {
        double lo = resultMin;
        double hi = resultMax;
        int l = b0 + blocks;
        int r = b1 + blocks;
        while (l < r) {
            if ((l & 1) != 0) {
                lo = Math.min(lo, min[l]);
                hi = Math.max(hi, max[l]);
                l++;
            }
            if ((r & 1) != 0) {
                r--;
                lo = Math.min(lo, min[r]);
                hi = Math.max(hi, max[r]);
            }
            l >>= 1;
            r >>= 1;
        }
        resultMin = lo;
        resultMax = hi;
    }

    /** The minimum of the last {@link #query}. */
    public double min() {
        return resultMin;
    }

    /** The maximum of the last {@link #query}. */
    public double max() {
        return resultMax;
    }
}
