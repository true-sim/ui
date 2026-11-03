package com.truesim.ui.core.input;

/**
 * Where pointers come from (design UI22): the mouse, touch fingers and pens read from devices directly, XR rays and
 * buttons through the {@code ui.pointer} actions. Called once at frame start, after input sampling.
 */
@FunctionalInterface
public interface PointerSource {

    /** Updates the pointers this source owns in {@code input}. */
    void poll(UiInput input);
}
