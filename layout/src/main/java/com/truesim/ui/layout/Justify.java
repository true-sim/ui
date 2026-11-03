package com.truesim.ui.layout;

/**
 * Distribution on the main axis ({@code justify-content}); same order as ui-style's {@code Justify}. The spacing
 * modes only spread positive free space; on overflow they start at the start (Yoga's behaviour).
 */
public enum Justify {
    START,
    END,
    CENTER,
    SPACE_BETWEEN,
    SPACE_AROUND,
    SPACE_EVENLY
}
