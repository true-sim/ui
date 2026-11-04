package com.truesim.ui.charts;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedDoubleRing;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Section 11's chart targets on the CPU (UI23, UI24's CPU path), under the default theme through the real text path:
 *
 * <ul>
 * <li>1,000,000 samples streaming at 1,000 a frame on a 4K-wide plot (3,840 pixel columns): the ring's min-max
 * pyramid appended, the X axis following, the envelope found and recorded (budget 0.5 ms a frame);
 * <li>10,000,000 samples, zooming and panning every frame (budget 1 ms): sorted X stored (binary searches) and evenly
 * spaced X (index arithmetic).
 * </ul>
 *
 * Prints the whole frame ({@code Ui.update}: poll, decimation, recording of axes, plot and overlay) and the decimation
 * alone ({@link Decimator#minMax}), median and p99. Runs with {@code TRUESIM_BENCHMARK=true} (JaCoCo off).
 */
class ChartBenchmarkTest {

    private static final float W = 3840 + 140; // the plot 3,840 pixels wide beside its Y axis
    private static final float H = 1000;

    private static String stats(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return String.format(Locale.ROOT, "median %.4f ms, p99 %.4f ms", sorted[sorted.length / 2] / 1e6,
            sorted[sorted.length * 99 / 100] / 1e6);
    }

    @Test
    void millionPointsStreamingAThousandAFrameAt4k() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")));
        float initial = ChartTunables.PYRAMID_BLOCK.get();
        for (int block : new int[] {8, 16, 32, 64}) {
            ChartTunables.PYRAMID_BLOCK.set(block);
            streaming(block);
        }
        ChartTunables.PYRAMID_BLOCK.set(initial);
    }

    private void streaming(int block) {
        Rig rig = new Rig(Rig.DEFAULT, Rig.nativeText());
        rig.width = W;
        rig.height = H;
        ChangeClock clock = rig.ui.store().clock();
        VersionedDoubleRing ring = clock.doubleRing(1_000_000);
        Chart chart = rig.chart("stream");
        LineSeries line = chart.line("signal", Columns.index(0, 0.001), Columns.of(ring));
        chart.x().range(0, 1000).fits(false).follow(true); // the whole ring across the plot
        SplittableRandom random = new SplittableRandom(1);
        double[] chunk = new double[1000];
        double[] fill = new double[1_000_000];
        for (int i = 0; i < fill.length; i++) {
            fill[i] = Math.sin(i * 0.0007) + random.nextGaussian() * 0.1;
        }
        ring.append(fill, 0, fill.length);
        rig.settle();
        int frames = 3000;
        long[] whole = new long[frames];
        long[] decimation = new long[frames];
        Decimator d = new Decimator();
        for (int f = -500; f < frames; f++) {
            long base = ring.appended();
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = Math.sin((base + i) * 0.0007) + random.nextGaussian() * 0.1;
            }
            ring.append(chunk, 0, chunk.length);
            long t0 = System.nanoTime();
            rig.run();
            long t1 = System.nanoTime();
            d.minMax(line.x(), line.y(), line.pyramid(), line.visibleFrom(), line.visibleTo(), chart.x(),
                (int) chart.plotLeft(), (int) chart.plotRight());
            long t2 = System.nanoTime();
            if (f >= 0) {
                whole[f] = t1 - t0;
                decimation[f] = t2 - t1;
            }
        }
        System.out.printf(Locale.ROOT,
            "1M streaming, 1,000 a frame, %d columns, blocks of %d: frame %s; envelope alone %s; pyramid %.1f MB%n",
            (int) (chart.plotRight() - chart.plotLeft()), block, stats(whole), stats(decimation),
            line.pyramid().bytes() / 1e6);
    }

    @Test
    void tenMillionPointsZoomingAndPanning() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")));
        int n = 10_000_000;
        double[] xs = new double[n];
        double[] ys = new double[n];
        SplittableRandom random = new SplittableRandom(2);
        double t = 0;
        for (int i = 0; i < n; i++) {
            t += 0.5 + random.nextDouble();
            xs[i] = t;
            ys[i] = Math.sin(t * 1e-5) + random.nextGaussian() * 0.2;
        }
        float initial = ChartTunables.PYRAMID_BLOCK.get();
        for (int block : new int[] {8, 16, 32, 64}) {
            ChartTunables.PYRAMID_BLOCK.set(block);
            zooming(block, n, xs, ys);
        }
        ChartTunables.PYRAMID_BLOCK.set(initial);
    }

    private void zooming(int block, int n, double[] xs, double[] ys) {
        for (boolean even : new boolean[] {false, true}) {
            Rig rig = new Rig(Rig.DEFAULT, Rig.nativeText());
            rig.width = W;
            rig.height = H;
            Chart chart = rig.chart("big");
            Column x = even ? Columns.index(0, 1) : Columns.of(xs);
            LineSeries line = chart.line("ten million", x, Columns.of(ys));
            rig.settle();
            double lo = x.get(0);
            double hi = x.get(n - 1);
            int frames = 1500;
            long[] whole = new long[frames];
            long[] decimation = new long[frames];
            Decimator d = new Decimator();
            for (int f = -300; f < frames; f++) {
                // a zoom in and out over the whole range, panning as it goes
                double phase = (f + 300) * 0.01;
                double span = (hi - lo) * (0.02 + 0.98 * (0.5 + 0.5 * Math.cos(phase)));
                double centre = lo + (hi - lo) * (0.5 + 0.45 * Math.sin(phase * 0.7));
                chart.x().range(centre - span / 2, centre + span / 2);
                long t0 = System.nanoTime();
                rig.run();
                long t1 = System.nanoTime();
                d.minMax(line.x(), line.y(), line.pyramid(), line.visibleFrom(), line.visibleTo(), chart.x(),
                    (int) chart.plotLeft(), (int) chart.plotRight());
                long t2 = System.nanoTime();
                if (f >= 0) {
                    whole[f] = t1 - t0;
                    decimation[f] = t2 - t1;
                }
            }
            System.out.printf(Locale.ROOT,
                "10M zoom and pan, %s X, blocks of %d: frame %s; envelope alone %s; pyramid %.1f MB%n",
                even ? "evenly spaced" : "sorted stored", block, stats(whole), stats(decimation),
                line.pyramid().bytes() / 1e6);
        }
    }
}
