package com.truesim.ui.core.input;

/**
 * The platform's text input, implemented by the engine: a focused text field starts it (the text context, D61), and
 * tells where the IME's candidate window goes. The UI's interaction starts it when a text-taking element gains focus
 * and stops it when that element loses focus.
 */
public interface TextInputHost {

    /** Starts text input; the IME's candidates appear near {@code x, y, width, height} (UI pixels). */
    void start(float x, float y, float width, float height);

    /**
     * Moves the IME's area while text input runs (the caret moved, the field scrolled); the default starts again with
     * the new area, which SDL treats as a move.
     */
    default void area(float x, float y, float width, float height) {
        start(x, y, width, height);
    }

    /** Stops text input. */
    void stop();
}
