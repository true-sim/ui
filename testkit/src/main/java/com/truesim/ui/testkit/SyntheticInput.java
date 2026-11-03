package com.truesim.ui.testkit;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.PointerSource;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiCommandSource;
import com.truesim.ui.core.input.UiInput;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Scripted input for tests (design section 12, "scripted action and pointer playback, no devices"): commands and a
 * mouse queued now, handed to the UI by the next poll, as the engine's sources would at frame start.
 */
public final class SyntheticInput implements UiCommandSource, PointerSource {

    private final Deque<UiCommand> commands = new ArrayDeque<>();
    private final StringBuilder text = new StringBuilder();
    private float mouseX;
    private float mouseY;
    private int buttons;
    private boolean mouse;

    /** Nothing queued. */
    @AllowAllocation("test helper")
    public SyntheticInput() {
    }

    /** Queues commands for the next frame; returns this. */
    @AllowAllocation("test helper")
    public SyntheticInput press(UiCommand... queued) {
        for (UiCommand command : queued) {
            commands.add(command);
        }
        return this;
    }

    /** Queues committed text for the next frame; returns this. */
    @AllowAllocation("test helper")
    public SyntheticInput type(String typed) {
        text.append(typed);
        return this;
    }

    /** Puts the mouse at {@code x, y} with {@code buttonBits} held; returns this. */
    public SyntheticInput mouse(float x, float y, int buttonBits) {
        mouse = true;
        mouseX = x;
        mouseY = y;
        buttons = buttonBits;
        return this;
    }

    @Override
    public void poll(UiInput input) {
        while (!commands.isEmpty()) {
            input.command(commands.poll(), 0, 0);
        }
        if (!text.isEmpty()) {
            input.text(text);
            text.setLength(0);
        }
        if (mouse) {
            input.pointer(0, Pointer.Kind.MOUSE).moveTo(mouseX, mouseY).buttons(buttons);
        }
    }
}
