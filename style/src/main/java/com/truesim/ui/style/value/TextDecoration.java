package com.truesim.ui.style.value;

/** Lines drawn with text, in its colour: CSS's {@code text-decoration-line}. */
public enum TextDecoration {
    NONE,
    UNDERLINE,
    LINE_THROUGH,
    /** Both an underline and a line through. */
    UNDERLINE_LINE_THROUGH;

    /** Whether an underline is drawn. */
    public boolean underline() {
        return this == UNDERLINE || this == UNDERLINE_LINE_THROUGH;
    }

    /** Whether a line through is drawn. */
    public boolean lineThrough() {
        return this == LINE_THROUGH || this == UNDERLINE_LINE_THROUGH;
    }
}
