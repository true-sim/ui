package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;

/**
 * Reduces a series with sorted X to what its pixels can show (design UI23), into buffers it keeps:
 *
 * <ul>
 * <li>{@link #minMax}: per pixel column, the index range of the samples whose X falls in it, their first and last Y
 * and their minimum and maximum Y from a {@link MinMaxPyramid} (the M4 envelope): a line drawn through first, min,
 * max and last of each column covers exactly the pixels the full polyline covers, at any zoom, in
 * O(columns x (log n + block)).
 * <li>{@link #lttb}: Largest-Triangle-Three-Buckets (Steinarsson 2013): a subset of the samples that keeps the shape,
 * for markers and scatter, in O(n) over the visible samples.
 * </ul>
 *
 * <p>Index searches use the X column's even spacing when it has one ({@link Column#uniformStep()}), and galloping
 * binary searches otherwise. Nothing allocates once the buffers have grown to the plot's width.
 */
public final class Decimator {

    private int columns;
    private float[] pixel = new float[0];
    private long[] from = new long[0];
    private long[] to = new long[0];
    private double[] firstY = new double[0];
    private double[] lastY = new double[0];
    private double[] minY = new double[0];
    private double[] maxY = new double[0];
    private long[] picked = new long[0];
    private int pickedCount;

    // ---------------------------------------------------------------- searching sorted X

    /** The first index in {@code lo..hi} whose X is at or above {@code value} ({@code hi} if none); X sorted. */
    public static long lowerBound(Column x, double value, long lo, long hi) {
        double step = x.uniformStep();
        if (step == step) {
            double start = x.uniformStart();
            if (step > 0) {
                double i = Math.ceil((value - start) / step - 1e-12);
                return clamp(i, lo, hi);
            }
            return lo;
        }
        long a = lo;
        long b = hi;
        while (a < b) {
            long m = (a + b) >>> 1;
            if (x.get(m) < value) {
                a = m + 1;
            } else {
                b = m;
            }
        }
        return a;
    }

    private static long clamp(double i, long lo, long hi) {
        if (i != i || i <= lo) {
            return lo;
        }
        return i >= hi ? hi : (long) i;
    }

    /** {@link #lowerBound} starting from {@code lo} by doubling steps first: cheap when the answer is close. */
    static long gallop(Column x, double value, long lo, long hi) {
        if (x.uniformStep() == x.uniformStep() || lo >= hi || x.get(lo) >= value) {
            return lowerBound(x, value, lo, hi);
        }
        long step = 1;
        long prev = lo;
        long probe = lo + 1;
        while (probe < hi && x.get(probe) < value) {
            prev = probe;
            step <<= 1;
            probe = lo + step;
        }
        return lowerBound(x, value, prev + 1, Math.min(probe, hi));
    }

    // ---------------------------------------------------------------- min-max columns

    /**
     * The M4 envelope of samples {@code a..b} (X sorted ascending) over the pixel columns from {@code px0} to
     * {@code px1} of {@code axis} (one per whole pixel): {@link #columns()} non-empty columns with their
     * {@link #pixel} (centre), index range, first, last, minimum and maximum Y. {@code pyramid} answers min and max for
     * {@code y}.
     */
    public void minMax(Column x, Column y, MinMaxPyramid pyramid, long a, long b, Axis axis, int px0, int px1) {
        int width = Math.max(0, px1 - px0);
        ensure(width);
        columns = 0;
        if (b <= a || width == 0) {
            return;
        }
        boolean ascending = axis.fromPixel(px0 + 1) >= axis.fromPixel(px0);
        double edge = axis.fromPixel(ascending ? px0 : px1);
        long start = lowerBound(x, edge, a, b);
        boolean uniform = x.uniformStep() == x.uniformStep();
        long last = 1; // samples in the last column: where the next column's end is looked for first
        for (int c = 0; c < width; c++) {
            int p = ascending ? px0 + c : px1 - 1 - c;
            double right = axis.fromPixel(ascending ? p + 1 : p);
            long end;
            if (c == width - 1) {
                end = lowerBoundAfter(x, right, start, b);
            } else if (uniform) {
                end = lowerBound(x, right, start, b);
            } else {
                end = guided(x, right, start, b, start + last);
            }
            last = Math.max(1, end - start);
            if (end > start) {
                int o = columns++;
                pixel[o] = p + 0.5f;
                from[o] = start;
                to[o] = end;
                firstY[o] = y.get(start);
                lastY[o] = y.get(end - 1);
                if (end - start <= 2) {
                    minY[o] = Math.min(firstY[o], lastY[o]);
                    maxY[o] = Math.max(firstY[o], lastY[o]);
                } else {
                    pyramid.query(start, end);
                    minY[o] = pyramid.min();
                    maxY[o] = pyramid.max();
                }
            }
            start = Math.max(start, end);
        }
    }

    /**
     * {@link #lowerBound} in {@code lo..hi} starting at {@code guess} (where the last column's width predicts it, data
     * being about evenly spaced in X): galloping forwards or backwards from there, so a column costs a few reads of X
     * however many samples it holds.
     */
    static long guided(Column x, double value, long lo, long hi, long guess) {
        if (guess >= hi || guess <= lo) {
            return gallop(x, value, lo, hi);
        }
        if (x.get(guess) < value) {
            return gallop(x, value, guess, hi);
        }
        // the answer lies at or before the guess: back off by doubling steps
        long upper = guess;
        long step = 1;
        long probe = guess - 1;
        while (probe > lo && x.get(probe) >= value) {
            upper = probe;
            step <<= 1;
            probe = guess - step;
        }
        return lowerBound(x, value, Math.max(lo, probe), upper);
    }

    /** The last column takes the samples at its right edge too. */
    private static long lowerBoundAfter(Column x, double value, long lo, long hi) {
        long i = gallop(x, value, lo, hi);
        while (i < hi && x.get(i) <= value) {
            i++;
        }
        return i;
    }

    @AllowAllocation("the buffers grow to the plot's width, rarely")
    private void ensure(int width) {
        if (pixel.length < width) {
            int n = Math.max(width, pixel.length * 3 / 2);
            pixel = new float[n];
            from = new long[n];
            to = new long[n];
            firstY = new double[n];
            lastY = new double[n];
            minY = new double[n];
            maxY = new double[n];
        }
    }

    /** Non-empty columns of the last {@link #minMax}. */
    public int columns() {
        return columns;
    }

    /** Column {@code c}'s pixel centre. */
    public float pixel(int c) {
        return pixel[c];
    }

    /** Column {@code c}'s first sample index. */
    public long from(int c) {
        return from[c];
    }

    /** Column {@code c}'s end sample index (exclusive). */
    public long to(int c) {
        return to[c];
    }

    /** Column {@code c}'s first Y. */
    public double firstY(int c) {
        return firstY[c];
    }

    /** Column {@code c}'s last Y. */
    public double lastY(int c) {
        return lastY[c];
    }

    /** Column {@code c}'s smallest Y. */
    public double minY(int c) {
        return minY[c];
    }

    /** Column {@code c}'s largest Y. */
    public double maxY(int c) {
        return maxY[c];
    }

    // ---------------------------------------------------------------- LTTB

    /**
     * Picks {@code threshold} samples of {@code a..b} by Largest-Triangle-Three-Buckets, in the axes' transformed
     * units (so log axes keep their shape): {@link #picked()} indices, ascending. Fewer samples than the threshold are
     * all picked.
     */
    public void lttb(Column x, Column y, long a, long b, int threshold, Axis xAxis, Axis yAxis) {
        long n = b - a;
        pickedCount = 0;
        if (n <= 0) {
            return;
        }
        if (threshold >= n) {
            ensurePicked((int) n);
            for (long i = a; i < b; i++) {
                picked[pickedCount++] = i;
            }
            return;
        }
        if (threshold < 3) { // the ends only
            ensurePicked(2);
            picked[pickedCount++] = a;
            if (threshold == 2) {
                picked[pickedCount++] = b - 1;
            }
            return;
        }
        ensurePicked(threshold);
        double bucket = (double) (n - 2) / (threshold - 2);
        long chosen = a;
        picked[pickedCount++] = a;
        for (int i = 0; i < threshold - 2; i++) {
            long avgStart = a + (long) Math.floor((i + 1) * bucket) + 1;
            long avgEnd = Math.min(a + (long) Math.floor((i + 2) * bucket) + 1, b);
            double avgX = 0;
            double avgY = 0;
            long avgCount = 0;
            for (long j = avgStart; j < avgEnd; j++) {
                avgX += t(xAxis, x.get(j));
                avgY += t(yAxis, y.get(j));
                avgCount++;
            }
            if (avgCount > 0) {
                avgX /= avgCount;
                avgY /= avgCount;
            }
            long rangeStart = a + (long) Math.floor(i * bucket) + 1;
            long rangeEnd = a + (long) Math.floor((i + 1) * bucket) + 1;
            double ax = t(xAxis, x.get(chosen));
            double ay = t(yAxis, y.get(chosen));
            double best = -1;
            long next = rangeStart;
            for (long j = rangeStart; j < rangeEnd; j++) {
                double area = Math.abs((ax - avgX) * (t(yAxis, y.get(j)) - ay) - (ax - t(xAxis, x.get(j))) * (avgY - ay));
                if (area > best) {
                    best = area;
                    next = j;
                }
            }
            picked[pickedCount++] = next;
            chosen = next;
        }
        picked[pickedCount++] = b - 1;
    }

    private static double t(Axis axis, double v) {
        Scale s = axis.scale();
        return s == Scale.LOG || s == Scale.SYMLOG ? axis.forward(v) : v;
    }

    @AllowAllocation("the buffer grows to the plot's width, rarely")
    private void ensurePicked(int n) {
        if (picked.length < n) {
            picked = new long[Math.max(n, picked.length * 3 / 2)];
        }
    }

    /** How many samples the last {@link #lttb} picked. */
    public int pickedCount() {
        return pickedCount;
    }

    /** The {@code i}th picked sample's index. */
    public long picked(int i) {
        return picked[i];
    }
}
