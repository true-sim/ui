package com.truesim.ui.profiler;

import com.truesim.ui.controls.data.Format;

/** Durations, sizes and counts as text, appended without allocating. */
public final class Units {

    private Units() {
    }

    /** Appends {@code nanos} in the unit that suits it: {@code 850 ns}, {@code 12.4 us}, {@code 3.21 ms}, {@code 1.5 s}. */
    public static StringBuilder duration(StringBuilder out, long nanos) {
        long a = Math.abs(nanos);
        if (a < 1_000) {
            return out.append(nanos).append(" ns");
        }
        if (a < 1_000_000) {
            return Format.append(out, nanos / 1e3, a < 100_000 ? 1 : 0).append(" us");
        }
        if (a < 1_000_000_000) {
            return Format.append(out, nanos / 1e6, a < 10_000_000 ? 2 : a < 100_000_000 ? 1 : 0).append(" ms");
        }
        return Format.append(out, nanos / 1e9, 2).append(" s");
    }

    /**
     * Appends time {@code nanos} as a ruler's label for ticks {@code step} nanoseconds apart: seconds for steps of a
     * second or more, else milliseconds, with the decimals the step needs ({@code 3.5 s}, {@code 3922 ms},
     * {@code 3922.35 ms}).
     */
    public static StringBuilder time(StringBuilder out, long nanos, double step) {
        if (step >= 1e9) {
            int d = (int) Math.max(0, Math.min(3, -Math.floor(com.truesim.common.math.Maths.log10(step / 1e9))));
            return Format.append(out, nanos / 1e9, d).append(" s");
        }
        int d = (int) Math.max(0, Math.min(6, -Math.floor(com.truesim.common.math.Maths.log10(step / 1e6))));
        return Format.append(out, nanos / 1e6, d).append(" ms");
    }

    /** Appends {@code millis} with {@code decimals} decimals and no unit. */
    public static StringBuilder millis(StringBuilder out, double millis, int decimals) {
        return Format.append(out, millis, decimals);
    }

    /** Appends {@code value} of {@code unit}: bytes as KiB, MiB or GiB, percentages with one decimal, else plain. */
    public static StringBuilder value(StringBuilder out, double value, String unit) {
        if (value != value) {
            return out.append('-');
        }
        if ("bytes".equals(unit)) {
            double a = Math.abs(value);
            if (a < 1024) {
                return Format.append(out, value, 0).append(" B");
            }
            if (a < 1024 * 1024) {
                return Format.append(out, value / 1024, 1).append(" KiB");
            }
            if (a < 1024.0 * 1024 * 1024) {
                return Format.append(out, value / (1024 * 1024), 1).append(" MiB");
            }
            return Format.append(out, value / (1024.0 * 1024 * 1024), 2).append(" GiB");
        }
        if ("%".equals(unit)) {
            return Format.append(out, value, 1).append(" %");
        }
        double a = Math.abs(value);
        Format.append(out, value, a >= 100 || value == Math.rint(value) ? 0 : a >= 10 ? 1 : 2);
        return unit.isEmpty() ? out : out.append(' ').append(unit);
    }
}
