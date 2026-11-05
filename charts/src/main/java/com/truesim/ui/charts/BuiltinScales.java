package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import java.util.Arrays;

/** The scales {@link Scale} names. */
final class BuiltinScales {

    private BuiltinScales() {
    }

    /** {@link Scale#LINEAR}. */
    static final class Linear implements Scale {

        @Override
        public double forward(double value) {
            return value;
        }

        @Override
        public double inverse(double transformed) {
            return transformed;
        }

        @Override
        public boolean identity() {
            return true;
        }

        @Override
        public void ticks(Axis axis, Ticks ticks, double min, double max, float pixels, float spacing, int limit,
            boolean minors) {
            ticks.linear(min, max, pixels, spacing, limit, minors);
        }

        @Override
        public String toString() {
            return "LINEAR";
        }
    }

    /** {@link Scale#LOG}. */
    static final class Log implements Scale {

        @Override
        public double forward(double value) {
            return value > 0 ? Maths.log10(value) : Double.NaN;
        }

        @Override
        public double inverse(double transformed) {
            return Maths.pow(10, transformed);
        }

        @Override
        public double defaultMin() {
            return 1;
        }

        @Override
        public double defaultMax() {
            return 10;
        }

        @Override
        public void ticks(Axis axis, Ticks ticks, double min, double max, float pixels, float spacing, int limit,
            boolean minors) {
            ticks.log(this, min, max, pixels, spacing, minors);
        }

        @Override
        public void format(Axis axis, StringBuilder out, double value) {
            TickFormat.significant(out, value);
        }

        @Override
        public String toString() {
            return "LOG";
        }
    }

    /** {@link Scale#symlog(double)}. */
    static final class Symlog implements Scale {

        private final double constant;

        Symlog(double constant) {
            if (!(constant > 0) || Double.isInfinite(constant)) {
                throw new IllegalArgumentException("the symlog constant must be positive: " + constant);
            }
            this.constant = constant;
        }

        /** The linear half-width. */
        double constant() {
            return constant;
        }

        @Override
        public double forward(double value) {
            double t = Maths.log10(1 + Math.abs(value) / constant);
            return value < 0 ? -t : t;
        }

        @Override
        public double inverse(double transformed) {
            double v = constant * (Maths.pow(10, Math.abs(transformed)) - 1);
            return transformed < 0 ? -v : v;
        }

        @Override
        public void ticks(Axis axis, Ticks ticks, double min, double max, float pixels, float spacing, int limit,
            boolean minors) {
            ticks.symlog(this, constant, min, max, pixels, spacing, minors);
        }

        @Override
        public String toString() {
            return "SYMLOG(" + constant + ")";
        }
    }

    /** {@link Scale#TIME}. */
    static final class Time implements Scale {

        @Override
        public double forward(double value) {
            return value;
        }

        @Override
        public double inverse(double transformed) {
            return transformed;
        }

        @Override
        public boolean identity() {
            return true;
        }

        @Override
        public void ticks(Axis axis, Ticks ticks, double min, double max, float pixels, float spacing, int limit,
            boolean minors) {
            ticks.time(axis.timeOffset(), min, max, pixels, spacing, limit);
        }

        @Override
        public void format(Axis axis, StringBuilder out, double value) {
            double step = axis.ticks().step();
            TickFormat.time(out, value + axis.timeOffset(), step > 0 ? Math.max(1e-6, step / 10) : 1);
        }

        @Override
        public String toString() {
            return "TIME";
        }
    }

    /** {@link Scale#CATEGORY}. */
    static final class Category implements Scale {

        @Override
        public double forward(double value) {
            return value;
        }

        @Override
        public double inverse(double transformed) {
            return transformed;
        }

        @Override
        public boolean identity() {
            return true;
        }

        @Override
        public void ticks(Axis axis, Ticks ticks, double min, double max, float pixels, float spacing, int limit,
            boolean minors) {
            ticks.category(axis, min, max, pixels, spacing, limit);
        }

        @Override
        public void format(Axis axis, StringBuilder out, double value) {
            axis.categoryName(out, Math.rint(value));
        }

        @Override
        public String toString() {
            return "CATEGORY";
        }
    }

    /** {@link Scale#power(double)}. */
    static final class Power implements Scale {

        private final double exponent;
        private final double reciprocal;

        Power(double exponent) {
            if (!(exponent > 0) || Double.isInfinite(exponent)) {
                throw new IllegalArgumentException("the exponent must be positive: " + exponent);
            }
            this.exponent = exponent;
            this.reciprocal = 1 / exponent;
        }

        @Override
        public double forward(double value) {
            double t = Maths.pow(Math.abs(value), exponent);
            return value < 0 ? -t : t;
        }

        @Override
        public double inverse(double transformed) {
            double v = Maths.pow(Math.abs(transformed), reciprocal);
            return transformed < 0 ? -v : v;
        }

        @Override
        public String toString() {
            return "POWER(" + exponent + ")";
        }
    }

    /** {@link Scale#reversed(Scale)}. */
    static final class Reversed implements Scale {

        private final Scale scale;

        Reversed(Scale scale) {
            if (scale == null) {
                throw new IllegalArgumentException("no scale to reverse");
            }
            this.scale = scale;
        }

        /** The scale reversed. */
        Scale scale() {
            return scale;
        }

        @Override
        public double forward(double value) {
            return -scale.forward(value);
        }

        @Override
        public double inverse(double transformed) {
            return scale.inverse(-transformed);
        }

        @Override
        public double defaultMin() {
            return scale.defaultMin();
        }

        @Override
        public double defaultMax() {
            return scale.defaultMax();
        }

        @Override
        public void ticks(Axis axis, Ticks ticks, double min, double max, float pixels, float spacing, int limit,
            boolean minors) {
            scale.ticks(axis, ticks, min, max, pixels, spacing, limit, minors);
        }

        @Override
        public void format(Axis axis, StringBuilder out, double value) {
            scale.format(axis, out, value);
        }

        @Override
        public String toString() {
            return "REVERSED(" + scale + ")";
        }
    }

    /** {@link Scale#piecewise(double[], double[])}. */
    static final class Piecewise implements Scale {

        private final double[] values;
        private final double[] positions;

        @AllowAllocation("a scale is made when a chart is built")
        Piecewise(double[] values, double[] positions) {
            if (values.length < 2 || values.length != positions.length) {
                throw new IllegalArgumentException("a piecewise scale needs at least two values, each with a position");
            }
            for (int i = 0; i < values.length; i++) {
                if (!Double.isFinite(values[i]) || !Double.isFinite(positions[i])
                    || i > 0 && !(values[i] > values[i - 1] && positions[i] > positions[i - 1])) {
                    throw new IllegalArgumentException("values and positions must be finite and strictly increasing");
                }
            }
            this.values = values.clone();
            this.positions = positions.clone();
        }

        @Override
        public double forward(double value) {
            return map(values, positions, value);
        }

        @Override
        public double inverse(double transformed) {
            return map(positions, values, transformed);
        }

        @Override
        public double defaultMin() {
            return values[0];
        }

        @Override
        public double defaultMax() {
            return values[values.length - 1];
        }

        /** {@code v} in {@code from}'s pieces mapped to {@code to}'s: straight between them and beyond the ends. */
        private static double map(double[] from, double[] to, double v) {
            if (v != v) {
                return Double.NaN;
            }
            int n = from.length;
            int i = Arrays.binarySearch(from, v);
            if (i >= 0) {
                return to[i];
            }
            int hi = Math.min(Math.max(-i - 1, 1), n - 1); // the piece's upper end: the first or last piece outside
            int lo = hi - 1;
            return to[lo] + (v - from[lo]) * (to[hi] - to[lo]) / (from[hi] - from[lo]);
        }

        @Override
        public String toString() {
            return "PIECEWISE" + Arrays.toString(values) + "->" + Arrays.toString(positions);
        }
    }
}
