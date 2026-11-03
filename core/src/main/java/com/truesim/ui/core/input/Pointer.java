package com.truesim.ui.core.input;

/**
 * One pointer (design 4.7): a mouse, a finger, a pen, a gamepad's virtual cursor, an XR ray. Reused: sources write it
 * every frame, so events are never allocated. Screen pointers have a position in UI pixels; rays come with stage 4.
 */
public final class Pointer {

    /** What kind of device moves it. */
    public enum Kind { MOUSE, TOUCH, PEN, VIRTUAL_CURSOR, XR_RAY }

    /** Button bits. */
    public static final int PRIMARY = 1;
    public static final int SECONDARY = 2;
    public static final int MIDDLE = 4;

    private int id;
    private Kind kind = Kind.MOUSE;
    private int player;
    private float x;
    private float y;
    private int buttons;
    private float scrollX;
    private float scrollY;

    void reset(int newId, Kind newKind) {
        id = newId;
        kind = newKind;
        player = 0;
        x = 0;
        y = 0;
        buttons = 0;
        scrollX = 0;
        scrollY = 0;
    }

    /** Sets where it is, in UI pixels; returns this. */
    public Pointer moveTo(float newX, float newY) {
        x = newX;
        y = newY;
        return this;
    }

    /** Sets the buttons held; returns this. */
    public Pointer buttons(int bits) {
        buttons = bits;
        return this;
    }

    /** Sets this frame's scroll; returns this. */
    public Pointer scroll(float dx, float dy) {
        scrollX = dx;
        scrollY = dy;
        return this;
    }

    /** Sets the owning player; returns this. */
    public Pointer player(int index) {
        player = index;
        return this;
    }

    public int id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public int player() {
        return player;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public int buttons() {
        return buttons;
    }

    public float scrollX() {
        return scrollX;
    }

    public float scrollY() {
        return scrollY;
    }
}
