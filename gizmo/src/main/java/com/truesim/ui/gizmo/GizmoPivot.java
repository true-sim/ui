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
    BOUNDS_CENTER;

    private static final GizmoPivot[] ALL = values();

    /**
     * The next pivot in the cycle {@code GIZMO_NEXT_PIVOT} steps through: median, individual, active, cursor, bounds
     * centre.
     */
    public GizmoPivot next() {
        return ALL[(ordinal() + 1) % ALL.length];
    }

    /** The previous pivot in that cycle ({@code GIZMO_PREVIOUS_PIVOT}). */
    public GizmoPivot previous() {
        return ALL[(ordinal() + ALL.length - 1) % ALL.length];
    }
}
