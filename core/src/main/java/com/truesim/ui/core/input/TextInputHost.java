package com.truesim.ui.core.input;

/**
 * The platform's text input, implemented by the engine: a focused text field starts it (the text context, D61), and
 * tells where the IME's candidate window goes. The UI's interaction starts it when a text-taking element gains focus
 * and stops it when that element loses focus.
 */
public interface TextInputHost {

    /** Kind of text input: plain text (the default). */
    int TEXT = 0;
    /**
     * Kind: a password: no IME composition, prediction or learning (SDL's hidden password input type); text
     * still arrives committed.
     */
    int PASSWORD = 1;
    /** Kind: a number (a numeric on-screen keyboard where there is one). */
    int NUMBER = 2;
    /** Kind: multi-line text (Enter makes a line break on an on-screen keyboard). */
    int MULTILINE = 3;

    /** Starts text input; the IME's candidates appear near {@code x, y, width, height} (UI pixels). */
    void start(float x, float y, float width, float height);

    /**
     * Starts text input of {@code kind} ({@link #TEXT}, {@link #PASSWORD}, {@link #NUMBER}, {@link #MULTILINE});
     * the default ignores the kind.
     */
    default void start(float x, float y, float width, float height, int kind) {
        start(x, y, width, height);
    }

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
