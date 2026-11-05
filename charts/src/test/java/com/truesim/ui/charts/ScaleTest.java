package com.truesim.ui.charts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.testing.AllocationAssertions;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Custom scales: the built-in power, square root, reversed and piecewise scales, a scale of a game's own, their
 * mappings both ways, fitting, navigation and adaptive ticks (checked against what they promise: nice values, the
 * spacing kept on the screen, ascending, labelled).
 */
class ScaleTest {

    private final Rig rig = new Rig(Rig.DEFAULT, null);
    private final Chart chart = rig.chart("scales");

    /** A game's own scale: the cube root. */
    private static final Scale CUBE = new Scale() {
        @Override
        public double forward(double value) {
            return Math.cbrt(value);
        }

        @Override
        public double inverse(double transformed) {
            return transformed * transformed * transformed;
        }
    };

    private Axis axis(Scale scale, double min, double max) {
        Axis a = chart.addAxis("a" + chart.axes().size(), Axis.Side.BOTTOM, scale);
        a.range().set(min, max);
        a.map(100, 500);
        return a;
    }

    @Test
    void builtInsMapBothWays() {
        Axis sqrt = axis(Scale.SQRT, 0, 100);
        assertThat(sqrt.toPixel(25)).isCloseTo(300f, within(1e-3f)); // sqrt(25) = 5, half of sqrt(100)
        assertThat(sqrt.toPixel(-4)).as("odd: the sign kept").isCloseTo(100 - 80f, within(1e-3f));
        Axis cubed = axis(Scale.power(3), 0, 2);
        assertThat(cubed.toPixel(1)).isCloseTo(150f, within(1e-3f)); // 1 of 8
        Axis pieces = axis(Scale.piecewise(new double[] {0, 10, 100}, new double[] {0, 2, 3}), 0, 100);
        assertThat(pieces.toPixel(10)).isCloseTo(100 + 400 * 2 / 3f, within(1e-3f));
        assertThat(pieces.toPixel(55)).isCloseTo(100 + 400 * 2.5f / 3f, within(1e-3f));
        assertThat(pieces.forward(-5)).as("extrapolated by the first piece").isEqualTo(-1);
        assertThat(pieces.forward(190)).as("and the last").isEqualTo(4);
        assertThat(pieces.forward(Double.NaN)).isNaN();
        Axis own = axis(CUBE, -8, 8);
        assertThat(own.toPixel(0)).isCloseTo(300f, within(1e-3f));
        assertThat(own.toPixel(1)).isCloseTo(400f, within(1e-3f));
        for (Axis a : List.of(sqrt, cubed, pieces, own)) {
            for (double v = a.range().min(); v <= a.range().max(); v += (a.range().max() - a.range().min()) / 7) {
                assertThat(a.fromPixel(a.toPixel(v))).as("%s at %s", a.scale(), v)
                    .isCloseTo(v, within(1e-4 * Math.max(1, Math.abs(v))));
            }
        }
        assertThat(Scale.SQRT.identity()).isFalse();
        assertThat(Scale.LINEAR.identity()).isTrue();
        assertThat(Scale.SYMLOG.toString()).contains("SYMLOG");
        assertThat(Scale.reversed(Scale.LOG).toString()).isEqualTo("REVERSED(LOG)");
        assertThat(Scale.piecewise(new double[] {0, 1}, new double[] {0, 1}).toString()).startsWith("PIECEWISE");
        assertThat(Scale.SQRT.toString()).isEqualTo("POWER(0.5)");
        assertThatThrownBy(() -> Scale.power(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scale.symlog(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scale.reversed(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scale.piecewise(new double[] {0}, new double[] {0}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scale.piecewise(new double[] {0, 1}, new double[] {1, 0}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scale.piecewise(new double[] {0, Double.NaN}, new double[] {0, 1}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chart.addAxis("none", Axis.Side.LEFT, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sqrt.scale(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reversedRunsTheOtherWay() {
        Axis rev = axis(Scale.reversed(Scale.LOG), 1, 1e4);
        assertThat(rev.toPixel(1e4)).as("the maximum at the start").isCloseTo(100f, within(1e-3f));
        assertThat(rev.toPixel(1)).isCloseTo(500f, within(1e-3f));
        assertThat(rev.toPixel(100)).isCloseTo(300f, within(1e-3f));
        assertThat(rev.fromPixel(200)).isCloseTo(1000, within(1e-6));
        assertThat(Scale.reversed(Scale.reversed(Scale.LOG))).isSameAs(Scale.LOG);
        assertThat(Scale.reversed(Scale.LOG).defaultMin()).isEqualTo(1);
        assertThat(Scale.reversed(Scale.LOG).defaultMax()).isEqualTo(10);
        // zoom about a point keeps it under the pointer; pan moves the content with the pointer; a box zooms to it
        double under = rev.fromPixel(250);
        rev.zoom(250, 0.5);
        assertThat(rev.fromPixel(250)).isCloseTo(under, within(under * 1e-9));
        assertThat(rev.range().min()).isLessThan(rev.range().max());
        rev.range().set(1, 1e4);
        rev.remap();
        rev.pan(100); // dragging right: the start shows larger values (the maximum lies there)
        assertThat(rev.range().max()).isGreaterThan(1e4);
        rev.zoomTo(100, 300);
        assertThat(rev.range().min()).isLessThan(rev.range().max());
        assertThat(rev.range().max() / rev.range().min()).isCloseTo(100, within(1e-6));
        // fitting sorts what the reversed transform turns round
        assertThat(rev.fit(10, 1000, 0)).isTrue();
        assertThat(rev.range().min()).isCloseTo(10, within(1e-9));
        assertThat(rev.range().max()).isCloseTo(1000, within(1e-6));
        // ticks and tooltip text are the reversed scale's
        rev.ticks.generate(rev, 400, 40, 40, true);
        assertThat(rev.ticks().majors()).isGreaterThan(1);
        assertThat(rev.format(new StringBuilder(), 2500).toString()).isEqualTo("2500");
    }

    @Test
    void anAxisFallsBackToItsScalesDefaultRange() {
        Axis log = axis(Scale.LOG, -5, -1); // nothing a logarithm shows
        assertThat(log.fromPixel(100)).isCloseTo(1, within(1e-9));
        assertThat(log.fromPixel(500)).isCloseTo(10, within(1e-9));
        Axis pieces = axis(Scale.piecewise(new double[] {2, 4}, new double[] {0, 1}), 3, 3);
        assertThat(pieces.fromPixel(100)).isCloseTo(2, within(1e-9));
        Scale constant = new Scale() { // a scale showing nothing apart: the unit range
            @Override
            public double forward(double value) {
                return 0;
            }

            @Override
            public double inverse(double transformed) {
                return transformed;
            }
        };
        Axis flat = axis(constant, 0, 1);
        assertThat(flat.toPixel(0.5)).isFinite();
        Chart c = rig.chart("defaults");
        Axis fresh = c.addAxis("p", Axis.Side.LEFT, Scale.piecewise(new double[] {5, 9}, new double[] {0, 1}));
        assertThat(fresh.range().min()).isEqualTo(5);
        assertThat(fresh.range().max()).isEqualTo(9);
    }

    @Test
    void scaleAndSymlogConstantChangeTheAxis() {
        Axis a = axis(Scale.LINEAR, 0, 100);
        a.scale(Scale.SQRT);
        a.map(100, 500);
        assertThat(a.scale()).isSameAs(Scale.SQRT);
        assertThat(a.toPixel(25)).isCloseTo(300f, within(1e-3f));
        assertThat(a.symlogConstant()).as("not a symlog").isEqualTo(1);
        a.symlogConstant(10);
        assertThat(a.scale().toString()).isEqualTo("SYMLOG(10.0)");
        assertThat(a.symlogConstant()).isEqualTo(10);
        assertThat(a.scale(a.scale())).isSameAs(a);
    }

    /** Majors of {@code a} for {@code pixels} at {@code spacing}: checked against what adaptive ticks promise. */
    private List<Double> adaptive(Axis a, float pixels, float spacing) {
        a.map(0, pixels);
        a.ticks.generate(a, pixels, spacing, 40, true);
        Ticks t = a.ticks();
        List<Double> out = new ArrayList<>();
        double lastPixel = Double.NaN;
        for (int i = 0; i < t.count(); i++) {
            assertThat(t.major(i)).isTrue();
            double v = t.value(i);
            out.add(v);
            assertThat(v).isBetween(a.range().min(), a.range().max());
            if (i > 0) {
                assertThat(v).as("ascending").isGreaterThan(t.value(i - 1));
            }
            double p = a.toPixel(v);
            if (lastPixel == lastPixel) {
                assertThat(Math.abs(p - lastPixel)).as("%s apart from the one before", v)
                    .isGreaterThanOrEqualTo(spacing - 1e-3);
            }
            lastPixel = p;
            // nice: a whole multiple of a power of ten at most a thousandth of the range (as fine as steps go)
            double resolution = Math.pow(10, Math.floor(Math.log10(a.range().max() - a.range().min())) - 3);
            double k = v / resolution;
            assertThat(Math.abs(k - Math.rint(k))).as("%s is a nice number", v).isLessThan(1e-4 * Math.max(1, Math.abs(k) * 1e-9));
            assertThat(t.label(i).length()).isPositive();
        }
        assertThat(t.step()).as("irregular").isZero();
        return out;
    }

    @Test
    void adaptiveTicksKeepTheSpacingOnTheScreen() {
        // the square root: finer ticks at the start, where it stretches
        List<Double> sqrt = adaptive(axis(Scale.SQRT, 0, 1000), 400, 40);
        assertThat(sqrt).contains(0.0).hasSizeGreaterThan(4);
        assertThat(sqrt.get(1) - sqrt.get(0)).isLessThan(sqrt.get(sqrt.size() - 1) - sqrt.get(sqrt.size() - 2));
        // pieces: the stretched first ten units get their own ticks, the squeezed rest only nice ones
        List<Double> pieces = adaptive(axis(Scale.piecewise(new double[] {0, 10, 100}, new double[] {0, 2, 3}), 0,
            100), 300, 40);
        assertThat(pieces).contains(0.0, 5.0, 10.0, 100.0);
        // a reversed log: ticks of the log
        Axis rev = axis(Scale.reversed(Scale.LOG), 1, 1e6);
        rev.map(0, 400);
        rev.ticks.generate(rev, 400, 40, 40, true);
        assertThat(rev.ticks().value(0)).isEqualTo(1);
        // a game's own scale, and random ranges of every adaptive scale
        assertThat(adaptive(axis(CUBE, -1000, 1000), 500, 50)).contains(0.0, 1000.0, -1000.0);
        SplittableRandom random = new SplittableRandom(3);
        Scale[] scales = {Scale.SQRT, Scale.power(2), Scale.power(0.3), CUBE,
            Scale.piecewise(new double[] {-1, 0, 1, 1000}, new double[] {0, 5, 6, 7})};
        for (int i = 0; i < 200; i++) {
            Scale s = scales[i % scales.length];
            double lo = (random.nextDouble() - 0.5) * Math.pow(10, random.nextInt(-3, 6));
            double hi = lo + Math.pow(10, random.nextInt(-4, 7)) * (0.1 + random.nextDouble());
            Axis a = axis(s, lo, hi);
            adaptive(a, 50 + random.nextInt(1000), 20 + random.nextInt(100));
        }
        // a tiny range beyond the resolution of doubles, and nothing to show: no ticks rather than wrong ones
        adaptive(axis(Scale.SQRT, 1e15, 1e15 + 1), 400, 40);
        adaptive(axis(Scale.SQRT, 1e300, 1.5e300), 400, 40);
        Ticks empty = new Ticks();
        empty.reserve(10);
        empty.adaptive(Scale.SQRT, 1, 1, 100, 10, 10);
        assertThat(empty.count()).isZero();
        empty.adaptive(Scale.LOG, -1, 1, 100, 10, 10);
        assertThat(empty.count()).isZero();
    }

    @Test
    void aScaleOfItsOwnMakesItsTicksWithTheMakers() {
        Scale quarters = new Scale() { // ticks of its own: quarters, a minor between
            @Override
            public double forward(double value) {
                return value;
            }

            @Override
            public double inverse(double transformed) {
                return transformed;
            }

            @Override
            public void ticks(Axis axis, Ticks ticks, double min, double max, float pixels, float spacing, int limit,
                boolean minors) {
                for (double v = Math.ceil(min * 4) / 4; v <= max; v += 0.25) {
                    ticks.addMajor(v).append(v).append(" q");
                    ticks.addMinor(v + 0.125);
                }
                ticks.step(0.25);
            }
        };
        Axis a = axis(quarters, 0, 1);
        a.ticks.generate(a, 400, 40, 40, true);
        assertThat(a.ticks().majors()).isEqualTo(5);
        assertThat(a.ticks().label(0).toString()).isEqualTo("0.0 q");
        assertThat(a.ticks().major(1)).isFalse();
        assertThat(a.format(new StringBuilder(), 0.5).toString()).as("the default text: a tenth of the step")
            .isEqualTo("0.5");
        Ticks small = new Ticks();
        small.reserve(1);
        for (int i = 0; i < 100; i++) {
            small.addMajor(i).append('x');
        }
        assertThat(small.full()).isTrue();
        assertThat(small.addMajor(1000).length()).as("a scratch label when full").isZero();
        // linear ticks for a scale that wants them
        Ticks lin = new Ticks();
        lin.reserve(10);
        lin.linear(0, 10, 100, 20, 10, false);
        assertThat(lin.majors()).isEqualTo(6);
    }

    @Test
    void adaptiveTicksAllocateNothing() {
        Axis a = axis(Scale.SQRT, 0, 1000);
        a.map(0, 400);
        a.ticks.generate(a, 400, 40, 40, true);
        AllocationAssertions.assertAllocationFree(() -> {
            a.range().set(3, 977);
            a.ticks.generate(a, 400, 40, 40, true);
            a.range().set(0, 1000);
            a.ticks.generate(a, 400, 40, 40, true);
        });
    }
}
