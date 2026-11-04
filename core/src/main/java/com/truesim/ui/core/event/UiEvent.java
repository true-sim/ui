package com.truesim.ui.core.event;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;

/**
 * One event on its route (design 4.7): a reused object, filled by {@link Interaction} and handed to each listener on
 * the route; never allocated per event and never to be kept. What it carries depends on its {@link #type()}: pointer
 * events have a pointer, position, buttons and modifiers; commands a {@link UiCommand}; text events the text; drag and
 * drop events the {@link DragSession}.
 */
public final class UiEvent {

    private final Interaction interaction;
    EventType type;
    Phase phase;
    int target = UiStore.NONE;
    int current = UiStore.NONE;
    int related = UiStore.NONE;
    boolean consumed;
    long time;

    // pointer
    int pointerId = -1;
    Pointer.Kind pointerKind;
    int player;
    float x;
    float y;
    int button;
    int buttons;
    int modifiers;
    int clickCount;
    float scrollX;
    float scrollY;
    float deltaX;
    float deltaY;
    float pressX;
    float pressY;

    // commands and text
    UiCommand command;
    int commandFlags;
    CharSequence text;
    int compositionCursor;
    boolean focusVisible;

    // drag and drop
    DragSession drag;
    int dropAccepted;
    int acceptedBy = UiStore.NONE;

    UiEvent(Interaction interaction) {
        this.interaction = interaction;
    }

    void reset(EventType newType, int newTarget, long now) {
        type = newType;
        phase = Phase.TARGET;
        target = newTarget;
        current = newTarget;
        related = UiStore.NONE;
        consumed = false;
        time = now;
        pointerId = -1;
        pointerKind = null;
        player = 0;
        x = 0;
        y = 0;
        button = 0;
        buttons = 0;
        modifiers = 0;
        clickCount = 0;
        scrollX = 0;
        scrollY = 0;
        deltaX = 0;
        deltaY = 0;
        pressX = 0;
        pressY = 0;
        command = null;
        commandFlags = 0;
        text = null;
        compositionCursor = 0;
        focusVisible = false;
        drag = null;
        dropAccepted = DragOperation.NONE;
        acceptedBy = UiStore.NONE;
    }

    /** What happened. */
    public EventType type() {
        return type;
    }

    /** Where the event is on its route. */
    public Phase phase() {
        return phase;
    }

    /** The element the event is for (the hit, the focused element). */
    public Element target() {
        return interaction.store().element(target);
    }

    /** The target's slot. */
    public int targetSlot() {
        return target;
    }

    /** The element whose listener runs now (the target or one of its ancestors). */
    public Element currentTarget() {
        return interaction.store().element(current);
    }

    /** The slot of the element whose listener runs now. */
    public int currentSlot() {
        return current;
    }

    /**
     * The other element: for ENTER the one the pointer came from, for EXIT the one it went to, for FOCUS_IN the one
     * that lost focus and for FOCUS_OUT the one that gains it; null if none.
     */
    public Element related() {
        return related == UiStore.NONE ? null : interaction.store().element(related);
    }

    /** Stops the route here: no further listeners, no pass-through to elements below, no default action. */
    public void consume() {
        consumed = true;
    }

    /** Whether a listener consumed the event. */
    public boolean consumed() {
        return consumed;
    }

    /** The UI clock's time of the frame, in nanoseconds. */
    public long time() {
        return time;
    }

    /** The interaction that dispatches the event: focus, capture, clipboard, text input. */
    public Interaction interaction() {
        return interaction;
    }

    // ---------------------------------------------------------------- pointer

    /** The pointer's id, or -1 for events not from a pointer (commands, focus, text). */
    public int pointerId() {
        return pointerId;
    }

    /** The pointer's kind, or null. */
    public Pointer.Kind pointerKind() {
        return pointerKind;
    }

    /** The player the pointer, command or focus belongs to. */
    public int player() {
        return player;
    }

    /** The pointer's position in UI pixels. */
    public float x() {
        return x;
    }

    /** The pointer's position in UI pixels. */
    public float y() {
        return y;
    }

    /** The pointer's position relative to the current target's left edge. */
    public float localX() {
        return x - interaction.geometry().x(current);
    }

    /** The pointer's position relative to the current target's top edge. */
    public float localY() {
        return y - interaction.geometry().y(current);
    }

    /** The button that went down or up ({@link Pointer#PRIMARY} and the others); 0 for other events. */
    public int button() {
        return button;
    }

    /** The buttons held. */
    public int buttons() {
        return buttons;
    }

    /** The modifiers held ({@link Pointer#SHIFT} and the others). */
    public int modifiers() {
        return modifiers;
    }

    /** For CLICK: 1, 2 for a double click, 3 for a triple click... (always 1 for ACCEPT). */
    public int clickCount() {
        return clickCount;
    }

    /** The wheel's horizontal scroll. */
    public float scrollX() {
        return scrollX;
    }

    /** The wheel's vertical scroll (positive away from the user). */
    public float scrollY() {
        return scrollY;
    }

    /** For MOVE and DRAG: how far the pointer moved since the last frame, in UI pixels. */
    public float deltaX() {
        return deltaX;
    }

    /** For MOVE and DRAG: how far the pointer moved since the last frame, in UI pixels. */
    public float deltaY() {
        return deltaY;
    }

    /** For drag events: where the press was, in UI pixels. */
    public float pressX() {
        return pressX;
    }

    /** For drag events: where the press was, in UI pixels. */
    public float pressY() {
        return pressY;
    }

    // ---------------------------------------------------------------- commands, text, focus

    /** For COMMAND: the command. */
    public UiCommand command() {
        return command;
    }

    /** For COMMAND: its flags ({@link com.truesim.ui.core.input.UiInput#EXTEND_SELECTION}). */
    public int commandFlags() {
        return commandFlags;
    }

    /** For TEXT: the committed text; for COMPOSITION: the composition (empty when it ended). Valid during the call. */
    public CharSequence text() {
        return text;
    }

    /** For COMPOSITION: the cursor in the composition. */
    public int compositionCursor() {
        return compositionCursor;
    }

    /** For FOCUS_IN: whether the focus came from navigation (keys, gamepad), so a focus ring shows. */
    public boolean focusVisible() {
        return focusVisible;
    }

    // ---------------------------------------------------------------- drag and drop

    /**
     * For DRAG_START: starts a drag session for this pointer from the current target, allowing {@code operations}
     * ({@link DragOperation} bits), and consumes the event. Put the payload into the returned session.
     */
    public DragSession startDrag(int operations) {
        if (type != EventType.DRAG_START) {
            throw new IllegalStateException("a drag session starts in DRAG_START, not " + type);
        }
        consumed = true;
        return interaction.beginDrag(pointerId, current, operations, x, y);
    }

    /** For drag and drop events: the session, or null when the drag carries no session. */
    public DragSession drag() {
        return drag;
    }

    /**
     * For DROP_OVER: the current target accepts the payload for {@code operations} ({@link DragOperation} bits); the
     * drop will do the user's preferred one of those the source allows. Consumes the event.
     */
    public void acceptDrop(int operations) {
        if (type != EventType.DROP_OVER) {
            throw new IllegalStateException("drops are accepted in DROP_OVER, not " + type);
        }
        dropAccepted = operations;
        acceptedBy = current;
        consumed = true;
    }

    /** For DROP_ENTER, DROP and DRAG_END: what the drop does (one {@link DragOperation}; NONE: cancelled). */
    public int dropOperation() {
        return drag == null ? DragOperation.NONE : drag.operation();
    }

    @Override
    public String toString() {
        return type + "@" + target + (phase == Phase.TARGET ? "" : "/" + phase + "@" + current);
    }
}
