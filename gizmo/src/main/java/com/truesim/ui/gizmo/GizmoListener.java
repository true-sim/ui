package com.truesim.ui.gizmo;

/**
 * Undo hooks of a gizmo (design 8): an editor turns {@link #commit} into one undo command holding the initial and the
 * final transforms. The gizmo has already written the nodes' transforms when each hook runs.
 */
public interface GizmoListener {

    /** A drag (or numeric entry) began on the nodes of {@code initial}, whose local transforms it holds. */
    default void begin(GizmoTransforms initial) {
    }

    /** The drag moved: {@code delta} is the change since {@link #begin}, applied to every node. */
    default void update(GizmoDelta delta) {
    }

    /** The drag ended: the nodes now have the local transforms of {@code finals}; {@code initial} had them before. */
    default void commit(GizmoTransforms initial, GizmoTransforms finals) {
    }

    /** The drag was cancelled: the nodes have their initial transforms again, those of {@code initial}. */
    default void cancel(GizmoTransforms initial) {
    }
}
