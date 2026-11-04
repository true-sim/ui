package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.style.Facts;

/**
 * A line between groups of content (type {@code separator}, with the fact {@code orientation=horizontal} or
 * {@code vertical} for the theme: {@code separator[orientation=vertical]}).
 */
public final class Separator {

    private final Element element;

    /** A separator, {@code vertical} or horizontal. */
    @AllowAllocation("controls are built once")
    public Separator(Controls controls, boolean vertical) {
        element = controls.ui().element("separator", null);
        element.semantics(Role.SEPARATOR, null);
        controls.ui().styles().facts(element.slot(), Facts.of("orientation", vertical ? "vertical" : "horizontal"));
    }

    /** The element. */
    public Element element() {
        return element;
    }
}
