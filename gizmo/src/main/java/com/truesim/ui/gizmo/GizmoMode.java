package com.truesim.ui.gizmo;

/** What a gizmo does (design 8; ImGuizmo's operations). */
public enum GizmoMode {
    /** Axis arrows, plane squares and a screen-plane centre. */
    TRANSLATE,
    /** Axis rings, a view ring and a free trackball. */
    ROTATE,
    /** Axis cubes and a uniform centre. */
    SCALE,
    /** The selection's box: corner and face handles scale it with the opposite side fixed. */
    BOUNDS,
    /** Translate arrows, rotate rings and scale cubes at once. */
    UNIVERSAL;

    private static final GizmoMode[] ALL = values();

    /** The next mode in the cycle {@code GIZMO_NEXT_MODE} steps through: translate, rotate, scale, bounds, universal. */
    public GizmoMode next() {
        return ALL[(ordinal() + 1) % ALL.length];
    }
}
