package com.truesim.ui.core.input;

/** The system clipboard's text, implemented by the engine (SDL); tests use a field. */
public interface Clipboard {

    /** The clipboard's text, or an empty string. */
    String text();

    /** Puts {@code text} on the clipboard. */
    void text(String text);
}
