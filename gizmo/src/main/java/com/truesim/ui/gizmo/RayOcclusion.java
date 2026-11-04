package com.truesim.ui.gizmo;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.ui.scene.SceneView;
import java.util.function.LongPredicate;

/**
 * Gizmo occlusion by the scene's boxes ({@link GizmoOcclusion}): a ray from the camera to the point through the
 * {@link SpatialIndex} on a layer mask; the point is hidden when a box the ray enters before it belongs to a node not
 * {@code ignored} (the selection itself, which the gizmo sits in). Coarser than the engine's depth test (boxes, not
 * surfaces) but needs nothing from the renderer.
 */
public final class RayOcclusion implements GizmoOcclusion {

    private final SpatialIndex index;
    private final int layerMask;
    private final LongPredicate ignored;
    private final SpatialIndex.Hit hit = new SpatialIndex.Hit();
    private final SpatialIndex.RayFilter filter;

    /** Occlusion by the nodes of {@code spatialIndex} on {@code mask}, except those {@code ignore} accepts. */
    @AllowAllocation("one per gizmo")
    public RayOcclusion(SpatialIndex spatialIndex, int mask, LongPredicate ignore) {
        this.index = spatialIndex;
        this.layerMask = mask;
        this.ignored = ignore;
        this.filter = (node, box, ox, oy, oz, dx, dy, dz, max) -> ignored.test(node) ? Double.POSITIVE_INFINITY : box;
    }

    @Override
    public boolean occluded(SceneView view, double x, double y, double z) {
        double ox = view.x();
        double oy = view.y();
        double oz = view.z();
        if (view.isOrthographic()) {
            // parallel rays: from the camera plane straight along the view
            double depth = view.depth(x, y, z);
            ox = x - view.forward(0) * depth;
            oy = y - view.forward(1) * depth;
            oz = z - view.forward(2) * depth;
        }
        // up to just short of the point: what is behind it does not hide it
        return index.raycast(ox, oy, oz, x - ox, y - oy, z - oz, 0.999, layerMask, filter, hit);
    }
}
