package com.truesim.ui.gizmo;

/**
 * What a drag did so far, relative to its start (design 8: {@link GizmoListener#update}): a translation in world
 * units, a rotation about the pivot, or a scale along the gizmo's axes about the pivot; with the handle, the gizmo's
 * axes and pivot, and the one number a status bar shows (distance, degrees or factor). Reused per gizmo.
 */
public final class GizmoDelta {

    GizmoHandle handle = GizmoHandle.NONE;
    final double[] translation = new double[3];
    final double[] rotation = {0, 0, 0, 1};
    final double[] scale = {1, 1, 1};
    final double[] axes = {0, 0, 0, 1};
    final double[] pivot = new double[3];
    boolean individual;
    double value;
    boolean snapped;
    boolean typed;

    void reset(GizmoHandle grabbed) {
        handle = grabbed;
        translation[0] = 0;
        translation[1] = 0;
        translation[2] = 0;
        rotation[0] = 0;
        rotation[1] = 0;
        rotation[2] = 0;
        rotation[3] = 1;
        scale[0] = 1;
        scale[1] = 1;
        scale[2] = 1;
        value = 0;
        snapped = false;
        typed = false;
    }

    /** The handle dragged. */
    public GizmoHandle handle() {
        return handle;
    }

    /** Component {@code c} (0 x, 1 y, 2 z) of the translation in world units. */
    public double translation(int c) {
        return translation[c];
    }

    /** Component {@code c} (0 x, 1 y, 2 z, 3 w) of the rotation (world space) about the pivot. */
    public double rotation(int c) {
        return rotation[c];
    }

    /** The scale factor along gizmo axis {@code c}. */
    public double scale(int c) {
        return scale[c];
    }

    /** Component {@code c} of the gizmo axes' rotation (the space's): scale factors are along these axes. */
    public double axes(int c) {
        return axes[c];
    }

    /** Component {@code c} of the pivot rotations and scales are about (world). */
    public double pivot(int c) {
        return pivot[c];
    }

    /** Whether each node turns and scales about its own origin instead of the pivot. */
    public boolean individual() {
        return individual;
    }

    /** The drag's one number: the distance moved along the handle, the angle in degrees, or the factor. */
    public double value() {
        return value;
    }

    /** Whether snapping applied. */
    public boolean snapped() {
        return snapped;
    }

    /** Whether the value was typed (numeric entry). */
    public boolean typed() {
        return typed;
    }
}
