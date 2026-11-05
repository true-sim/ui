package com.truesim.ui.core;

/**
 * An element's value for assistive technology (design UI27): a field's text, a slider's or a progress bar's number.
 * Read only when an OS accessibility bridge takes the semantics tree (never per frame), set on the UI thread when a
 * screen reader or another assistive tool changes it. Controls give one to their element
 * ({@link UiStore#accessibleValue(int, AccessibleValue)}).
 */
public interface AccessibleValue {

    /** The value as text (announced as it is). Not called for a {@linkplain #password() password}. */
    String text();

    /** Whether assistive technology may not {@linkplain #set set} it. */
    default boolean readOnly() {
        return true;
    }

    /** Sets the value from {@code text} on the UI thread; returns false if it was refused (read only, not a number). */
    default boolean set(String text) {
        return false;
    }

    /** Whether the value is protected (a password): its text is never exposed. */
    default boolean password() {
        return false;
    }

    /**
     * {@code v} as a screen reader should announce it: whole numbers without a fraction, others with at most four
     * decimals and no trailing zeros, in the C locale ({@code 0.5}, {@code 12}, {@code -3.1416}).
     */
    @com.truesim.annotations.AllowAllocation("read when the semantics tree is taken, not per frame")
    static String number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return Double.toString(v);
        }
        double r = Math.rint(v * 10_000) / 10_000;
        if (r == Math.rint(r) && Math.abs(r) < 1e15) {
            return Long.toString((long) r);
        }
        String s = java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString();
        return s;
    }

    /** Parses {@code text} as a number (C locale, surrounding spaces allowed); NaN if it is none. */
    @com.truesim.annotations.AllowAllocation("an assistive tool setting a value, not per frame")
    static double parse(String text) {
        try {
            return Double.parseDouble(text.strip());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
