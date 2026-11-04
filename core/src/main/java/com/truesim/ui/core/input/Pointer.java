package com.truesim.ui.core.input;

/**
 * One pointer (design 4.7): a mouse, a finger, a pen, a gamepad's virtual cursor, an XR ray. Reused: sources write it
 * every frame, so events are never allocated. Screen pointers have a position in UI pixels; XR pointers (and screen
 * pointers over a 3D view) also have a {@linkplain #ray world ray}, which scene picking and world panels test.
 */
public final class Pointer {

    /** What kind of device moves it. */
    public enum Kind { MOUSE, TOUCH, PEN, VIRTUAL_CURSOR, XR_RAY }

    /** Button bits. */
    public static final int PRIMARY = 1;
    public static final int SECONDARY = 2;
    public static final int MIDDLE = 4;

    /** Modifier bits ({@link #modifiers()}): Shift held (extends selections). */
    public static final int SHIFT = 1;
    /** Ctrl held. */
    public static final int CTRL = 2;
    /** Alt (Option) held. */
    public static final int ALT = 4;
    /** Meta (Cmd, Windows key) held. */
    public static final int META = 8;
    /** The platform's shortcut modifier held: Ctrl, or Cmd on macOS (toggles items in multi-selections). */
    public static final int SHORTCUT = 16;
    /** The {@code ui.pointer.dragCopy} action held: a drag copies. */
    public static final int DRAG_COPY = 32;
    /** The {@code ui.pointer.dragLink} action held: a drag links. */
    public static final int DRAG_LINK = 64;
    /** The {@code ui.gizmo.snap} action held: gizmo drags snap to their increments (or stop snapping). */
    public static final int GIZMO_SNAP = 128;

    private int id;
    private Kind kind = Kind.MOUSE;
    private int player;
    private float x;
    private float y;
    private int buttons;
    private float scrollX;
    private float scrollY;
    private int modifiers;
    private boolean hasRay;
    private double rayOriginX;
    private double rayOriginY;
    private double rayOriginZ;
    private double rayDirectionX;
    private double rayDirectionY;
    private double rayDirectionZ;

    void reset(int newId, Kind newKind) {
        id = newId;
        kind = newKind;
        player = 0;
        x = 0;
        y = 0;
        buttons = 0;
        scrollX = 0;
        scrollY = 0;
        modifiers = 0;
        hasRay = false;
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

    /** Sets the modifiers held ({@link #SHIFT} and the others); returns this. */
    public Pointer modifiers(int bits) {
        modifiers = bits;
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

    /** The modifiers held. */
    public int modifiers() {
        return modifiers;
    }

    /**
     * Gives the pointer a ray in world space for this frame: an XR controller's aim, or a screen pointer's camera ray
     * when the engine knows it (otherwise the scene bridge makes one from its view). Scene picking and world panels
     * test it; screen layers use {@link #x()}, {@link #y()}. Doubles, so it is exact far from the origin. The
     * direction need not be unit length; distances along it are in its units. Returns this.
     */
    public Pointer ray(double originX, double originY, double originZ, double directionX, double directionY,
        double directionZ) {
        hasRay = true;
        rayOriginX = originX;
        rayOriginY = originY;
        rayOriginZ = originZ;
        rayDirectionX = directionX;
        rayDirectionY = directionY;
        rayDirectionZ = directionZ;
        return this;
    }

    /** Takes the pointer's ray away (a screen pointer again); returns this. */
    public Pointer clearRay() {
        hasRay = false;
        return this;
    }

    /** Whether the pointer has a {@linkplain #ray world ray}. */
    public boolean hasRay() {
        return hasRay;
    }

    /** The ray's origin x. */
    public double rayOriginX() {
        return rayOriginX;
    }

    /** The ray's origin y. */
    public double rayOriginY() {
        return rayOriginY;
    }

    /** The ray's origin z. */
    public double rayOriginZ() {
        return rayOriginZ;
    }

    /** The ray's direction x. */
    public double rayDirectionX() {
        return rayDirectionX;
    }

    /** The ray's direction y. */
    public double rayDirectionY() {
        return rayDirectionY;
    }

    /** The ray's direction z. */
    public double rayDirectionZ() {
        return rayDirectionZ;
    }
}
