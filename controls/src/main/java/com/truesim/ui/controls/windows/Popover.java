package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.overlay.ElementAnchor;
import com.truesim.ui.controls.overlay.Overlay;
import com.truesim.ui.core.Element;

/**
 * A pop-up attached to a trigger element, made by {@link Popovers}: a {@linkplain Popovers#hover hover card} (opens
 * while the pointer rests on its trigger, stays open while the pointer moves to it, closes when the pointer has left
 * both) or a {@linkplain Popovers#click click popover} (opens and closes on a click of its trigger, closes on a press
 * outside it and on {@code CANCEL}). Its content shows in a {@code popover} or {@code hover-card} element on the
 * pop-up layer, placed next to the trigger by that element's placement keys. A popover opened from inside another one
 * is its child: the parent stays open while the pointer or a press is in the child, and closing it closes the child.
 */
public final class Popover {

    /** What a popover tells its owner. */
    public interface Listener {
        /** The popover opened. */
        default void opened(Popover popover) {
        }

        /** The popover closed. */
        default void closed(Popover popover) {
        }
    }

    private static final Listener NONE = new Listener() {
    };

    private final Popovers owner;
    private final Element trigger;
    private final Element surface;
    private final Element content;
    private final boolean hover;
    final Overlay overlay;
    Popover parent;
    Listener listener = NONE;
    long hoverSince = -1;
    long leftAt = -1;
    float leaveX;
    float leaveY;
    boolean focusOpened;
    int index;

    @AllowAllocation("popovers are made when a UI is built")
    Popover(Popovers owner, Element trigger, Element surface, Element content, boolean hover, Overlay overlay) {
        this.owner = owner;
        this.trigger = trigger;
        this.surface = surface;
        this.content = content;
        this.hover = hover;
        this.overlay = overlay.anchor(new ElementAnchor(owner.ui().layout(), trigger));
    }

    /** The element that opens it. */
    public Element trigger() {
        return trigger;
    }

    /** The surface element ({@code popover} or {@code hover-card}) holding the content. */
    public Element surface() {
        return surface;
    }

    /** The content given. */
    public Element content() {
        return content;
    }

    /** Whether this is a hover card (else a click popover). */
    public boolean isHover() {
        return hover;
    }

    /** The overlay showing it: its side, its place. */
    public Overlay overlay() {
        return overlay;
    }

    /** The popover it was opened from, while open; null for a top-level one. */
    public Popover parent() {
        return parent;
    }

    /** Whether it shows. */
    public boolean isOpen() {
        return overlay.showing();
    }

    /** Sets what is told when it opens and closes; returns this. */
    public Popover listener(Listener value) {
        listener = value == null ? NONE : value;
        return this;
    }

    /** Opens it (over the other pop-ups). */
    public void open() {
        owner.open(this);
    }

    /** Closes it and the popovers opened from it. */
    public void close() {
        owner.close(this);
    }

    /** Opens it if closed, closes it if open. */
    public void toggle() {
        if (isOpen()) {
            close();
        } else {
            open();
        }
    }

    @Override
    public String toString() {
        return (hover ? "HoverCard[" : "Popover[") + trigger + (isOpen() ? " open" : "") + "]";
    }
}
