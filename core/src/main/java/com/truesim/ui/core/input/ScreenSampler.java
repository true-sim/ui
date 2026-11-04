package com.truesim.ui.core.input;

/**
 * Reads colours off the screen for an eyedropper, implemented by the engine (a read-back of the presented image); tests
 * and galleries use {@link PatternScreenSampler}. Sampling is asynchronous by nature: the GPU hands back pixels of a
 * frame already presented, a frame or more after they were asked for. Nothing waits for it: the UI asks every frame for
 * the block under the pointer ({@link #request}) and shows whatever answer is newest ({@link #poll}), so input and
 * drawing never take a frame of delay for the sampler's sake.
 *
 * <p>An answer is a square block of {@code size x size} pixels centred on the asked-for point (for the magnifier) of
 * the most recently presented frame, as linear light with HDR intensities kept (values above 1 are brighter than paper
 * white; the engine reads its scene-referred image, before the display transform), alpha 1.
 */
public interface ScreenSampler {

    /** The largest block a request may ask for (pixels per side, odd). */
    int MAX_SIZE = 31;

    /**
     * Asks for the {@code size x size} block (odd, at most {@link #MAX_SIZE}) centred on UI pixel {@code x, y} of the
     * presented frame; replaces a request not answered yet. False when sampling is not available (no read-back on this
     * platform, the pointer outside every window): nothing will arrive.
     */
    boolean request(float x, float y, int size);

    /**
     * Copies the newest answer into {@code out} when it is newer than what {@code out} holds; true if it did. Called
     * once a frame while an eyedropper is active; allocates nothing.
     */
    boolean poll(ScreenSample out);

    /** The eyedropper closed: no more requests for now (the engine may release its read-back buffers). */
    default void stop() {
        // nothing to release
    }
}
