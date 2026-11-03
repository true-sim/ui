package com.truesim.ui.style.value;

import java.util.Objects;

/** A width and a height: what the {@code size} shorthand sets. */
public record Size(Length width, Length height) {

    public Size {
        Objects.requireNonNull(width, "width");
        Objects.requireNonNull(height, "height");
    }
}
