package com.truesim.ui.core.event;

/**
 * Handles events of an element ({@link Interaction#on}). The event is a reused object: read it during the call, never
 * keep it. {@link UiEvent#consume()} stops the route and keeps the event from passing through to elements below.
 */
@FunctionalInterface
public interface UiListener {

    /** Handles {@code event}. */
    void handle(UiEvent event);
}
