package com.truesim.ui.core.input;

/**
 * The pointer's shape over an element (style key {@code cursor}), read by the engine for the mouse through
 * {@code Interaction.cursor(pointerId)}.
 */
public enum Cursor {
    /** The parent's cursor; the default arrow at the root. */
    AUTO,
    DEFAULT,
    /** A hand: something to click. */
    POINTER,
    /** An I-beam: text. */
    TEXT,
    MOVE,
    GRAB,
    GRABBING,
    NOT_ALLOWED,
    RESIZE_EW,
    RESIZE_NS,
    RESIZE_NESW,
    RESIZE_NWSE,
    CROSSHAIR,
    WAIT,
    /** No cursor drawn. */
    NONE
}
