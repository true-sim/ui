package com.truesim.ui.style.value;

/**
 * Godot-style anchors for HUDs: each edge of the element as a fraction of its parent (0 the parent's left or top edge,
 * 1 its right or bottom edge); the {@code offsets} key moves each edge from its anchor in panel units.
 */
public record Anchors(float left, float top, float right, float bottom) {

    /** The top left corner. */
    public static final Anchors TOP_LEFT = new Anchors(0, 0, 0, 0);
    /** Stretched over the whole parent. */
    public static final Anchors FULL = new Anchors(0, 0, 1, 1);
}
