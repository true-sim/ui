package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;

/**
 * A small count or tag (type {@code badge}, a {@code label} inside): {@link #count} shows a number (above
 * {@code max} as "max+"; 0 shows no text, an empty badge the theme may draw as a dot), {@link #text} anything short.
 * Changing the count allocates nothing. Usually placed over another element (an anchors or overlay parent).
 */
public final class Badge extends Control {

    private final Element label;
    private final StringBuilder digits = new StringBuilder(8);
    private int count = Integer.MIN_VALUE;
    private int max = 99;

    /** A badge showing {@code text}, announced with {@code name}. */
    @AllowAllocation("controls are built once")
    public Badge(Controls controls, CharSequence text, String name) {
        super(controls, "badge", Role.STATUS, name);
        label = label(text);
        label.semantics(Role.NONE, null);
        root.add(label);
    }

    /** Shows {@code text}. */
    public Badge text(CharSequence text) {
        count = Integer.MIN_VALUE;
        ui.text(label, text);
        return this;
    }

    /** The largest count shown as a number; above it, "max+". */
    public Badge max(int value) {
        max = value;
        return this;
    }

    /** Shows {@code value} (0 shows an empty badge); allocates nothing. */
    public Badge count(int value) {
        if (value == count) {
            return this;
        }
        count = value;
        digits.setLength(0);
        if (value > max) {
            append(max);
            digits.append('+');
        } else if (value != 0) {
            append(value);
        }
        ui.text(label, digits);
        return this;
    }

    private void append(int value) {
        if (value < 0) {
            digits.append('-');
            value = -value;
        }
        int start = digits.length();
        do {
            digits.insert(start, (char) ('0' + value % 10));
            value /= 10;
        } while (value > 0);
    }

    /** The count shown, or {@link Integer#MIN_VALUE} for text. */
    public int count() {
        return count;
    }

    /** The label element. */
    public Element labelElement() {
        return label;
    }
}
