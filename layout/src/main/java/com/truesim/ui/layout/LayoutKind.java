package com.truesim.ui.layout;

/**
 * How an element arranges its children (design 4.6). Same constants in the same order as ui-style's
 * {@code LayoutKind}, so a resolved style value maps by ordinal.
 */
public enum LayoutKind {
    /** Absolute: each child at its {@code offsets} left and top, at its own size. */
    NONE,
    /** One after another along {@link Direction}, at their own sizes (a box; flex without growing or wrapping). */
    STACK,
    /** A CSS flexbox subset: direction, wrap, grow, shrink, basis, justify, align, gap. */
    FLEX,
    /** Tracks (fixed, auto, fraction, min-max) and placements with spans. */
    GRID,
    /** Lemur's border layout: north, south, west, east around a centre. */
    BORDER,
    /** Godot-style anchors plus offsets, for HUDs. */
    ANCHORS,
    /** Layers on top of each other, each aligned in the whole content box. */
    OVERLAY
}
