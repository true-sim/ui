package com.truesim.ui.charts;

import com.truesim.common.math.Maths;
import com.truesim.ui.controls.data.Format;

/**
 * Tick and tooltip labels as text without allocating: numbers with the decimals a tick step needs, scientific
 * notation for very large or small ones, powers of ten, and calendar dates and times (UTC plus an offset) by the
 * civil-date algorithms of Howard Hinnant (whole-number arithmetic, any year).
 */
public final class TickFormat {

    /** From this magnitude on, numbers show as {@code 1.5e7}. */
    static final double SCIENTIFIC_ABOVE = 1e7;

    private static final double[] POW10 = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13,
        1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

    private TickFormat() {
    }

    /** {@code 10^exponent}, exact (correctly rounded) for exponents from -22 to 22. */
    public static double pow10(int exponent) {
        if (exponent >= 0 && exponent < POW10.length) {
            return POW10[exponent];
        }
        if (exponent < 0 && -exponent < POW10.length) {
            return 1 / POW10[-exponent];
        }
        return Maths.pow(10, exponent);
    }

    /** {@code floor(log10(value))} for a positive value, robust against the logarithm's last-bit error. */
    public static int exponent(double value) {
        int e = (int) Math.floor(Maths.log10(value));
        if (pow10(e + 1) <= value) {
            e++;
        } else if (pow10(e) > value) {
            e--;
        }
        return e;
    }

    /** The decimals that tell ticks {@code step} apart (0 to 9). */
    public static int decimals(double step) {
        if (!(step > 0) || Double.isInfinite(step)) {
            return 0;
        }
        int e = exponent(step);
        double mantissa = step / pow10(e);
        int d = -e + (Math.abs(mantissa - Math.rint(mantissa)) > 1e-9 ? 1 : 0); // 2.5: one more
        return Math.max(0, Math.min(9, d));
    }

    /**
     * Appends {@code value} as a tick label of ticks {@code step} apart: plain with the decimals the step needs, or as
     * {@code m.mmme±x} when the numbers are very large or the step very small.
     */
    public static StringBuilder number(StringBuilder out, double value, double step) {
        double magnitude = Math.abs(value);
        if (value == 0 || magnitude < step * 1e-6) {
            return out.append('0');
        }
        if (value != value || Double.isInfinite(value)) {
            return Format.append(out, value, 0);
        }
        if (magnitude >= SCIENTIFIC_ABOVE || step < 1e-6) {
            return scientific(out, value, step);
        }
        return Format.append(out, value, decimals(step));
    }

    /** Appends {@code value} as {@code m.mmme±x} with the mantissa decimals that ticks {@code step} apart need. */
    public static StringBuilder scientific(StringBuilder out, double value, double step) {
        if (value != value || Double.isInfinite(value)) {
            return Format.append(out, value, 0);
        }
        if (value == 0) {
            return out.append('0');
        }
        int exponent = exponent(Math.abs(value));
        double mantissa = value / pow10(exponent);
        int d = step > 0 ? exponent - exponent(step) : 3;
        d = Math.max(0, Math.min(6, d));
        double rounded = Math.rint(Math.abs(mantissa) * pow10(d)) / pow10(d);
        if (rounded >= 10) { // rounding carried into the next power
            mantissa /= 10;
            exponent++;
        }
        Format.append(out, mantissa, d);
        out.append('e');
        return out.append(exponent);
    }

    /** Appends {@code 10^exponent} as a log axis label: plain from 0.0001 to 100000, else {@code 1e7}. */
    public static StringBuilder power(StringBuilder out, int exponent) {
        if (exponent >= -4 && exponent <= 5) {
            return Format.append(out, pow10(exponent), Math.max(0, -exponent));
        }
        return out.append("1e").append(exponent);
    }

    /** Appends {@code value} with three significant digits (log axes' tooltips). */
    public static StringBuilder significant(StringBuilder out, double value) {
        if (value == 0 || value != value || Double.isInfinite(value)) {
            return Format.append(out, value, 0);
        }
        double magnitude = Math.abs(value);
        int exponent = exponent(magnitude);
        if (magnitude >= SCIENTIFIC_ABOVE || magnitude < 1e-4) {
            return scientific(out, value, pow10(exponent - 2));
        }
        return Format.append(out, value, Math.max(0, Math.min(9, 2 - exponent)));
    }

    // ---------------------------------------------------------------- calendar

    /** Days from 1970-01-01 to the civil date {@code year-month-day} (proleptic Gregorian). */
    public static long daysFromCivil(long year, int month, int day) {
        long y = month <= 2 ? year - 1 : year;
        long era = Math.floorDiv(y, 400);
        long yoe = y - era * 400;
        long doy = (153L * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /**
     * The civil date of {@code days} since 1970-01-01, packed as {@code year << 9 | month << 5 | day} ({@link #year},
     * {@link #month}, {@link #day} unpack it).
     */
    public static long civilFromDays(long days) {
        long z = days + 719468;
        long era = Math.floorDiv(z, 146097);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        long y = yoe + era * 400 + (m <= 2 ? 1 : 0);
        return y << 9 | m << 5 | d;
    }

    /** The year of a packed civil date. */
    public static long year(long civil) {
        return civil >> 9;
    }

    /** The month (1 to 12) of a packed civil date. */
    public static int month(long civil) {
        return (int) (civil >> 5 & 15);
    }

    /** The day (1 to 31) of a packed civil date. */
    public static int day(long civil) {
        return (int) (civil & 31);
    }

    /** What a time label shows, by the size of the step between its ticks. */
    enum TimeUnit {
        /** Microseconds to milliseconds: {@code mm:ss.ffffff} with the decimals the step needs. */
        SUBSECOND,
        /** {@code HH:mm:ss}. */
        SECOND,
        /** {@code HH:mm}. */
        MINUTE,
        /** {@code MM-dd}. */
        DAY,
        /** {@code yyyy-MM}. */
        MONTH,
        /** {@code yyyy}. */
        YEAR
    }

    /** The label kind of ticks {@code step} seconds apart. */
    static TimeUnit unit(double step) {
        if (step < 1) {
            return TimeUnit.SUBSECOND;
        }
        if (step < 60) {
            return TimeUnit.SECOND;
        }
        if (step < 86_400) {
            return TimeUnit.MINUTE;
        }
        if (step < 28 * 86_400) {
            return TimeUnit.DAY;
        }
        if (step < 365 * 86_400) {
            return TimeUnit.MONTH;
        }
        return TimeUnit.YEAR;
    }

    /**
     * Appends the time {@code seconds} (since 1970-01-01 UTC, already shifted to local time) as a label of ticks
     * {@code step} seconds apart.
     */
    public static StringBuilder time(StringBuilder out, double seconds, double step) {
        if (seconds != seconds || Double.isInfinite(seconds)) {
            return Format.append(out, seconds, 0);
        }
        double whole = Math.floor(seconds);
        long s = (long) whole;
        long micros = (long) Math.rint((seconds - whole) * 1e6);
        if (micros >= 1_000_000) { // rounding reached the next second
            micros -= 1_000_000;
            s++;
        }
        long days = Math.floorDiv(s, 86_400L);
        int secondOfDay = (int) (s - days * 86_400L);
        switch (unit(step)) {
            case SUBSECOND -> {
                int d = Math.max(1, Math.min(6, decimals(step)));
                two(out, secondOfDay / 60 % 60).append(':');
                two(out, secondOfDay % 60).append('.');
                long digits = micros;
                for (int i = 6; i > d; i--) {
                    digits /= 10;
                }
                padded(out, digits, d);
            }
            case SECOND -> {
                two(out, secondOfDay / 3600).append(':');
                two(out, secondOfDay / 60 % 60).append(':');
                two(out, secondOfDay % 60);
            }
            case MINUTE -> {
                two(out, secondOfDay / 3600).append(':');
                two(out, secondOfDay / 60 % 60);
            }
            case DAY -> {
                long civil = civilFromDays(days);
                two(out, month(civil)).append('-');
                two(out, day(civil));
            }
            case MONTH -> {
                long civil = civilFromDays(days);
                out.append(year(civil)).append('-');
                two(out, month(civil));
            }
            default -> out.append(year(civilFromDays(days)));
        }
        return out;
    }

    private static StringBuilder two(StringBuilder out, int v) {
        if (v < 10) {
            out.append('0');
        }
        return out.append(v);
    }

    private static void padded(StringBuilder out, long v, int digits) {
        long limit = 1;
        for (int i = 1; i < digits; i++) {
            limit *= 10;
        }
        for (long p = limit; p > 1 && v < p; p /= 10) {
            out.append('0');
        }
        out.append(v);
    }
}
