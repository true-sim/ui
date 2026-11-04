package com.truesim.ui.controls.overlay;

/**
 * The side of its anchor an overlay prefers (style key {@code placement}): above, below, left or right of it, or at
 * its start or end side (left and right in left-to-right text, mirrored in right-to-left).
 */
public enum Placement {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
    START,
    END;

    private static final Placement[] ALL = values();

    /** The placement of an ordinal, without copying {@code values()}. */
    public static Placement of(int ordinal) {
        return ALL[ordinal];
    }

    /** The physical side: START and END resolved for the text direction. */
    public Placement physical(boolean rtl) {
        return switch (this) {
            case START -> rtl ? RIGHT : LEFT;
            case END -> rtl ? LEFT : RIGHT;
            default -> this;
        };
    }

    /** The side across the anchor: TOP for BOTTOM, LEFT for RIGHT (START and END are resolved first). */
    public Placement opposite() {
        return switch (this) {
            case TOP -> BOTTOM;
            case BOTTOM -> TOP;
            case LEFT -> RIGHT;
            case RIGHT -> LEFT;
            case START -> END;
            default -> START;
        };
    }

    /** Whether the overlay sits above or below its anchor (its cross axis is horizontal). */
    public boolean vertical() {
        return this == TOP || this == BOTTOM;
    }
}
