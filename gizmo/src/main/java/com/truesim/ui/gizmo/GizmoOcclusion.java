package com.truesim.ui.gizmo;

import com.truesim.ui.scene.SceneView;

/**
 * Whether a point of a gizmo is hidden behind the scene from a view (UI26: gizmos draw on top, their hidden parts
 * faded), decided on the CPU per part when the gizmo records: for views whose depth the renderer does not draw the UI
 * over (a camera view's), headless runs and tests. Where it does, {@link Gizmo#depthTest} is exact instead (per pixel,
 * against the scene's depth of the same frame) and this is not asked. {@link RayOcclusion} asks the scene's
 * {@code SpatialIndex} (by boxes, this frame). Called when the gizmo records, a few hundred points at most, never per
 * pointer. Must not allocate.
 */
@FunctionalInterface
public interface GizmoOcclusion {

    /** Whether world point {@code x, y, z} is behind something of the scene seen through {@code view}. */
    boolean occluded(SceneView view, double x, double y, double z);
}
