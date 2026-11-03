package com.truesim.ui.core.input;

import com.truesim.annotations.AllowAllocation;

/**
 * What the UI received this frame, filled at frame start by the engine (or a test) before the UI updates, so nothing
 * waits a frame: commands per player in arrival order, the pointers' positions and buttons, committed text and the
 * IME's composition. Fixed-size and reused; adding allocates nothing.
 */
public final class UiInput {

    /** Command flag: the selection extends (Shift held with a move). */
    public static final int EXTEND_SELECTION = 1;

    /** The most pointers tracked at once (mouse, fingers, pens, XR rays). */
    public static final int MAX_POINTERS = 16;

    private final int[] commands;
    private final int[] players;
    private final int[] flags;
    private int commandCount;

    private final Pointer[] pointers = new Pointer[MAX_POINTERS];
    private int pointerCount;

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder composition = new StringBuilder();
    private int compositionCursor;

    /** Room for {@code maxCommands} commands a frame (more are dropped and counted). */
    @AllowAllocation("one per UI")
    public UiInput(int maxCommands) {
        commands = new int[maxCommands];
        players = new int[maxCommands];
        flags = new int[maxCommands];
        for (int i = 0; i < MAX_POINTERS; i++) {
            pointers[i] = new Pointer();
        }
    }

    /** Forgets last frame's commands and text; pointers stay where they are. */
    public void beginFrame() {
        commandCount = 0;
        text.setLength(0);
    }

    /** Adds a command from {@code player}; false if this frame's room is used up. */
    public boolean command(UiCommand command, int player, int commandFlags) {
        if (commandCount == commands.length) {
            return false;
        }
        commands[commandCount] = command.ordinal();
        players[commandCount] = player;
        flags[commandCount] = commandFlags;
        commandCount++;
        return true;
    }

    /** Commands this frame. */
    public int commandCount() {
        return commandCount;
    }

    /** Command {@code index} of this frame. */
    public UiCommand command(int index) {
        return UiCommand.of(commands[index]);
    }

    /** The player of command {@code index}. */
    public int player(int index) {
        return players[index];
    }

    /** The flags of command {@code index}. */
    public int flags(int index) {
        return flags[index];
    }

    /** Whether {@code command} arrived this frame (from any player). */
    public boolean has(UiCommand command) {
        for (int i = 0; i < commandCount; i++) {
            if (commands[i] == command.ordinal()) {
                return true;
            }
        }
        return false;
    }

    /** The pointer with {@code id}, made active if it was not: a source updates it each frame. */
    public Pointer pointer(int id, Pointer.Kind kind) {
        for (int i = 0; i < pointerCount; i++) {
            if (pointers[i].id() == id) {
                return pointers[i];
            }
        }
        if (pointerCount == MAX_POINTERS) {
            throw new IllegalStateException("more than " + MAX_POINTERS + " pointers");
        }
        Pointer pointer = pointers[pointerCount++];
        pointer.reset(id, kind);
        return pointer;
    }

    /** Removes the pointer with {@code id} (a finger lifted, a controller gone). */
    public void removePointer(int id) {
        for (int i = 0; i < pointerCount; i++) {
            if (pointers[i].id() == id) {
                Pointer gone = pointers[i];
                pointers[i] = pointers[--pointerCount];
                pointers[pointerCount] = gone;
                return;
            }
        }
    }

    /** Active pointers. */
    public int pointerCount() {
        return pointerCount;
    }

    /** Active pointer {@code index}. */
    public Pointer pointerAt(int index) {
        return pointers[index];
    }

    /** Text committed this frame (typed or from the IME). */
    public void text(CharSequence committed) {
        text.append(committed);
    }

    /** Text committed this frame. */
    public CharSequence text() {
        return text;
    }

    /** The IME's composition now (empty when none), with its cursor. */
    public void composition(CharSequence editing, int cursor) {
        composition.setLength(0);
        composition.append(editing);
        compositionCursor = cursor;
    }

    /** The IME's composition. */
    public CharSequence composition() {
        return composition;
    }

    /** The cursor in the composition. */
    public int compositionCursor() {
        return compositionCursor;
    }
}
