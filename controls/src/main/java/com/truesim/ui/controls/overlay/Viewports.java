package com.truesim.ui.controls.overlay;

/**
 * The viewports of a UI that spans several (docking's OS windows each show a region of one UI): an anchored overlay
 * is placed and kept inside the viewport holding its anchor, not its host's box ({@link Overlays#viewports}).
 */
@FunctionalInterface
public interface Viewports {

    /**
     * The viewport holding {@code x, y} (UI pixels): its {@code x, y, width, height} into {@code out}, true; false
     * if it is the overlays' host box (the main window). Called per showing overlay per frame: allocate nothing.
     */
    boolean viewportAt(float x, float y, float[] out);
}
