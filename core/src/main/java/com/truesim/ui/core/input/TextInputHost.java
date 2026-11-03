package com.truesim.ui.core.input;

/**
 * The platform's text input, implemented by the engine: a focused text field starts it (the text context, D61), and
 * tells where the IME's candidate window goes.
 */
public interface TextInputHost {

    /** Starts text input; the IME's candidates appear near {@code x, y, width, height} (UI pixels). */
    void start(float x, float y, float width, float height);

    /** Stops text input. */
    void stop();
}
