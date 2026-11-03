package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;

/**
 * Text with an icon (type {@code icon-label}: an {@code icon}, then a {@code label}); which side the icon is on and
 * the gap are the theme's. Announced as text.
 */
public final class IconLabel extends Control {

    private final Element icon;
    private final Element label;

    /** {@code text} after the icon {@code iconName}. */
    @AllowAllocation("controls are built once")
    public IconLabel(Controls controls, String iconName, CharSequence text) {
        super(controls, "icon-label", Role.TEXT, text.toString());
        icon = controls.icon(iconName);
        label = label(text);
        label.semantics(Role.NONE, null);
        root.add(icon).add(label);
    }

    /** Shows {@code text}. */
    @AllowAllocation("text changes on edit")
    public IconLabel text(CharSequence text) {
        ui.text(label, text);
        root.semantics(Role.TEXT, text.toString());
        return this;
    }

    /** The icon element (its id names the icon). */
    public Element icon() {
        return icon;
    }

    /** The label element. */
    public Element labelElement() {
        return label;
    }
}
