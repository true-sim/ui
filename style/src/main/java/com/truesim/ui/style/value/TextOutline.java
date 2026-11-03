package com.truesim.ui.style.value;

import java.util.Objects;

/** An outline around glyphs (drawn from the glyph's distance field): its width in panel units and its colour. */
public record TextOutline(float width, Color color) {

    /** No outline. */
    public static final TextOutline NONE = new TextOutline(0, Color.TRANSPARENT);

    public TextOutline {
        Objects.requireNonNull(color, "color");
    }
}
