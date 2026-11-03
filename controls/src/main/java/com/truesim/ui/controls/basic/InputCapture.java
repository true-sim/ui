package com.truesim.ui.controls.basic;

/**
 * Captures the next key, button or axis the player presses, for rebinding (a {@link KeyBindingField}): the engine's
 * input system implements it (engine-agnostic here). While a capture runs the input system keeps the pressed input
 * from its actions and UI commands, and reports it once.
 */
public interface InputCapture {

    /** Told the outcome of a capture. */
    interface Listener {
        /** The player pressed {@code binding} (the engine's binding object). */
        void captured(Object binding);

        /** The capture ended without a binding (cancelled, timed out, or another capture started). */
        void cancelled();
    }

    /** Starts capturing for {@code player}: the next input pressed goes to {@code listener}, once. */
    void begin(int player, Listener listener);

    /** Cancels the running capture: its listener is told {@link Listener#cancelled()}. */
    void cancel();

    /** The label of {@code binding} for display ({@code Space}, a gamepad glyph's name), empty for none. */
    CharSequence label(Object binding);
}
