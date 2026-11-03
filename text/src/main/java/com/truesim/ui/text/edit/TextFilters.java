package com.truesim.ui.text.edit;

import com.truesim.annotations.AllowAllocation;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Common {@link TextFilter}s (what may be typed) and validators (whether the whole text is acceptable: a control
 * shows {@code :invalid} when not).
 */
@AllowAllocation("filters run when text is typed or pasted, not per frame")
public final class TextFilters {

    private TextFilters() {
    }

    /** Only characters matching {@code allowed} (a character class such as {@code [0-9a-fA-F]}); others dropped. */
    public static TextFilter chars(String allowed) {
        Pattern p = Pattern.compile(allowed);
        return (text, start, end, value) -> {
            StringBuilder kept = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); ) {
                int cp = Character.codePointAt(value, i);
                String one = new String(Character.toChars(cp));
                if (p.matcher(one).matches()) {
                    kept.append(one);
                }
                i += Character.charCount(cp);
            }
            return kept;
        };
    }

    /** Refuses an edit whose result does not match {@code partial} (a pattern every prefix of a valid text matches). */
    public static TextFilter pattern(String partial) {
        Pattern p = Pattern.compile(partial);
        return (text, start, end, value) -> p.matcher(result(text, start, end, value)).matches() ? value : null;
    }

    /**
     * A number as typed: an optional minus sign (if {@code negative}), digits and, if {@code decimals} is above 0, a
     * point (or a comma, taken as a point) with at most that many digits after it. Anything else in typed text is
     * dropped; an edit that would leave no valid prefix of a number is refused.
     */
    public static TextFilter number(boolean negative, int decimals) {
        return (text, start, end, value) -> {
            StringBuilder kept = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == ',' && decimals > 0) {
                    c = '.';
                }
                if (c >= '0' && c <= '9' || c == '-' && negative || c == '.' && decimals > 0) {
                    kept.append(c);
                }
            }
            String r = result(text, start, end, kept);
            return numberPrefix(r, negative, decimals) ? kept : null;
        };
    }

    private static boolean numberPrefix(String s, boolean negative, int decimals) {
        int i = 0;
        if (i < s.length() && s.charAt(i) == '-') {
            if (!negative) {
                return false;
            }
            i++;
        }
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            int digits = 0;
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
                digits++;
            }
            if (digits > decimals) {
                return false;
            }
        }
        return i == s.length();
    }

    /** The text after replacing {@code [start, end)} with {@code value}. */
    public static String result(CharSequence text, int start, int end, CharSequence value) {
        return new StringBuilder(text.length() - (end - start) + value.length()).append(text, 0, start).append(value)
            .append(text, end, text.length()).toString();
    }

    /** Valid when the whole text matches {@code regex}. */
    public static Predicate<CharSequence> matches(String regex) {
        Pattern p = Pattern.compile(regex);
        return text -> p.matcher(text).matches();
    }

    /** Valid when the text is a number in {@code [min, max]} (an empty text is invalid unless {@code optional}). */
    public static Predicate<CharSequence> range(double min, double max, boolean optional) {
        return text -> {
            if (text.isEmpty()) {
                return optional;
            }
            double v = parse(text);
            return !Double.isNaN(v) && v >= min && v <= max;
        };
    }

    /** The number in {@code text} (a comma taken as a point), or NaN. */
    public static double parse(CharSequence text) {
        String s = text.toString().trim().replace(',', '.');
        if (s.isEmpty() || s.equals("-") || s.equals(".") || s.equals("-.")) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
