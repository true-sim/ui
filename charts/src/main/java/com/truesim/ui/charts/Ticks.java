package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;

/**
 * The ticks of one axis for its current range and length: values in data units, which are major (labelled, with a
 * grid line) and which minor, and the majors' labels as text. Generated again only when the range, the length or the
 * spacing changed; generating allocates nothing once the buffers have grown to the tick limit.
 *
 * <ul>
 * <li>{@link Scale#LINEAR}: majors at multiples of a nice step (1, 2 or 5 times a power of ten) chosen so that majors
 * lie about {@code spacing} pixels apart; four or five minors between them.
 * <li>{@link Scale#LOG}: majors at powers of ten (every n-th one when decades are narrow), minors at 2 to 9 times them;
 * a range within about one decade gets linear ticks.
 * <li>{@link Scale#SYMLOG}: zero and the signed powers of ten times the constant, thinned to the spacing; a range
 * within the linear part gets linear ticks.
 * <li>{@link Scale#TIME}: calendar steps (microseconds to seconds as nice numbers, then 1, 2, 5, 10, 15 and 30 seconds,
 * minutes and hours, days, weeks, 1, 2, 3 and 6 months, nice numbers of years), aligned to local time.
 * <li>{@link Scale#CATEGORY}: whole numbers in nice steps, labelled by their names.
 * </ul>
 */
public final class Ticks {

    private static final double[] SECOND_STEPS = {1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200,
        10_800, 21_600, 43_200, 86_400, 172_800, 604_800};
    private static final double[] MONTH_STEPS = {1, 2, 3, 6};
    private static final double MONTH = 30.44 * 86_400;
    private static final double YEAR = 365.25 * 86_400;

    private double[] values = new double[0];
    private boolean[] major = new boolean[0];
    private StringBuilder[] labels = new StringBuilder[0];
    private int count;
    private int majors;
    private double step;
    private int capacity;

    /** How many ticks there are, majors and minors. */
    public int count() {
        return count;
    }

    /** How many majors there are. */
    public int majors() {
        return majors;
    }

    /** Tick {@code i}'s value in data units. */
    public double value(int i) {
        return values[i];
    }

    /** Whether tick {@code i} is major (labelled). */
    public boolean major(int i) {
        return major[i];
    }

    /** Tick {@code i}'s label (empty for a minor). */
    public CharSequence label(int i) {
        return labels[i];
    }

    /** The step between majors in the scale's units (seconds for time, decades for log; 0 when irregular). */
    public double step() {
        return step;
    }

    /** Room for {@code limit} majors and their minors; returns whether the buffers changed. */
    @AllowAllocation("the buffers grow when the tick limit is raised")
    void reserve(int limit) {
        int n = Math.max(4, limit) * 10 + 2;
        if (n != capacity) {
            capacity = n;
            values = new double[n];
            major = new boolean[n];
            labels = new StringBuilder[n];
            for (int i = 0; i < n; i++) {
                labels[i] = new StringBuilder(16);
            }
        }
    }

    /** Clears the ticks. */
    void clear() {
        count = 0;
        majors = 0;
        step = 0;
    }

    /**
     * Ticks for {@code axis} at its current range over {@code pixels} pixels, majors at least {@code spacing} pixels
     * apart, at most {@code limit} majors; {@code minors}: whether to make minor ticks.
     */
    void generate(Axis axis, float pixels, float spacing, int limit, boolean minors) {
        reserve(limit);
        clear();
        double min = axis.range().min();
        double max = axis.range().max();
        if (!(pixels > 0) || !(max > min)) {
            return;
        }
        double target = Math.max(1, Math.min(limit, pixels / Math.max(1, spacing)));
        switch (axis.scale()) {
            case LINEAR -> linear(min, max, target, minors, false);
            case CATEGORY -> category(axis, min, max, target);
            case LOG -> log(axis, min, max, pixels, spacing, minors);
            case SYMLOG -> symlog(axis, min, max, pixels, spacing, minors);
            case TIME -> time(axis, min, max, target);
            default -> throw new IllegalStateException();
        }
    }

    /**
     * The nice step (1, 2 or 5 times a power of ten) nearest {@code raw} on a log scale (so the tick count comes
     * closest to the one asked for: 2.2 gives 2, 3.3 gives 5).
     */
    public static double niceStep(double raw) {
        if (!(raw > 0) || Double.isInfinite(raw)) {
            return 1;
        }
        int e = TickFormat.exponent(raw);
        double p = TickFormat.pow10(e);
        double norm = raw / p;
        // the geometric means of neighbouring steps: sqrt(2), sqrt(10), sqrt(50)
        double nice = norm < 1.4142135623730951 ? 1 : norm < 3.1622776601683795 ? 2 : norm < 7.0710678118654755 ? 5
            : 10;
        return nice * p;
    }

    /** The nice step at or above {@code raw} (where fewer is better than crowded: every n-th decade). */
    static double niceStepUp(double raw) {
        if (!(raw > 0) || Double.isInfinite(raw)) {
            return 1;
        }
        int e = TickFormat.exponent(raw);
        double p = TickFormat.pow10(e);
        double norm = raw / p;
        double nice = norm <= 1 + 1e-9 ? 1 : norm <= 2 + 1e-9 ? 2 : norm <= 5 + 1e-9 ? 5 : 10;
        return nice * p;
    }

    /** Of {@code candidates} (ascending), the one nearest {@code raw} on a log scale. */
    private static double nearest(double[] candidates, double raw) {
        double best = candidates[0];
        double bestD = Double.POSITIVE_INFINITY;
        for (double c : candidates) {
            double d = Math.abs(c > raw ? c / raw : raw / c);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    private void add(double value, boolean isMajor) {
        if (count == values.length) {
            return;
        }
        values[count] = value;
        major[count] = isMajor;
        labels[count].setLength(0);
        if (isMajor) {
            majors++;
        }
        count++;
    }

    private void linear(double min, double max, double target, boolean minors, boolean whole) {
        double s = niceStep((max - min) / target);
        if (whole) {
            s = Math.max(1, Math.rint(s));
        }
        step = s;
        double first = Math.ceil(min / s - 1e-9);
        double last = Math.floor(max / s + 1e-9);
        if (!(last - first <= values.length) || first + 1 == first || last - first < 0) {
            return; // beyond the resolution of doubles: no ticks rather than a wrong count
        }
        int sub = minors ? subdivisions(s) : 1;
        double minor = s / sub;
        // values rounded to the minors' decimals: 3 x 0.2 is 0.6, not 0.6000000000000001
        int d = TickFormat.decimals(minor);
        double scaleBack = d <= 15 ? TickFormat.pow10(d) : 0;
        // minors before the first major
        if (sub > 1) {
            for (int j = sub - 1; j >= 1; j--) {
                double v = round((first - 1) * s + j * minor, scaleBack);
                if (v >= min - minor * 1e-9) {
                    add(v, false);
                }
            }
        }
        for (int t = 0, n = (int) (last - first); t <= n && count < values.length; t++) {
            double k = first + t;
            double v = round(k * s, scaleBack);
            if (Math.abs(v) < s * 1e-9) {
                v = 0;
            }
            add(v, true);
            TickFormat.number(labels[count - 1], v, s);
            for (int j = 1; j < sub; j++) {
                double m = round(v + j * minor, scaleBack);
                if (m > max + minor * 1e-9) {
                    break;
                }
                add(m, false);
            }
        }
    }

    private static double round(double v, double scale) {
        return scale > 0 && Math.abs(v * scale) < 1e15 ? Math.rint(v * scale) / scale : v;
    }

    private static int subdivisions(double step) {
        double mantissa = step / TickFormat.pow10(TickFormat.exponent(step));
        return Math.abs(mantissa - 2) < 1e-9 ? 4 : 5;
    }

    private void category(Axis axis, double min, double max, double target) {
        int n = axis.categories().size();
        if (n > 0) { // ticks at the categories only
            min = Math.max(min, 0);
            max = Math.min(max, n - 1);
            if (max < min) {
                return;
            }
        }
        linear(min, max, target, false, true);
        for (int i = 0; i < count; i++) {
            labels[i].setLength(0);
            axis.categoryName(labels[i], values[i]);
        }
    }

    private void log(Axis axis, double min, double max, float pixels, float spacing, boolean minors) {
        double lo = axis.forward(min);
        double hi = axis.forward(max);
        if (hi - lo < 1.2 || lo != lo) { // within a decade: plain numbers read better
            linear(Math.max(min, Double.MIN_NORMAL), max, Math.max(1, pixels / spacing), false, false);
            step = 0;
            return;
        }
        double perDecade = pixels / (hi - lo);
        int every = (int) niceStepUp(Math.max(1, spacing / perDecade));
        step = every;
        int first = (int) Math.ceil(lo - 1e-9);
        int last = (int) Math.floor(hi + 1e-9);
        boolean showMinors = minors && every == 1 && perDecade >= spacing * 0.5f;
        for (int k = first - 1; k <= last; k++) {
            double decade = TickFormat.pow10(k);
            if (k >= first && Math.floorMod(k, every) == 0) {
                add(decade, true);
                TickFormat.power(labels[count - 1], k);
            } else if (k >= first && minors && every > 1) {
                add(decade, false);
            }
            if (showMinors) {
                for (int m = 2; m <= 9; m++) {
                    double v = m * decade;
                    if (v >= min && v <= max) {
                        add(v, false);
                    }
                }
            }
        }
    }

    private void symlog(Axis axis, double min, double max, float pixels, float spacing, boolean minors) {
        double lo = axis.forward(min);
        double hi = axis.forward(max);
        double c = axis.symlogConstant();
        if (hi - lo < 1.5) {
            linear(min, max, Math.max(1, pixels / spacing), minors, false);
            step = 0;
            return;
        }
        double perUnit = pixels / (hi - lo);
        step = 0;
        // zero, then the signed powers of ten times the constant outwards, each at least the spacing from the last
        if (min <= 0 && max >= 0) {
            add(0, true);
            labels[count - 1].append('0');
        }
        for (int sign = 1; sign >= -1; sign -= 2) {
            double lastT = min <= 0 && max >= 0 ? 0 : Double.NaN;
            for (int k = 0; k < 400; k++) {
                double v = sign * c * TickFormat.pow10(k);
                if (Double.isInfinite(v)) {
                    break;
                }
                double t = axis.forward(v);
                if (v > max || v < min) {
                    if (sign > 0 ? v > max : v < min) {
                        break;
                    }
                    continue;
                }
                if (lastT == lastT && Math.abs(t - lastT) * perUnit < spacing) {
                    if (minors) {
                        add(v, false);
                    }
                    continue;
                }
                add(v, true);
                TickFormat.number(labels[count - 1], v, Math.abs(v));
                lastT = t;
            }
        }
    }

    private void time(Axis axis, double min, double max, double target) {
        double offset = axis.timeOffset();
        double raw = (max - min) / target;
        if (raw < 1) {
            linear(min + offset, max + offset, target, false, false);
            for (int i = 0; i < count; i++) {
                values[i] -= offset;
                labels[i].setLength(0);
                TickFormat.time(labels[i], values[i] + offset, step);
            }
            return;
        }
        if (raw <= SECOND_STEPS[SECOND_STEPS.length - 1] * 2) {
            double s = nearest(SECOND_STEPS, raw);
            step = s;
            double first = Math.ceil((min + offset) / s - 1e-9);
            double last = Math.floor((max + offset) / s + 1e-9);
            if (!(last - first <= values.length) || first + 1 == first) {
                return;
            }
            for (int j = 0, n = (int) (last - first); j <= n && count < values.length; j++) {
                double local = (first + j) * s;
                add(local - offset, true);
                TickFormat.time(labels[count - 1], local, s);
            }
            return;
        }
        if (raw <= 9 * MONTH) {
            int months = (int) nearest(MONTH_STEPS, raw / MONTH);
            step = months * MONTH;
            long civil = TickFormat.civilFromDays(Math.floorDiv((long) Math.floor(min + offset), 86_400L));
            long monthIndex = TickFormat.year(civil) * 12 + TickFormat.month(civil) - 1;
            monthIndex = Math.floorDiv(monthIndex, months) * months;
            for (int guard = 0; guard < values.length; guard++, monthIndex += months) {
                long y = Math.floorDiv(monthIndex, 12L);
                int m = (int) Math.floorMod(monthIndex, 12L) + 1;
                double local = TickFormat.daysFromCivil(y, m, 1) * 86_400.0;
                double v = local - offset;
                if (v > max) {
                    break;
                }
                if (v >= min) {
                    add(v, true);
                    TickFormat.time(labels[count - 1], local, step);
                }
            }
            return;
        }
        int years = (int) Math.max(1, niceStep(raw / YEAR));
        step = years * YEAR;
        long civil = TickFormat.civilFromDays(Math.floorDiv((long) Math.floor(min + offset), 86_400L));
        long year = Math.floorDiv(TickFormat.year(civil), years) * years;
        for (int guard = 0; guard < values.length; guard++, year += years) {
            double local = TickFormat.daysFromCivil(year, 1, 1) * 86_400.0;
            double v = local - offset;
            if (v > max) {
                break;
            }
            if (v >= min) {
                add(v, true);
                TickFormat.time(labels[count - 1], local, step);
            }
        }
    }
}
