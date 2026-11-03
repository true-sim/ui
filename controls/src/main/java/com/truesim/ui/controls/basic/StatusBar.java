package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import java.util.Arrays;

/**
 * A status bar (type {@code status-bar}): a row of sections, each a label of style name {@code status-section} (or any
 * element). Setting a section's text allocates nothing. Announced as status.
 */
public final class StatusBar extends Control {

    private Element[] sections = new Element[4];
    private int count;

    /** A status bar named {@code name}. */
    @AllowAllocation("controls are built once")
    public StatusBar(Controls controls, String name) {
        super(controls, "status-bar", Role.STATUS, name);
    }

    /** Adds a text section showing {@code text}; returns its index. */
    @AllowAllocation("controls are built once")
    public int section(CharSequence text) {
        Element label = ui.label("status-section", text);
        return section(label);
    }

    /** Adds {@code element} as a section; returns its index. */
    @AllowAllocation("controls are built once")
    public int section(Element element) {
        if (count == sections.length) {
            sections = Arrays.copyOf(sections, count * 2);
        }
        sections[count] = element;
        root.add(element);
        return count++;
    }

    /** Shows {@code text} in text section {@code index}. */
    public StatusBar text(int index, CharSequence text) {
        ui.text(sections[index], text);
        return this;
    }

    /** Section {@code index}. */
    public Element section(int index) {
        return sections[index];
    }
}
