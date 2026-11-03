package com.truesim.ui.controls.overlay;

/** An anchor at a point that code moves: the pointer (a tooltip following it), a place on the screen. */
public final class PointAnchor implements OverlayAnchor {

    private float x;
    private float y;
    private boolean visible = true;

    /** A point anchor at {@code x, y} in UI pixels. */
    public PointAnchor(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /** Moves the point; returns this. */
    public PointAnchor set(float newX, float newY) {
        x = newX;
        y = newY;
        return this;
    }

    /** Shows or hides what is anchored here; returns this. */
    public PointAnchor visible(boolean value) {
        visible = value;
        return this;
    }

    /** The point's x. */
    public float x() {
        return x;
    }

    /** The point's y. */
    public float y() {
        return y;
    }

    @Override
    public boolean bounds(float[] out) {
        out[0] = x;
        out[1] = y;
        out[2] = 0;
        out[3] = 0;
        return visible;
    }
}
