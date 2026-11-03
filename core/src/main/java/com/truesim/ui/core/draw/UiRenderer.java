package com.truesim.ui.core.draw;

/**
 * Draws a UI's layers, implemented by the engine ({@code engine-ui}): each frame it uploads each list's dirty
 * primitives and clips and the dirty regions of the textures, then draws the lists in order into the UI image. A frame
 * where nothing changed uploads nothing.
 */
public interface UiRenderer {

    /** Draws {@code layers} (back to front) of a UI {@code width x height} pixels, its textures in {@code textures}. */
    void submit(DrawList[] layers, int layerCount, UiTextures textures, int width, int height);
}
