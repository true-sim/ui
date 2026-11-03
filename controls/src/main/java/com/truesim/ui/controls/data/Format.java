package com.truesim.ui.controls.data;

/**
 * Numbers into text without allocating (the JDK's double formatting is exact but may allocate): integers in full,
 * fractions to a fixed number of decimals with trailing zeros dropped, colours as hex. For renderers.
 */
public final class Format {

    private static final double[] POWERS = {1, 10, 100, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9};
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Format() {
    }

    /** Appends {@code value} with at most {@code decimals} (0 to 9) decimals, trailing zeros dropped. */
    public static StringBuilder append(StringBuilder out, double value, int decimals) {
        if (value != value) {
            return out.append("NaN");
        }
        if (value == Double.POSITIVE_INFINITY || value == Double.NEGATIVE_INFINITY) {
            return out.append(value > 0 ? "∞" : "-∞");
        }
        double scale = POWERS[decimals];
        double scaled = Math.rint(Math.abs(value) * scale);
        if (scaled >= 9.0e18) {
            // beyond long: digits and a decimal exponent
            double a = Math.abs(value);
            int exponent = 0;
            while (a >= 1e15) {
                a /= 10;
                exponent++;
            }
            if (value < 0) {
                out.append('-');
            }
            return out.append((long) Math.rint(a)).append('e').append(exponent);
        }
        long units = (long) scaled;
        long whole = units / (long) scale;
        long fraction = units % (long) scale;
        if (value < 0 && units != 0) {
            out.append('-');
        }
        out.append(whole);
        if (fraction != 0) {
            int digits = decimals;
            while (fraction % 10 == 0) {
                fraction /= 10;
                digits--;
            }
            out.append('.');
            for (long p = (long) POWERS[digits - 1]; p > fraction && p > 1; p /= 10) {
                out.append('0');
            }
            out.append(fraction);
        }
        return out;
    }

    /** Appends a colour's channels (0 to 1, sRGB) as {@code #rrggbbaa}. */
    public static StringBuilder hex(StringBuilder out, float r, float g, float b, float a) {
        out.append('#');
        channel(out, r);
        channel(out, g);
        channel(out, b);
        return channel(out, a);
    }

    private static StringBuilder channel(StringBuilder out, float v) {
        int c = (int) Math.rint(Math.max(0, Math.min(1, v)) * 255);
        return out.append(HEX[c >> 4]).append(HEX[c & 15]);
    }
}
