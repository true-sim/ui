package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;

/**
 * A titled group (type {@code group-box}; a fieldset): a title label (style name {@code group-title}) over a
 * {@code group-content} panel. Disabling it ({@link #enabled(boolean)}) disables everything inside (no events;
 * {@code group-box:disabled} and its descendants for the look). Announced as a group named by its title.
 */
public final class GroupBox extends Control {

    private final Element title;
    private final Element content;

    /** A group titled {@code title}. */
    @AllowAllocation("controls are built once")
    public GroupBox(Controls controls, CharSequence title) {
        super(controls, "group-box", Role.GROUP, title.toString());
        this.title = ui.label("group-title", title);
        this.title.semantics(Role.NONE, null);
        content = part("group-content");
        root.add(this.title).add(content);
    }

    /** Adds {@code child} to the content; returns this. */
    public GroupBox add(Element child) {
        content.add(child);
        return this;
    }

    /** The content panel. */
    public Element content() {
        return content;
    }

    /** The title label. */
    public Element title() {
        return title;
    }
}
