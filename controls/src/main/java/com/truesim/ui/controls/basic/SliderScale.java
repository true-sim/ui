package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;

/**
 * How a slider (or a dragged number) maps its values to positions: {@link #LINEAR}, {@link #logarithmic} (ranges of
 * positive values spanning orders of magnitude: a frequency, an exposure, a distance) or {@link #symlog} (ranges
 * crossing zero: logarithmic away from it, linear near it). A scale maps a value to a coordinate ({@link #forward}),
 * linear in position; the slider's thumb, ticks, drags and keyboard steps work in that coordinate, so a step of the
 * arrow keys moves the same distance anywhere on the track.
 *
 * <pre>{@code
 * Slider freq = controls.slider("Frequency", hz, 20, 20_000).scale(SliderScale.logarithmic(10)).scaleTicks();
 * Slider pan = controls.slider("Offset", offset, -1000, 1000).scale(SliderScale.symlog(1, 10));
 * }</pre>
 */
public abstract sealed class SliderScale {

    /** Values evenly along the track. */
    public static final SliderScale LINEAR = new Linear();

    private SliderScale() {
    }

    /**
     * Logarithmic: each power of {@code base} takes the same length (the range must be positive); ticks fall on the
     * powers of the base.
     */
    @AllowAllocation("scales are made when a control is configured")
    public static SliderScale logarithmic(double base) {
        return new Log(base);
    }

    /**
     * Symmetric logarithmic, for ranges crossing zero: {@code sign(v) * log_base(1 + |v| / linear)}, about linear within
     * {@code linear} of zero and logarithmic beyond; ticks fall on zero and on plus and minus the powers of the base
     * from {@code linear} out.
     */
    @AllowAllocation("scales are made when a control is configured")
    public static SliderScale symlog(double linear, double base) {
        return new SymLog(linear, base);
    }

    /** The coordinate of {@code value}: linear in position along the track. */
    public abstract double forward(double value);

    /** The value at coordinate {@code t} (the inverse of {@link #forward}). */
    public abstract double inverse(double t);

    /** Throws if {@code min..max} cannot be shown on this scale (a logarithmic one needs positive values). */
    public void check(double min, double max) {
        if (!(max > min)) {
            throw new IllegalArgumentException("the maximum must exceed the minimum: " + min + ".." + max);
        }
    }

    /** Where {@code value} is along {@code min..max}: 0 to 1 (clamped). */
    public final double fraction(double value, double min, double max) {
        double a = forward(min);
        double f = (forward(value) - a) / (forward(max) - a);
        return f != f ? 0 : Math.max(0, Math.min(1, f));
    }

    /** The value at {@code fraction} (0 to 1) of {@code min..max}. */
    public final double value(double fraction, double min, double max) {
        double a = forward(min);
        double v = inverse(a + Math.max(0, Math.min(1, fraction)) * (forward(max) - a));
        return Math.max(min, Math.min(max, v));
    }

    /**
     * The scale's natural tick values within {@code min..max} (ends included) into {@code out}; returns how many
     * (at most {@code out.length}). Linear: none (give a count instead).
     */
    public int ticks(double min, double max, double[] out) {
        return 0;
    }

    /** Whether values are evenly spaced (positions and values are proportional). */
    public boolean linear() {
        return false;
    }

    private static int add(double[] out, int n, double v) {
        if (n < out.length) {
            out[n] = v;
            return n + 1;
        }
        return n;
    }

    /** Values evenly along the track. */
    private static final class Linear extends SliderScale {
        @Override
        public double forward(double value) {
            return value;
        }

        @Override
        public double inverse(double t) {
            return t;
        }

        @Override
        public boolean linear() {
            return true;
        }

        @Override
        public String toString() {
            return "linear";
        }
    }

    /** Logarithmic. */
    private static final class Log extends SliderScale {
        private final double base;
        private final double logBase;

        Log(double base) {
            if (!(base > 1)) {
                throw new IllegalArgumentException("a logarithmic base must exceed 1: " + base);
            }
            this.base = base;
            this.logBase = Math.log(base);
        }

        @Override
        public double forward(double value) {
            return Math.log(Math.max(value, Double.MIN_NORMAL)) / logBase;
        }

        @Override
        public double inverse(double t) {
            return Math.exp(t * logBase);
        }

        @Override
        public void check(double min, double max) {
            super.check(min, max);
            if (!(min > 0)) {
                throw new IllegalArgumentException("a logarithmic scale needs a positive range: " + min + ".." + max);
            }
        }

        @Override
        public int ticks(double min, double max, double[] out) {
            int n = 0;
            for (double k = Math.ceil(forward(min) - 1e-9); k <= forward(max) + 1e-9; k++) {
                n = add(out, n, inverse(k));
            }
            return n;
        }

        @Override
        public String toString() {
            return "log" + base;
        }
    }

    /** Symmetric logarithmic. */
    private static final class SymLog extends SliderScale {
        private final double linear;
        private final double base;
        private final double logBase;

        SymLog(double linear, double base) {
            if (!(base > 1) || !(linear > 0)) {
                throw new IllegalArgumentException("symlog needs a base over 1 and a positive linear range: " + base
                    + ", " + linear);
            }
            this.linear = linear;
            this.base = base;
            this.logBase = Math.log(base);
        }

        @Override
        public double forward(double value) {
            return Math.signum(value) * Math.log1p(Math.abs(value) / linear) / logBase;
        }

        @Override
        public double inverse(double t) {
            return Math.signum(t) * linear * Math.expm1(Math.abs(t) * logBase);
        }

        @Override
        public int ticks(double min, double max, double[] out) {
            int n = 0;
            double first = Math.pow(base, Math.ceil(Math.log(linear) / logBase - 1e-9));
            int count = 0;
            for (double v = first; v <= -min * (1 + 1e-9); v *= base) {
                count++;
            }
            for (int i = count - 1; i >= 0; i--) { // the negative side, outermost first
                n = add(out, n, -first * Math.pow(base, i));
            }
            if (min <= 0 && max >= 0) {
                n = add(out, n, 0);
            }
            for (double v = first; v <= max * (1 + 1e-9); v *= base) {
                if (v >= min) {
                    n = add(out, n, v);
                }
            }
            return n;
        }

        @Override
        public String toString() {
            return "symlog" + base + "/" + linear;
        }
    }
}
