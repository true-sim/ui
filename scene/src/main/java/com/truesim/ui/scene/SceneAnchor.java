package com.truesim.ui.scene;

import com.truesim.common.math.geometry.AabbArray;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.ui.controls.overlay.OverlayAnchor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * An overlay anchor in a 3D view: a world point, or a scene node's world box, projected through a {@link SceneView}
 * every frame, so a label, a tooltip or a marker follows its object (the scene anchors {@link OverlayAnchor} asks the
 * engine for, and the markers of a {@link CameraView}). Hidden when the point (the box's centre) is behind the camera
 * or off the view.
 *
 * <pre>{@code
 * Overlay label = overlays.create(nameTag, Layers.OVERLAY).anchor(new SceneAnchor(view).node(scene, player));
 * }</pre>
 */
public final class SceneAnchor implements OverlayAnchor {

    private SceneView view;
    private SceneGraph scene;
    private long node = NodeHandle.NONE;
    private double x;
    private double y;
    private double z;
    private final float[] p = new float[3];

    /** An anchor projected through {@code sceneView}. */
    public SceneAnchor(SceneView sceneView) {
        view = sceneView;
    }

    /** Projects through {@code sceneView} from now on; returns this. */
    public SceneAnchor view(SceneView sceneView) {
        view = sceneView;
        return this;
    }

    /** The view it projects through. */
    public SceneView view() {
        return view;
    }

    /** Anchors at world point {@code px, py, pz}; returns this. */
    public SceneAnchor point(double px, double py, double pz) {
        scene = null;
        node = NodeHandle.NONE;
        x = px;
        y = py;
        z = pz;
        return this;
    }

    /** Anchors at {@code target}'s world box in {@code graph} (its origin if it has none); returns this. */
    public SceneAnchor node(SceneGraph graph, long target) {
        scene = graph;
        node = target;
        return this;
    }

    /** The node anchored to, or {@link NodeHandle#NONE}. */
    public long node() {
        return node;
    }

    @Override
    public boolean bounds(float[] out) {
        if (scene == null) {
            return point(x, y, z, out);
        }
        if (!scene.contains(node)) {
            return false;
        }
        int index = NodeHandle.index(node);
        if (!scene.hasBounds(node)) {
            long o = index * scene.worldTransforms().stride();
            MemorySegment s = scene.worldTransforms().segment();
            return point(s.get(ValueLayout.JAVA_DOUBLE, o),
                s.get(ValueLayout.JAVA_DOUBLE, o + 8),
                s.get(ValueLayout.JAVA_DOUBLE, o + 16), out);
        }
        AabbArray b = scene.worldBounds();
        double x0 = b.get(index, 0);
        double y0 = b.get(index, 1);
        double z0 = b.get(index, 2);
        double x1 = b.get(index, 3);
        double y1 = b.get(index, 4);
        double z1 = b.get(index, 5);
        if (!point((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, out)) {
            return false;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int c = 0; c < 8; c++) {
            if (view.project((c & 1) == 0 ? x0 : x1, (c & 2) == 0 ? y0 : y1, (c & 4) == 0 ? z0 : z1, p)) {
                minX = Math.min(minX, p[0]);
                minY = Math.min(minY, p[1]);
                maxX = Math.max(maxX, p[0]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        // a corner behind the camera: keep the centre's point
        if (minX <= maxX) {
            out[0] = minX;
            out[1] = minY;
            out[2] = maxX - minX;
            out[3] = maxY - minY;
        }
        return true;
    }

    private boolean point(double px, double py, double pz, float[] out) {
        if (!view.project(px, py, pz, p) || !view.contains(p[0], p[1])) {
            return false;
        }
        out[0] = p[0];
        out[1] = p[1];
        out[2] = 0;
        out[3] = 0;
        return true;
    }
}
