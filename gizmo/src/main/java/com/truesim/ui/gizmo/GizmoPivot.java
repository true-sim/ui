package com.truesim.ui.gizmo;

/** Where a selection of several nodes turns and scales about (Blender's pivot points; design 8). */
public enum GizmoPivot {
    /** The mean of the selected nodes' positions. */
    MEDIAN,
    /** Each node about its own origin (the gizmo shows at the median). */
    INDIVIDUAL,
    /** The active (last selected) node's origin. */
    ACTIVE,
    /** A point the editor sets: the 3D cursor ({@link Gizmo#cursor}). */
    CURSOR,
    /** The centre of the selection's world bounds. */
    BOUNDS_CENTER
}
