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
 * Scripted input for tests (design section 12, "scripted action and pointer playback, no devices"): commands, text, a
 * mouse and further pointers (fingers, pens, a second player's cursor) queued now, handed to the UI by the next poll,
 * as the engine's sources would at frame start. Pointers keep their state between polls until moved or lifted;
 * commands, text and scroll are handed over once.
 */
public final class SyntheticInput implements UiCommandSource, PointerSource {

    /** The mouse's pointer id. */
    public static final int MOUSE = 0;

    private static final int MAX = UiInput.MAX_POINTERS;

    private final Deque<UiCommand> commands = new ArrayDeque<>();
    private final Deque<Integer> commandPlayers = new ArrayDeque<>();
    private final Deque<Integer> commandFlags = new ArrayDeque<>();
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder composition = new StringBuilder();
    private int compositionCursor;
    private boolean composing;
    private int mnemonic;
    private boolean mnemonicsShown;

    private final int[] ids = new int[MAX];
    private final Pointer.Kind[] kinds = new Pointer.Kind[MAX];
    private final float[] xs = new float[MAX];
    private final float[] ys = new float[MAX];
    private final int[] buttons = new int[MAX];
    private final int[] modifiers = new int[MAX];
    private final int[] players = new int[MAX];
    private final float[] scrollX = new float[MAX];
    private final float[] scrollY = new float[MAX];
    private final float[] pressures = new float[MAX];
    private final float[] tiltsX = new float[MAX];
    private final float[] tiltsY = new float[MAX];
    private final float[] twists = new float[MAX];
    private final boolean[] used = new boolean[MAX];
    private final boolean[] lifted = new boolean[MAX];

    /** Nothing queued. */
    @AllowAllocation("test helper")
    public SyntheticInput() {
    }

    /** Queues a mnemonic key (a letter pressed with Alt) for the next frame; returns this. */
    public SyntheticInput mnemonic(int codePoint) {
        mnemonic = codePoint;
        return this;
    }

    /** Holds ({@code true}) or lets go of the mnemonic modifier (Alt): underlined letters show while held. */
    public SyntheticInput mnemonicsShown(boolean shown) {
        mnemonicsShown = shown;
        return this;
    }

    /** Queues commands of player 0 for the next frame; returns this. */
    @AllowAllocation("test helper")
    public SyntheticInput press(UiCommand... queued) {
        for (UiCommand command : queued) {
            command(command, 0, 0);
        }
        return this;
    }

    /** Queues a command of {@code player} with {@code flags} for the next frame; returns this. */
    @AllowAllocation("test helper")
    public SyntheticInput command(UiCommand command, int player, int flags) {
        commands.add(command);
        commandPlayers.add(player);
        commandFlags.add(flags);
        return this;
    }

    /** Queues committed text for the next frame; returns this. */
    @AllowAllocation("test helper")
    public SyntheticInput type(String typed) {
        text.append(typed);
        return this;
    }

    /** Sets the IME composition (empty ends it) for the next frame; returns this. */
    @AllowAllocation("test helper")
    public SyntheticInput compose(String editing, int cursor) {
        composition.setLength(0);
        composition.append(editing);
        compositionCursor = cursor;
        composing = true;
        return this;
    }

    /** Puts the mouse (pointer {@link #MOUSE}) at {@code x, y} with {@code buttonBits} held; returns this. */
    public SyntheticInput mouse(float x, float y, int buttonBits) {
        return pointer(MOUSE, Pointer.Kind.MOUSE, x, y, buttonBits);
    }

    /** Puts pointer {@code id} of {@code kind} at {@code x, y} with {@code buttonBits} held; returns this. */
    public SyntheticInput pointer(int id, Pointer.Kind kind, float x, float y, int buttonBits) {
        int i = index(id);
        kinds[i] = kind;
        xs[i] = x;
        ys[i] = y;
        buttons[i] = buttonBits;
        lifted[i] = false;
        return this;
    }

    /**
     * Puts pointer {@code id}, a {@linkplain Pointer.Kind#PEN pen}, at {@code x, y} with {@code buttonBits} held,
     * pressing with {@code pressure} (0 to 1) at a tilt of {@code tiltX, tiltY} degrees; returns this.
     */
    public SyntheticInput pen(int id, float x, float y, int buttonBits, float pressure, float tiltX, float tiltY) {
        pointer(id, Pointer.Kind.PEN, x, y, buttonBits);
        int i = index(id);
        pressures[i] = pressure;
        tiltsX[i] = tiltX;
        tiltsY[i] = tiltY;
        return this;
    }

    /** Sets the pressure pointer {@code id} reports (0 to 1; {@link Pointer#NO_PRESSURE}: none); returns this. */
    public SyntheticInput pressure(int id, float value) {
        pressures[index(id)] = value;
        return this;
    }

    /** Sets pointer {@code id}'s barrel rotation in degrees; returns this. */
    public SyntheticInput twist(int id, float degrees) {
        twists[index(id)] = degrees;
        return this;
    }

    /** Sets the modifiers pointer {@code id} reports; returns this. */
    public SyntheticInput modifiers(int id, int bits) {
        modifiers[index(id)] = bits;
        return this;
    }

    /** Sets the player owning pointer {@code id}; returns this. */
    public SyntheticInput player(int id, int player) {
        players[index(id)] = player;
        return this;
    }

    /** Scrolls pointer {@code id} by {@code dx, dy} in the next frame; returns this. */
    public SyntheticInput scroll(int id, float dx, float dy) {
        int i = index(id);
        scrollX[i] += dx;
        scrollY[i] += dy;
        return this;
    }

    /** Lifts pointer {@code id} (a finger leaves the screen): removed from the input at the next poll. */
    public SyntheticInput lift(int id) {
        for (int i = 0; i < MAX; i++) {
            if (used[i] && ids[i] == id) {
                lifted[i] = true;
            }
        }
        return this;
    }

    private int index(int id) {
        int free = -1;
        for (int i = 0; i < MAX; i++) {
            if (used[i] && ids[i] == id) {
                return i;
            }
            if (!used[i] && free < 0) {
                free = i;
            }
        }
        if (free < 0) {
            throw new IllegalStateException("more than " + MAX + " pointers");
        }
        used[free] = true;
        ids[free] = id;
        kinds[free] = Pointer.Kind.MOUSE;
        pressures[free] = Pointer.NO_PRESSURE;
        tiltsX[free] = 0;
        tiltsY[free] = 0;
        twists[free] = 0;
        return free;
    }

    @Override
    @AllowAllocation("test helper: queued commands unbox their player and flags")
    public void poll(UiInput input) {
        while (!commands.isEmpty()) {
            input.command(commands.poll(), commandPlayers.poll(), commandFlags.poll());
        }
        if (mnemonic != 0) {
            input.mnemonic(mnemonic);
            mnemonic = 0;
        }
        input.mnemonicsShown(mnemonicsShown);
        if (!text.isEmpty()) {
            input.text(text);
            text.setLength(0);
        }
        if (composing) {
            input.composition(composition, compositionCursor);
            composing = false;
        }
        for (int i = 0; i < MAX; i++) {
            if (!used[i]) {
                continue;
            }
            if (lifted[i]) {
                input.removePointer(ids[i]);
                used[i] = false;
                lifted[i] = false;
                continue;
            }
            input.pointer(ids[i], kinds[i]).moveTo(xs[i], ys[i]).buttons(buttons[i]).modifiers(modifiers[i])
                .player(players[i]).scroll(scrollX[i], scrollY[i]).tilt(tiltsX[i], tiltsY[i]).twist(twists[i]);
            if (pressures[i] >= 0) {
                input.pointer(ids[i], kinds[i]).pressure(pressures[i]);
            } else {
                input.pointer(ids[i], kinds[i]).noPressure();
            }
            scrollX[i] = 0;
            scrollY[i] = 0;
        }
    }
}
