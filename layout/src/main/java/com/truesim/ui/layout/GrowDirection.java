package com.truesim.ui.layout;

/**
 * Which way an anchored element grows when its anchors and offsets leave it smaller than its minimum size (Godot's
 * {@code grow_horizontal} and {@code grow_vertical}).
 */
public enum GrowDirection {
    /** Towards the end (right or down): the start edge stays. */
    END,
    /** Towards the start: the end edge stays. */
    BEGIN,
    /** Both ways from the centre. */
    BOTH
}
