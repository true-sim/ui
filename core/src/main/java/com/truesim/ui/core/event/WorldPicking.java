package com.truesim.ui.core.event;

import com.truesim.ui.core.input.Pointer;

/**
 * Picking beyond the screen layers (design 4.7, steps 2 to 5): world panels and scene objects, behind every screen
 * layer. The UI knows no scene; the scene bridge ({@code ui-scene}) implements this and stands for each world panel
 * and each interactive scene node with an element of its own, so the hits it adds take part in everything a screen
 * hit does: routing with capture and bubble, pass-through, pointer capture, hover, clicks, long presses, drag and drop
 * with one {@link DragSession}.
 *
 * <p>Called once per pointer and frame, after the screen layers' hit test, unless a blocking layer stopped it.
 * Must not allocate.
 */
@FunctionalInterface
public interface WorldPicking {

    /**
     * Appends the elements standing for what {@code pointer} hits beyond the screen layers, nearest first, to
     * {@code hits} after its first {@code screenCount} entries (the screen layers' hits, front-most first; the picker
     * may read them, e.g. to see the pointer is over a camera view), at most up to the array's length. Returns the new
     * count.
     */
    int pick(Pointer pointer, int[] hits, int screenCount);
}
