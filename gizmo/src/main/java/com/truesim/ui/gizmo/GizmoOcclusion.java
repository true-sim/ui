package com.truesim.ui.gizmo;

import com.truesim.ui.scene.SceneView;

/**
 * Whether a point of a gizmo is hidden behind the scene from a view (UI26: gizmos draw on top, their hidden parts
 * faded). The engine implements it best from the view's depth buffer (exact, per pixel, one frame old); without it
 * {@link RayOcclusion} asks the scene's {@code SpatialIndex} (by boxes, this frame). Called when the gizmo records,
 * a few hundred points at most, never per pointer. Must not allocate.
 */
@FunctionalInterface
public interface GizmoOcclusion {

    /** Whether world point {@code x, y, z} is behind something of the scene seen through {@code view}. */
    boolean occluded(SceneView view, double x, double y, double z);
}
