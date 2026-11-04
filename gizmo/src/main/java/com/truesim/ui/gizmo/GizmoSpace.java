package com.truesim.ui.gizmo;

/** Which axes a gizmo's handles follow (design 8). */
public enum GizmoSpace {
    /** The active node's own axes. */
    LOCAL,
    /** The world's axes. */
    WORLD,
    /** The axes of the active node's parent (the world's without one). */
    PARENT,
    /** The camera's: right, up and towards the viewer. */
    VIEW;

    private static final GizmoSpace[] ALL = values();

    /** The next space in the cycle {@code GIZMO_TOGGLE_SPACE} steps through: local, world, parent, view. */
    public GizmoSpace next() {
        return ALL[(ordinal() + 1) % ALL.length];
    }
}
