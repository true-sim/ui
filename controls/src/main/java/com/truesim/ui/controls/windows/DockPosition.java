package com.truesim.ui.controls.windows;

/** Where a panel docks relative to a pane (or the whole dock space): beside it on a side, or into its tabs. */
public enum DockPosition {
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    CENTER;

    /** Whether docking here splits along a row (left or right). */
    public boolean row() {
        return this == LEFT || this == RIGHT;
    }

    /** Whether the new pane goes first (left or top). */
    public boolean first() {
        return this == LEFT || this == TOP;
    }

    /** The zone guide's id for this position: {@code left}, {@code right}, {@code top}, {@code bottom}, {@code center}. */
    public String key() {
        return switch (this) {
            case LEFT -> "left";
            case RIGHT -> "right";
            case TOP -> "top";
            case BOTTOM -> "bottom";
            default -> "center";
        };
    }
}
