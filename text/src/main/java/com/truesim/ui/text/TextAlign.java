package com.truesim.ui.text;

/**
 * Horizontal alignment of a {@link TextBlock}'s lines in its box. Start and end follow the paragraph's direction: start
 * is the left edge of a left-to-right paragraph and the right edge of a right-to-left one (the style module's
 * {@code TextAlign} maps one to one).
 */
public enum TextAlign {
    /** The edge where the paragraph begins. */
    START,
    /** Centred. */
    CENTER,
    /** The edge where the paragraph ends. */
    END;

    /** The share of a line's free space left of it: 0 hugs the left edge, 1 the right. */
    float leftShare(boolean rightToLeft) {
        return switch (this) {
            case START -> rightToLeft ? 1 : 0;
            case CENTER -> 0.5f;
            case END -> rightToLeft ? 0 : 1;
        };
    }
}
