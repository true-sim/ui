package com.truesim.ui.core.input;

/**
 * Where UI commands come from (design UI6): the engine maps its {@code ui} action group to them; a test pushes them.
 * Called once at frame start, after input sampling, so commands are seen in the same frame.
 */
@FunctionalInterface
public interface UiCommandSource {

    /** Adds this frame's commands to {@code input}. */
    void poll(UiInput input);
}
