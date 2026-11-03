package com.truesim.ui.style.value;

import java.util.Objects;

/** What an image element shows: an image asset by name, a tint, and how it fits the element's box. */
public record ImageSpec(String name, Color tint, ImageFit fit) {

    /** No image. */
    public static final ImageSpec NONE = new ImageSpec("", Color.WHITE, ImageFit.CONTAIN);

    public ImageSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tint, "tint");
        Objects.requireNonNull(fit, "fit");
    }
}
