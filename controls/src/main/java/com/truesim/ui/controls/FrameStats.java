package com.truesim.ui.controls;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * What a {@link Ui}'s last frame cost, phase by phase, for the UI debugger (design stage 15): input processing,
 * frame listeners and animations, styles, layout (with its after-layout passes), recording, the bytes the renderer
 * uploads for it, and which elements recorded. Off by default ({@link #enabled}); on, a frame takes a few clock
 * reads more and nothing is allocated.
 */
public final class FrameStats {

    boolean enabled;
    long input;
    long listeners;
    long styles;
    long layout;
    long record;
    long total;
    int passes;
    int recorded;
    long uploadBytes;
    private int[] slots = new int[256];
    private int count;
    private int[] lastSlots = new int[256];
    private int lastCount;

    FrameStats() {
    }

    /** Turns the measuring on or off; returns this. */
    public FrameStats enabled(boolean on) {
        enabled = on;
        return this;
    }

    /** Whether frames are measured. */
    public boolean enabled() {
        return enabled;
    }

    /** Nanoseconds the last {@code Ui.input} took (picking, events, focus, commands). */
    public long inputNanos() {
        return input;
    }

    /** Nanoseconds of the last update's frame listeners and animations (before styles). */
    public long listenerNanos() {
        return listeners;
    }

    /** Nanoseconds of the last update's style resolution (of changed elements; tokens and sheet reloads). */
    public long styleNanos() {
        return styles;
    }

    /** Nanoseconds of the last update's layout, with the listeners' after-layout passes. */
    public long layoutNanos() {
        return layout;
    }

    /** Nanoseconds of the last update's recording into the draw lists. */
    public long recordNanos() {
        return record;
    }

    /** Nanoseconds of the whole last update. */
    public long totalNanos() {
        return total;
    }

    /** Layout passes of the last update (1 unless listeners changed layout after it). */
    public int passes() {
        return passes;
    }

    /** Elements recorded in the last update. */
    public int recorded() {
        return recorded;
    }

    /** Bytes the renderer uploads for the layers now: their changed primitives' words and changed clip tables. */
    public long uploadBytes() {
        return uploadBytes;
    }

    /** How many elements the last update recorded ({@link #recordedSlot}). */
    public int recordedCount() {
        return lastCount;
    }

    /** The slot of the {@code i}-th element the last update recorded: the dirty ranges' owners. */
    public int recordedSlot(int i) {
        return lastSlots[i];
    }

    /** The frame's recorded elements become the last frame's. */
    void endFrame() {
        int[] t = lastSlots;
        lastSlots = slots;
        slots = t;
        lastCount = count;
        count = 0;
    }

    void recorded(int slot) {
        if (count == slots.length) {
            slots = grow(slots);
        }
        slots[count++] = slot;
    }

    @AllowAllocation("grows to the most elements a frame recorded")
    private static int[] grow(int[] a) {
        return Arrays.copyOf(a, a.length * 2);
    }
}
