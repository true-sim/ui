package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.layout.Layout;

/**
 * A pop-up of this package (a menu, a drop-down's list): an element on the {@link com.truesim.ui.core.Layers#POPUP}
 * layer placed next to an anchor (an element's rectangle, or a point) every frame while open, flipped and kept on the
 * screen, closed by a press outside it (unless the press lands on what the owner says it owns: the button that
 * toggles it). Opening and closing attach and detach the element (they allocate nothing, but restyle it).
 */
final class Popup implements UiListener {

    /** Told when a press outside closed the pop-up. */
    interface Owner {
        /** Whether a press on {@code slot} belongs to the owner (it keeps the pop-up for the owner to handle). */
        boolean owns(int slot);

        /** The pop-up closed because of a press outside. */
        void dismissed();
    }

    private final Controls controls;
    final Element element;
    private final Owner owner;
    private Element anchor;
    private float ax;
    private float ay;
    private boolean below;
    private boolean open;

    @AllowAllocation("controls are built once")
    Popup(Controls controls, Element element, Owner owner) {
        this.controls = controls;
        this.element = element;
        this.owner = owner;
        controls.ui().interaction().on(element, EventType.PRESS_OUTSIDE, this);
    }

    /** Whether it shows. */
    boolean isOpen() {
        return open;
    }

    /** Opens it below ({@code below}) or beside {@code anchorElement}. */
    void open(Element anchorElement, boolean placeBelow) {
        anchor = anchorElement;
        below = placeBelow;
        show();
    }

    /** Opens it at the point {@code x, y} (UI pixels): a context menu at the pointer. */
    void openAt(float x, float y) {
        anchor = null;
        ax = x;
        ay = y;
        below = true;
        show();
    }

    private void show() {
        if (!open) {
            open = true;
            controls.popupLayer().root().add(element);
        }
        place();
    }

    /** Closes it. */
    void close() {
        if (open) {
            open = false;
            if (element.parent() != null) {
                element.detach();
            }
        }
    }

    /** Places it again: each frame while open (the anchor may move, its size is known after its first frame). */
    void place() {
        if (!open) {
            return;
        }
        if (anchor != null && anchor.alive()) {
            Layout l = controls.layout();
            int s = anchor.slot();
            controls.place(element, l.x(s), l.y(s), l.width(s), l.height(s), below);
        } else {
            controls.place(element, ax, ay, 0, 0, true);
        }
    }

    @Override
    public void handle(UiEvent event) {
        if (event.type() != EventType.PRESS_OUTSIDE || !open) {
            return;
        }
        Element hit = controls.ui().interaction().hovered(event.pointerId());
        if (hit != null && owner.owns(hit.slot())) {
            return;
        }
        close();
        owner.dismissed();
    }

    /** Whether {@code slot} is inside the pop-up. */
    boolean contains(int slot) {
        return slot != UiStore.NONE && controls.inside(element.slot(), slot);
    }
}
