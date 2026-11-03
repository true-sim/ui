package com.truesim.ui.core.input;

/** The system clipboard's text, implemented by the engine (SDL); tests use a field. */
public interface Clipboard {

    /** The clipboard's text, or an empty string. */
    String text();

    /** Puts {@code text} on the clipboard. */
    void text(String text);

    /** A clipboard in a field: for tests, and a UI whose engine gives none. */
    @com.truesim.annotations.AllowAllocation("one per UI")
    static Clipboard inMemory() {
        return new Clipboard() {
            private String text = "";

            @Override
            public String text() {
                return text;
            }

            @Override
            public void text(String value) {
                text = value == null ? "" : value;
            }
        };
    }
}
