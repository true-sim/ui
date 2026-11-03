package com.truesim.ui.controls;

import com.truesim.ui.core.input.UiInput;

/**
 * Work a control does in every frame of its {@link Ui} ({@link Ui#addFrameHook}): pop-ups placed next to what they are
 * anchored to, windows and docked panes whose positions, sizes and split ratios are set in code, timers (tooltip and
 * hover delays). All calls run inside {@link Ui#update}, after input was processed, so their effects show in the same
 * frame; they must not allocate in steady state.
 */
public interface UiFrameHook {

    /**
     * Once a frame, after input was processed and styles resolved, before the first layout pass: timers and logic that
     * look at the frame's input (hover delays, a tooltip's pointer). {@code input} is the frame's input (null in
     * frames without input), {@code nanos} the UI clock's time.
     */
    default void frame(Ui ui, UiInput input, long nanos) {
    }

    /**
     * Before every layout pass (after styles were resolved and applied): set positions and sizes from code here
     * ({@code Layout} setters are no-ops when unchanged; a restyled element took its style's values, so set them again
     * every pass).
     */
    default void beforeLayout(Ui ui) {
    }

    /**
     * After layout: read what was laid out and place what depends on it (an overlay next to its anchor, a minimised
     * window's height). A change of layout inputs, state or facts made here is resolved and laid out again in the same
     * frame (a few passes at most, for overlays anchored in other overlays).
     */
    default void afterLayout(Ui ui) {
    }
}
