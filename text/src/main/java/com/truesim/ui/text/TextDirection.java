package com.truesim.ui.text;

/** The base direction of a {@link TextBlock}'s paragraphs (UAX #9's paragraph level). */
public enum TextDirection {
    /** From each paragraph's first strong character (rules P2 and P3); left to right when there is none. */
    AUTO,
    /** Left to right. */
    LTR,
    /** Right to left. */
    RTL
}
