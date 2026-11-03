package com.truesim.ui.controls.overlay;

/**
 * What an overlay is attached to, as a rectangle in the overlay's coordinates (UI pixels of its UI) asked for once a
 * frame, after layout: an element ({@link ElementAnchor}), a point ({@link PointAnchor}: the pointer, a fixed place),
 * or something in a 3D scene. The UI knows no scene: an engine supplies scene anchors by projecting a world point or
 * a node's bounds with the view's camera each frame, so a label follows its object; it reports the anchor hidden when
 * the point is behind the camera or off the view, and the overlay hides with it.
 */
@FunctionalInterface
public interface OverlayAnchor {

    /**
     * Writes the anchor's rectangle into {@code out} (x, y, width, height; a point has zero size) and returns whether
     * it is visible now. Called every frame: it must not allocate.
     */
    boolean bounds(float[] out);
}
