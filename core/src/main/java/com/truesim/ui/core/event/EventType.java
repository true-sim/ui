package com.truesim.ui.core.event;

/**
 * What a {@link UiEvent} reports (design 4.7). Listeners register for a mask of types ({@link #bit()} or'ed). Events
 * that {@linkplain #bubbles() bubble} run capture (root to target), target and bubble (target to root) phases; the
 * others go to their target only, as DOM's {@code mouseenter}: each element entered gets its own.
 */
public enum EventType {
    /** A pointer came over the element or one of its descendants (target only, one per element entered). */
    ENTER(false),
    /** A pointer left the element and its descendants (target only). */
    EXIT(false),
    /** A pointer moved over the element (or the element holds the pointer's capture). */
    MOVE(true),
    /** A pointer button went down: {@link UiEvent#button()}. Unconsumed, it passes to the next hit below. */
    PRESS(true),
    /** A pointer button went up: to the element that captured the pointer on its press. */
    RELEASE(true),
    /** A primary press and release on the same element, or ACCEPT on the focused one: {@link UiEvent#clickCount()}. */
    CLICK(true),
    /** A press held still for {@code ui.input.longPressDelay}. Consumed, it suppresses the click. */
    LONG_PRESS(true),
    /** A context menu is asked for: the secondary button, a long touch, or the CONTEXT_MENU command. */
    CONTEXT(true),
    /** The wheel or a scroll gesture: {@link UiEvent#scrollX()}, {@link UiEvent#scrollY()}. Passes through. */
    WHEEL(true),
    /** A pressed pointer moved past the drag threshold; a listener may {@linkplain UiEvent#startDrag start} a session. */
    DRAG_START(true),
    /** The captured pointer moved while dragging. */
    DRAG(true),
    /** The dragging pointer was released or the drag cancelled: {@link UiEvent#dropOperation()} says how it ended. */
    DRAG_END(true),
    /** A drag session's payload came over an element that {@linkplain UiEvent#acceptDrop accepted} it (target only). */
    DROP_ENTER(false),
    /** A drag session's payload is over the element: accept it with {@link UiEvent#acceptDrop}. Passes through. */
    DROP_OVER(true),
    /** The payload left the accepting element (target only). */
    DROP_LEAVE(false),
    /** The payload was dropped on the element that accepted it (target only). */
    DROP(false),
    /** The element got focus ({@link UiEvent#player()}); bubbles, like DOM's {@code focusin}. */
    FOCUS_IN(true),
    /** The element lost focus; bubbles. */
    FOCUS_OUT(true),
    /** A {@link com.truesim.ui.core.input.UiCommand} for the focused element ({@link UiEvent#command()}). */
    COMMAND(true),
    /** Committed text for the focused text-taking element ({@link UiEvent#text()}). */
    TEXT(true),
    /** The IME's composition changed ({@link UiEvent#text()}, {@link UiEvent#compositionCursor()}). */
    COMPOSITION(true),
    /** A pointer rested on the element for {@code ui.input.tooltipDelay}: show a tooltip. Bubbles to the nearest. */
    TOOLTIP(true),
    /** A press landed outside the element: for elements listening to it (pop-ups that close then; target only). */
    PRESS_OUTSIDE(false);

    private static final EventType[] ALL = values();

    /** Every pointer event type: an element listening to one of these is pickable. */
    public static final int POINTER_MASK = (1 << ENTER.ordinal()) | (1 << EXIT.ordinal()) | (1 << MOVE.ordinal())
        | (1 << PRESS.ordinal()) | (1 << RELEASE.ordinal()) | (1 << CLICK.ordinal()) | (1 << LONG_PRESS.ordinal())
        | (1 << CONTEXT.ordinal()) | (1 << WHEEL.ordinal()) | (1 << DRAG_START.ordinal()) | (1 << DRAG.ordinal())
        | (1 << DRAG_END.ordinal()) | (1 << DROP_ENTER.ordinal()) | (1 << DROP_OVER.ordinal())
        | (1 << DROP_LEAVE.ordinal()) | (1 << DROP.ordinal()) | (1 << TOOLTIP.ordinal());

    /** Every type. */
    public static final int ALL_MASK = (1 << ALL.length) - 1;

    private final boolean bubbles;

    EventType(boolean bubbles) {
        this.bubbles = bubbles;
    }

    /** This type's bit in listener masks. */
    public int bit() {
        return 1 << ordinal();
    }

    /** Whether events of this type run capture and bubble phases. */
    public boolean bubbles() {
        return bubbles;
    }

    /** The type of an ordinal, without copying {@code values()}. */
    public static EventType of(int ordinal) {
        return ALL[ordinal];
    }

    /** How many types exist. */
    public static int count() {
        return ALL.length;
    }
}
