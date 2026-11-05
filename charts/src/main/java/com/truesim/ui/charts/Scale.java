package com.truesim.ui.charts;

/**
 * How an axis maps data to pixels and where it puts its ticks: a monotonic transform ({@link #forward}, with its
 * {@link #inverse}) the axis maps evenly onto its pixels, the ticks for a range ({@link #ticks}) and how a value is
 * written ({@link #format}). The built-in scales are constants and factories here; a game implements its own the same
 * way (ImPlot's transform callbacks): only {@code forward} and {@code inverse} are required, and the default ticks are
 * nice numbers placed adaptively, about {@code tick-spacing} pixels apart wherever the transform stretches or squeezes
 * the range ({@link Ticks#adaptive}).
 *
 * <pre>{@code
 * Scale cube = new Scale() {          // a user's scale: the cube root
 *     public double forward(double v) { return Math.cbrt(v); }
 *     public double inverse(double t) { return t * t * t; }
 * };
 * chart.addAxis("y2", Axis.Side.RIGHT, cube);
 * chart.x().scale(Scale.reversed(Scale.LOG));
 * }</pre>
 *
 * <p>The transform may be increasing or decreasing ({@link #reversed}: the range's maximum then lies at the axis's
 * start); it must be monotonic and finite over the values the axis shows ({@link #forward} gives NaN for values it
 * cannot show, as a logarithm does for 0). Scales are immutable, and the axis calls them every frame: they must not
 * allocate.
 */
public interface Scale {

    /** Evenly: ticks at nice numbers (1, 2, 5 times a power of ten). */
    Scale LINEAR = new BuiltinScales.Linear();
    /** By the logarithm (positive data only): ticks at powers of ten, minor ticks at 2 to 9 times them. */
    Scale LOG = new BuiltinScales.Log();
    /**
     * Symmetric logarithm with a linear half-width of 1 (see {@link #symlog(double)}; {@link Axis#symlogConstant}
     * changes an axis's constant).
     */
    Scale SYMLOG = new BuiltinScales.Symlog(1);
    /** Time: data in seconds since 1970-01-01 UTC (fractions down to microseconds); calendar ticks and labels. */
    Scale TIME = new BuiltinScales.Time();
    /** Categories: whole numbers 0, 1, 2... labelled by name ({@link Axis#categories}). */
    Scale CATEGORY = new BuiltinScales.Category();
    /** The square root (of the magnitude, keeping the sign): small values spread out. */
    Scale SQRT = power(0.5);

    /** The transformed value of data value {@code value} (NaN where the scale cannot show it). */
    double forward(double value);

    /** The data value of transformed value {@code transformed}: {@link #forward}'s inverse. */
    double inverse(double transformed);

    /** Whether {@link #forward} is the identity (the axis maps data straight, skipping the calls). */
    default boolean identity() {
        return false;
    }

    /** The range a new axis shows, and an axis whose range the scale cannot show falls back to: its minimum. */
    default double defaultMin() {
        return 0;
    }

    /** The maximum of {@link #defaultMin}'s range. */
    default double defaultMax() {
        return 1;
    }

    /**
     * Makes the ticks of {@code axis} for its range {@code min..max} over {@code pixels} pixels into {@code ticks}
     * (cleared): majors about {@code spacing} pixels apart, at most {@code limit} of them; minors if {@code minors}
     * asks and the scale has them. By default nice numbers placed adaptively ({@link Ticks#adaptive}), labelled as
     * {@link Ticks#adaptive} writes them.
     */
    default void ticks(Axis axis, Ticks ticks, double min, double max, float pixels, float spacing, int limit,
        boolean minors) {
        ticks.adaptive(this, min, max, pixels, spacing, limit);
    }

    /**
     * Appends {@code value} as a tooltip shows it on {@code axis} (one decimal finer than its ticks): by default a
     * number to a tenth of the ticks' step, or to three significant digits of the value when they have none.
     */
    default void format(Axis axis, StringBuilder out, double value) {
        double step = axis.ticks().step();
        double s = step > 0 ? step / 10 : Math.max(Math.abs(value) * 1e-3, 1e-12);
        TickFormat.number(out, value, s);
    }

    /**
     * The symmetric logarithm: linear within {@code ±constant} of zero, logarithmic beyond, both signs (ticks at zero
     * and signed powers of ten times the constant).
     */
    static Scale symlog(double constant) {
        return new BuiltinScales.Symlog(constant);
    }

    /**
     * A power of the magnitude, keeping the sign ({@code sign(v) |v|^exponent}): below 1 small values spread out (0.5:
     * the square root), above 1 large ones do. Ticks adaptive.
     */
    static Scale power(double exponent) {
        return new BuiltinScales.Power(exponent);
    }

    /**
     * {@code scale} running the other way: the range's maximum at the axis's start (left, or bottom); the same ticks
     * and labels. (For any scale; {@link Axis#inverted} does the same for an axis.)
     */
    static Scale reversed(Scale scale) {
        return scale instanceof BuiltinScales.Reversed r ? r.scale() : new BuiltinScales.Reversed(scale);
    }

    /**
     * Piecewise linear: data value {@code values[i]} lies at position {@code positions[i]} of the axis (any units;
     * both strictly increasing, at least two), straight between them and beyond the ends (a broken or compressed
     * axis: {@code piecewise({0, 10, 100}, {0, 2, 3})} gives the first ten units two thirds of the axis). Ticks
     * adaptive, so every piece gets its own nice step.
     */
    static Scale piecewise(double[] values, double[] positions) {
        return new BuiltinScales.Piecewise(values, positions);
    }
}
