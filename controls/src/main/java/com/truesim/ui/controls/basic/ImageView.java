package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;

/**
 * An image with a description (type {@code image}, with the style name given): what it shows and how it fits are the
 * style's {@code image} ({@code image.portrait { image: "faces/ada" contain; }}). Announced as an image named by its
 * description.
 */
public final class ImageView {

    private final Element element;

    /** An image of style name {@code style} described as {@code description}. */
    @AllowAllocation("controls are built once")
    public ImageView(Controls controls, String style, String description) {
        element = controls.ui().image(style);
        element.semantics(Role.IMAGE, description);
    }

    /** The image element. */
    public Element element() {
        return element;
    }
}
