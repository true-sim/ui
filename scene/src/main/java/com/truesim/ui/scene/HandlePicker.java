package com.truesim.ui.scene;

import com.truesim.ui.core.input.Pointer;

/**
 * Screen-space handles in a 3D view picked before the scene's objects (design 8: gizmos come first in the pointer
 * pipeline): a gizmo tests its analytic shapes with a pixel tolerance and answers the element standing for the handle
 * under the pointer. Added with {@link ScenePicking#addHandles}. Must not allocate.
 */
@FunctionalInterface
public interface HandlePicker {

    /**
     * The slot of the element standing for the handle {@code pointer} is over, or {@code UiStore.NONE}. {@code view}
     * is the view the pointer is over (null for a pointer with a world ray of its own, an XR controller), and
     * {@code ray} its world ray: origin then direction.
     */
    int pick(Pointer pointer, SceneView view, double[] ray);
}
