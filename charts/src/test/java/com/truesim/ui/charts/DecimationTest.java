package com.truesim.ui.charts;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedDoubleRing;
import com.truesim.common.state.VersionedFloatRing;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Decimation is exact (UI23): the min-max pyramid against brute force over random ranges, arrays and streaming rings
 * (appended incrementally, wrapped, partly filled, with NaN); the per-column envelope against brute force for sorted,
 * evenly spaced, duplicated, log and inverted X; LTTB against a reference implementation.
 */
class DecimationTest {

    private final SplittableRandom random = new SplittableRandom(11);

    private static double[] bruteMinMax(Column c, long a, long b) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (long i = Math.max(a, c.first()); i < Math.min(b, c.end()); i++) {
            double v = c.get(i);
            if (v < lo) {
                lo = v;
            }
            if (v > hi) {
                hi = v;
            }
        }
        return new double[] {lo, hi};
    }

    private void checkQueries(MinMaxPyramid p, Column c, int queries) {
        long first = c.first();
        long end = c.end();
        for (int q = 0; q < queries; q++) {
            long a = first + random.nextLong(Math.max(1, end - first + 1));
            long b = a + random.nextLong(Math.max(1, end - a + 1));
            if (q % 7 == 0) {
                a = first - 5;
                b = end + 5; // clamped to the window
            }
            double[] want = bruteMinMax(c, a, b);
            boolean any = p.query(a, b);
            assertThat(any).isEqualTo(want[0] <= want[1]);
            assertThat(p.min()).isEqualTo(want[0]);
            assertThat(p.max()).isEqualTo(want[1]);
        }
    }

    @Test
    void pyramidMatchesBruteForceOnArrays() {
        for (int block : new int[] {2, 3, 8, 64, 1000}) {
            for (int n : new int[] {0, 1, 5, 63, 64, 65, 1000, 4097}) {
                double[] v = new double[n];
                for (int i = 0; i < n; i++) {
                    v[i] = random.nextDouble() * 100 - 50;
                    if (random.nextInt(50) == 0) {
                        v[i] = Double.NaN;
                    }
                }
                Columns.Values c = Columns.of(v);
                MinMaxPyramid p = new MinMaxPyramid(block);
                p.rebuild(c, block);
                assertThat(Integer.bitCount(p.blockSize())).isEqualTo(1);
                checkQueries(p, c, 200);
                // a set value updates by its block
                if (n > 0) {
                    int i = random.nextInt(n);
                    c.set(i, 1e9);
                    p.changed(i, i + 1);
                    checkQueries(p, c, 50);
                }
                assertThat(p.column()).isSameAs(c);
                assertThat(p.stale()).isFalse();
                assertThat(p.bytes()).isPositive();
            }
        }
    }

    @Test
    void pyramidOverAStreamingRingStaysExactAsItAppendsAndWraps() {
        ChangeClock clock = new ChangeClock();
        VersionedDoubleRing ring = clock.doubleRing(1000);
        Column c = Columns.of(ring);
        MinMaxPyramid p = new MinMaxPyramid(16);
        p.rebuild(c, 16);
        long seen = ring.version();
        double[] chunk = new double[300];
        for (int step = 0; step < 40; step++) {
            int n = 1 + random.nextInt(chunk.length);
            for (int i = 0; i < n; i++) {
                chunk[i] = Math.sin(step * 0.3 + i * 0.01) * 10 + random.nextGaussian();
            }
            ring.append(chunk, 0, n);
            assertThat(c.appendedSince(seen, p)).isTrue();
            seen = ring.version();
            checkQueries(p, c, 60);
        }
        // the same as a pyramid built from scratch
        MinMaxPyramid fresh = new MinMaxPyramid(16);
        fresh.rebuild(c, 16);
        for (int q = 0; q < 100; q++) {
            long a = c.first() + random.nextLong(c.size());
            long b = a + random.nextLong(c.end() - a + 1);
            p.query(a, b);
            fresh.query(a, b);
            assertThat(p.min()).isEqualTo(fresh.min());
            assertThat(p.max()).isEqualTo(fresh.max());
        }
        // a big append (more than the ring) rebuilds
        double[] big = new double[2500];
        for (int i = 0; i < big.length; i++) {
            big[i] = i;
        }
        ring.append(big, 0, big.length);
        assertThat(c.appendedSince(seen, p)).isTrue();
        checkQueries(p, c, 50);
        // cleared: a change of structure
        seen = ring.version();
        ring.clear();
        assertThat(c.appendedSince(seen, p)).isFalse();
        assertThat(c.structureVersion()).isEqualTo(ring.version());
        p.rebuild(c, 16);
        assertThat(p.query(0, Long.MAX_VALUE)).isFalse();
    }

    @Test
    void floatRingsAndPartlyFilledRings() {
        ChangeClock clock = new ChangeClock();
        VersionedFloatRing ring = clock.floatRing(256);
        Column c = Columns.of(ring);
        MinMaxPyramid p = new MinMaxPyramid(8);
        p.rebuild(c, 8);
        long seen = ring.version();
        for (int i = 0; i < 100; i++) {
            ring.append((float) random.nextGaussian());
        }
        c.appendedSince(seen, p);
        assertThat(c.capacity()).isEqualTo(256);
        assertThat(c.size()).isEqualTo(100);
        checkQueries(p, c, 100);
    }

    // ---------------------------------------------------------------- the envelope per pixel column

    private Axis axis(Scale scale, double min, double max, int px0, int px1, boolean inverted) {
        Rig rig = new Rig(Rig.DEFAULT, null);
        Chart chart = rig.chart("d");
        Axis a = chart.addAxis("d", Axis.Side.BOTTOM, scale);
        a.range().set(min, max);
        a.inverted(inverted);
        a.map(px0, px1);
        return a;
    }

    private static void checkEnvelope(Decimator d, Column x, Column y, long a, long b, Axis axis, int px0, int px1) {
        // brute force: which column each sample falls in, by its pixel
        int width = px1 - px0;
        long[] from = new long[width];
        long[] to = new long[width];
        java.util.Arrays.fill(from, Long.MAX_VALUE);
        java.util.Arrays.fill(to, Long.MIN_VALUE);
        for (int k = 0; k < d.columns(); k++) {
            int col = (int) Math.floor(d.pixel(k)) - px0;
            assertThat(col).isBetween(0, width - 1);
            double[] want = new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (long i = d.from(k); i < d.to(k); i++) {
                double xv = x.get(i);
                double p = axis.toPixel(xv);
                // every sample of the column lies in its pixel (up to rounding at the edges)
                assertThat(p).isBetween((double) px0 + col - 1e-3, (double) px0 + col + 1 + 1e-3);
                want[0] = Math.min(want[0], y.get(i));
                want[1] = Math.max(want[1], y.get(i));
            }
            assertThat(d.minY(k)).isEqualTo(want[0]);
            assertThat(d.maxY(k)).isEqualTo(want[1]);
            assertThat(d.firstY(k)).isEqualTo(y.get(d.from(k)));
            assertThat(d.lastY(k)).isEqualTo(y.get(d.to(k) - 1));
            if (k > 0) {
                assertThat(d.from(k)).isEqualTo(d.to(k - 1)); // contiguous, nothing skipped or repeated
            }
        }
        // every sample inside the axis's pixels is in some column
        long covered = 0;
        for (int k = 0; k < d.columns(); k++) {
            covered += d.to(k) - d.from(k);
        }
        double lo = Math.min(axis.fromPixel(px0), axis.fromPixel(px1));
        double hi = Math.max(axis.fromPixel(px0), axis.fromPixel(px1));
        long inside = 0;
        for (long i = a; i < b; i++) {
            double v = x.get(i);
            if (v >= lo && v <= hi) {
                inside++;
            }
        }
        assertThat(covered).isEqualTo(inside); // the far edge's samples join the last column
    }

    @Test
    void envelopeMatchesBruteForce() {
        int n = 50_000;
        double[] xs = new double[n];
        double[] ys = new double[n];
        double t = 0;
        for (int i = 0; i < n; i++) {
            t += random.nextInt(4) == 0 ? 0 : random.nextDouble() * 0.01; // duplicates too
            xs[i] = t;
            ys[i] = Math.sin(t) + random.nextGaussian() * 0.1;
        }
        Column x = Columns.of(xs);
        Column y = Columns.of(ys);
        MinMaxPyramid p = new MinMaxPyramid(32);
        p.rebuild(y, 32);
        Decimator d = new Decimator();
        for (boolean inverted : new boolean[] {false, true}) {
            Axis axis = axis(Scale.LINEAR, xs[n / 10], xs[n / 2], 50, 650, inverted);
            d.minMax(x, y, p, 0, n, axis, 50, 650);
            assertThat(d.columns()).isBetween(500, 600);
            checkEnvelope(d, x, y, 0, n, axis, 50, 650);
        }
        // evenly spaced X: index arithmetic instead of searches
        Column even = Columns.index(0, 0.001);
        Axis axis = axis(Scale.LINEAR, 3, 40, 0, 1000, false);
        d.minMax(even, y, p, 0, n, axis, 0, 1000);
        checkEnvelope(d, even, y, 0, n, axis, 0, 1000);
        // log X
        double[] lx = new double[n];
        for (int i = 0; i < n; i++) {
            lx[i] = 1 + i * 0.01;
        }
        Column logX = Columns.of(lx);
        Axis log = axis(Scale.LOG, 1, 500, 0, 800, false);
        d.minMax(logX, y, p, 0, n, log, 0, 800);
        checkEnvelope(d, logX, y, 0, n, log, 0, 800);
        // nothing to do
        d.minMax(x, y, p, 5, 5, axis, 0, 100);
        assertThat(d.columns()).isZero();
    }

    @Test
    void searches() {
        Column x = Columns.of(1, 2, 2, 2, 5, 9);
        assertThat(Decimator.lowerBound(x, 2, 0, 6)).isEqualTo(1);
        assertThat(Decimator.lowerBound(x, 3, 0, 6)).isEqualTo(4);
        assertThat(Decimator.lowerBound(x, 0, 0, 6)).isZero();
        assertThat(Decimator.lowerBound(x, 10, 0, 6)).isEqualTo(6);
        for (double v = 0; v < 10; v += 0.5) {
            assertThat(Decimator.gallop(x, v, 0, 6)).isEqualTo(Decimator.lowerBound(x, v, 0, 6));
        }
        Column even = Columns.index(10, 2);
        assertThat(Decimator.lowerBound(even, 14, 0, 100)).isEqualTo(2);
        assertThat(Decimator.lowerBound(even, 13, 0, 100)).isEqualTo(2);
        assertThat(Decimator.lowerBound(even, -5, 0, 100)).isZero();
        assertThat(Decimator.lowerBound(even, 1e9, 0, 100)).isEqualTo(100);
        assertThat(Decimator.lowerBound(Columns.index(0, 0), 5, 3, 9)).isEqualTo(3);
    }

    // ---------------------------------------------------------------- LTTB

    /** LTTB as Steinarsson's reference code does it, on plain arrays. */
    private static List<Integer> referenceLttb(double[] x, double[] y, int threshold) {
        int n = x.length;
        List<Integer> out = new ArrayList<>();
        if (threshold >= n || threshold == 0) {
            for (int i = 0; i < n; i++) {
                out.add(i);
            }
            return out;
        }
        double every = (double) (n - 2) / (threshold - 2);
        int a = 0;
        out.add(0);
        for (int i = 0; i < threshold - 2; i++) {
            int avgStart = (int) Math.floor((i + 1) * every) + 1;
            int avgEnd = Math.min((int) Math.floor((i + 2) * every) + 1, n);
            double avgX = 0;
            double avgY = 0;
            for (int j = avgStart; j < avgEnd; j++) {
                avgX += x[j];
                avgY += y[j];
            }
            avgX /= avgEnd - avgStart;
            avgY /= avgEnd - avgStart;
            int rangeOffs = (int) Math.floor(i * every) + 1;
            int rangeTo = (int) Math.floor((i + 1) * every) + 1;
            double max = -1;
            int next = rangeOffs;
            for (int j = rangeOffs; j < rangeTo; j++) {
                double area = Math.abs((x[a] - avgX) * (y[j] - y[a]) - (x[a] - x[j]) * (avgY - y[a]));
                if (area > max) {
                    max = area;
                    next = j;
                }
            }
            out.add(next);
            a = next;
        }
        out.add(n - 1);
        return out;
    }

    @Test
    void lttbMatchesTheReference() {
        Axis xa = axis(Scale.LINEAR, 0, 1, 0, 100, false);
        Axis ya = axis(Scale.LINEAR, 0, 1, 0, 100, false);
        Decimator d = new Decimator();
        for (int n : new int[] {10, 1000, 33_333}) {
            double[] x = new double[n];
            double[] y = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = i * 0.5 + random.nextDouble() * 0.1;
                y[i] = random.nextGaussian();
            }
            for (int threshold : new int[] {3, 50, 999}) {
                d.lttb(Columns.of(x), Columns.of(y), 0, n, threshold, xa, ya);
                List<Integer> want = referenceLttb(x, y, threshold);
                assertThat(d.pickedCount()).isEqualTo(want.size());
                for (int i = 0; i < want.size(); i++) {
                    assertThat(d.picked(i)).isEqualTo((long) want.get(i));
                }
            }
        }
        // a window: indices offset
        double[] x = new double[100];
        double[] y = new double[100];
        for (int i = 0; i < 100; i++) {
            x[i] = i;
            y[i] = i % 7;
        }
        d.lttb(Columns.of(x), Columns.of(y), 20, 80, 10, xa, ya);
        assertThat(d.picked(0)).isEqualTo(20);
        assertThat(d.picked(d.pickedCount() - 1)).isEqualTo(79);
        d.lttb(Columns.of(x), Columns.of(y), 0, 100, 2, xa, ya);
        assertThat(d.pickedCount()).isEqualTo(2);
        d.lttb(Columns.of(x), Columns.of(y), 0, 100, 1, xa, ya);
        assertThat(d.pickedCount()).isEqualTo(1);
        d.lttb(Columns.of(x), Columns.of(y), 5, 5, 10, xa, ya);
        assertThat(d.pickedCount()).isZero();
        // a log axis picks in log units
        Axis log = axis(Scale.LOG, 1, 100, 0, 100, false);
        double[] lx = new double[200];
        for (int i = 0; i < 200; i++) {
            lx[i] = 1 + i;
        }
        d.lttb(Columns.of(lx), Columns.of(y.length == 200 ? y : new double[200]), 0, 200, 20, log, ya);
        assertThat(d.pickedCount()).isEqualTo(20);
    }

    @Test
    void scatterGridFindsTheNearestPointLikeBruteForce() {
        int n = 5000;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = random.nextGaussian() * 10;
            y[i] = random.nextGaussian() * 3 + x[i] * 0.2;
        }
        Column xc = Columns.of(x);
        Column yc = Columns.of(y);
        Axis xa = axis(Scale.LINEAR, -30, 30, 0, 800, false);
        Rig rig = new Rig(Rig.DEFAULT, null);
        Chart chart = rig.chart("g");
        Axis ya = chart.addAxis("gy", Axis.Side.LEFT, Scale.LINEAR);
        ya.range().set(-12, 12);
        ya.map(600, 0);
        ScatterGrid grid = new ScatterGrid();
        grid.build(xc, yc, 0, n, xa, ya, 4);
        assertThat(grid.side()).isGreaterThan(10);
        for (int q = 0; q < 300; q++) {
            float px = (float) (random.nextDouble() * 900 - 50);
            float py = (float) (random.nextDouble() * 700 - 50);
            float reach = q % 3 == 0 ? Float.POSITIVE_INFINITY : 30;
            long best = -1;
            double bestD = reach;
            for (int i = 0; i < n; i++) {
                double dx = xa.toPixel(x[i]) - px;
                double dy = ya.toPixel(y[i]) - py;
                double dd = Math.sqrt(dx * dx + dy * dy);
                if (dd <= bestD) {
                    bestD = dd;
                    best = i;
                }
            }
            long got = grid.nearest(xc, yc, xa, ya, px, py, reach);
            if (best < 0) {
                assertThat(got).isEqualTo(-1);
            } else {
                assertThat(grid.distance()).isCloseTo(bestD, org.assertj.core.data.Offset.offset(1e-3));
            }
        }
        ScatterGrid empty = new ScatterGrid();
        empty.build(xc, yc, 0, 0, xa, ya, 4);
        assertThat(empty.nearest(xc, yc, xa, ya, 10, 10, 100)).isEqualTo(-1);
    }
}
