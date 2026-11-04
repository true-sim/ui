package com.truesim.ui.gizmo;

/** One handle of a gizmo: what the pointer grabs (design 8). */
public enum GizmoHandle {
    /** None. */
    NONE(-1),
    TRANSLATE_X(0),
    TRANSLATE_Y(1),
    TRANSLATE_Z(2),
    /** The square in the plane of X and Y. */
    TRANSLATE_XY(2),
    TRANSLATE_YZ(0),
    TRANSLATE_ZX(1),
    /** The centre: moves in the view plane. */
    TRANSLATE_SCREEN(-1),
    ROTATE_X(0),
    ROTATE_Y(1),
    ROTATE_Z(2),
    /** The outer ring: turns about the view direction. */
    ROTATE_VIEW(-1),
    /** Inside the rings: turns freely, as a ball rolled under the pointer. */
    ROTATE_TRACKBALL(-1),
    SCALE_X(0),
    SCALE_Y(1),
    SCALE_Z(2),
    /** The centre: scales evenly. */
    SCALE_UNIFORM(-1),
    /** A corner of the selection's box ({@link Gizmo#handlePart()}: which, bits 0 to 2 the max side of x, y, z). */
    BOUNDS_CORNER(-1),
    /** A face of the box ({@link Gizmo#handlePart()}: axis * 2 + 1 for the max side). */
    BOUNDS_FACE(-1);

    private final int axis;

    GizmoHandle(int axis) {
        this.axis = axis;
    }

    /** The gizmo axis (0 x, 1 y, 2 z) of an axis handle, or the normal of a plane handle; -1 for the others. */
    public int axis() {
        return axis;
    }

    /** Whether it translates. */
    public boolean translates() {
        return ordinal() >= TRANSLATE_X.ordinal() && ordinal() <= TRANSLATE_SCREEN.ordinal();
    }

    /** Whether it rotates. */
    public boolean rotates() {
        return ordinal() >= ROTATE_X.ordinal() && ordinal() <= ROTATE_TRACKBALL.ordinal();
    }

    /** Whether it scales (bounds handles too). */
    public boolean scales() {
        return ordinal() >= SCALE_X.ordinal();
    }

    /** Whether it is a plane square. */
    public boolean plane() {
        return this == TRANSLATE_XY || this == TRANSLATE_YZ || this == TRANSLATE_ZX;
    }
}
