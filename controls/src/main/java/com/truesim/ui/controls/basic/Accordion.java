package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import java.util.Arrays;

/**
 * Stacked {@link Collapsible} sections (type {@code accordion}): with {@code single} open, expanding one collapses the
 * others; otherwise any number are open.
 */
public final class Accordion extends Control {

    private final boolean single;
    private Collapsible[] sections = new Collapsible[4];
    private int count;

    /** An accordion named {@code name}: one section open at a time when {@code single}. */
    @AllowAllocation("controls are built once")
    public Accordion(Controls controls, String name, boolean single) {
        super(controls, "accordion", Role.GROUP, name);
        this.single = single;
    }

    /** Adds a section titled {@code title} showing {@code content}, open when {@code open}; returns it. */
    @AllowAllocation("controls are built once")
    public Collapsible add(CharSequence title, Element content, boolean open) {
        VersionedBoolean model = controls.clock().booleanValue(open);
        Collapsible c = new Collapsible(controls, title, content, model);
        c.accordion = this;
        if (count == sections.length) {
            sections = Arrays.copyOf(sections, count * 2);
        }
        sections[count++] = c;
        root.add(c.element());
        if (open) {
            expanded(c);
        }
        return c;
    }

    /** Section {@code index}. */
    public Collapsible section(int index) {
        return sections[index];
    }

    /** How many sections. */
    public int count() {
        return count;
    }

    /** Whether one section is open at a time. */
    public boolean single() {
        return single;
    }

    /** {@code opened} expanded: in single mode, the others collapse. */
    void expanded(Collapsible opened) {
        if (!single) {
            return;
        }
        for (int i = 0; i < count; i++) {
            if (sections[i] != opened && sections[i].expanded()) {
                sections[i].expanded(false);
            }
        }
    }
}
