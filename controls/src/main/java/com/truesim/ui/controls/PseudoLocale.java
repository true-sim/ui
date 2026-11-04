package com.truesim.ui.controls;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Tunable;
import java.util.List;

/**
 * Pseudo-localisation (design stage 15): labels shown accented, longer and bracketed, so a layout's room for
 * translations can be checked without translations: {@code Save} becomes {@code [Šàvé Š]}. Accents show where text
 * is hard-coded or its font lacks glyphs; the expansion (a share of the length, {@link #EXPANSION}) shows truncation and
 * overflow (German and Finnish run 30 to 40 % longer than English); the brackets show where text is cut. Mirrored, the
 * text is also forced right to left, for a first look at RTL layouts. A setting ({@link #MODE}): the UI applies it to
 * every label when it changes; accessible names keep the real text.
 */
public final class PseudoLocale {

    /** Off, on, or on and mirrored right to left. */
    public static final Tunable MODE = Tunable.choice("ui.l10n.pseudo", List.of("off", "on", "mirrored"), "off",
        "Pseudo-localised labels to check layouts for translations: accented, longer, bracketed");
    /** How much longer pseudo-localised text is, as a share of its length. */
    public static final Tunable EXPANSION = Tunable.of("ui.l10n.pseudoExpansion", 0.35f, 0, 2,
        "How much longer pseudo-localised labels are (0.35: 35 %)");

    private static final String PLAIN = "AaCcDdEeGgIiLlNnOoRrSsUuYyZz";
    private static final String ACCENTED = "\u00C5\u00E0\u00C7\u00E7\u010E\u010F\u00C9\u00E9\u011C\u011D\u00CE\u00EE"
        + "\u013B\u013C\u00D1\u00F1\u00D6\u00F6\u0158\u0159\u0160\u0161\u00DC\u00FC\u00DD\u00FD\u017D\u017E";
    private static final char RLO = '\u202E';
    private static final char PDF = '\u202C';

    private PseudoLocale() {
    }

    /** Whether labels are pseudo-localised now. */
    public static boolean active() {
        return MODE.index() != 0;
    }

    /** The tunables, for an engine to register as settings. */
    @AllowAllocation("asked once")
    public static List<Tunable> tunables() {
        return List.of(MODE, EXPANSION);
    }

    /**
     * {@code text} pseudo-localised into {@code out} (cleared first) with the settings' expansion and mirroring;
     * returns {@code out}. Allocation-free once {@code out} has grown to the longest label.
     */
    public static StringBuilder apply(CharSequence text, StringBuilder out) {
        return apply(text, out, EXPANSION.get(), MODE.index() == 2);
    }

    /** {@code text} accented, expanded by {@code expansion} of its length, bracketed (and mirrored), into {@code out}. */
    public static StringBuilder apply(CharSequence text, StringBuilder out, float expansion, boolean mirrored) {
        out.setLength(0);
        if (mirrored) {
            out.append(RLO);
        }
        out.append('[');
        int start = out.length();
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int at = PLAIN.indexOf(c);
            out.append(at >= 0 ? ACCENTED.charAt(at) : c);
            if (!Character.isWhitespace(c)) {
                letters++;
            }
        }
        int end = out.length();
        int pad = (int) Math.ceil(letters * expansion);
        if (pad > 0) {
            // the text's own letters again: about as wide as the letters a translation adds
            out.append(' ');
            for (int i = 0, k = start; i < pad; k++) {
                if (k == end) {
                    k = start;
                }
                char c = out.charAt(k);
                if (!Character.isWhitespace(c)) {
                    out.append(c);
                    i++;
                }
            }
        }
        out.append(']');
        if (mirrored) {
            out.append(PDF);
        }
        return out;
    }
}
