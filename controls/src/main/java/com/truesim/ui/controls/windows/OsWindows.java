package com.truesim.ui.controls.windows;

import java.util.List;

/**
 * The OS windows a dock space's panels tear out into ({@link OsDockHost}): implemented by an engine with several
 * windows (engine-ui does it over SDL) or by a fake in tests. Windows are named by the implementation's ids;
 * {@link #MAIN} is the window the UI is shown in. Positions and sizes are in screen coordinates (the desktop's, which
 * on a high-density display may differ from pixels: {@link #pixelRatio}).
 *
 * <p>{@link #bounds}, {@link #cursor}, {@link #pixelRatio} and {@link #contentScale} are asked every frame and must
 * not allocate; the others are called on user actions.
 */
public interface OsWindows {

    /** The id of the window the UI is shown in. */
    int MAIN = 0;

    /**
     * A monitor: its {@code key} (stable from run to run: what a saved layout names it by), its bounds and the part not
     * covered by task bars ({@code usable*}), in screen coordinates, and whether it is the primary one.
     */
    record Monitor(String key, int x, int y, int width, int height, int usableX, int usableY, int usableWidth,
        int usableHeight, boolean primary) {
    }

    /** The connected monitors, the primary one first. */
    List<Monitor> monitors();

    /**
     * Where the mouse cursor is on the screen, into {@code out} (x, y); false if the system does not tell (Wayland
     * gives no global position): the pointer's position in the UI is used instead.
     */
    boolean cursor(float[] out);

    /** Opens a window titled {@code title} at {@code x, y}, {@code width x height}; returns its id (not MAIN). */
    int open(String title, int x, int y, int width, int height);

    /** Closes window {@code window}. */
    void close(int window);

    /** Moves window {@code window}'s top left to {@code x, y}. */
    void move(int window, int x, int y);

    /** Retitles window {@code window}. */
    void title(int window, String title);

    /** Brings window {@code window} to the front (and gives it the keyboard). */
    void raise(int window);

    /** Maximises window {@code window}, or restores it. */
    void maximize(int window, boolean on);

    /** Whether window {@code window} is maximised. */
    boolean maximized(int window);

    /** Window {@code window}'s place and size (x, y, width, height) into {@code out}; false if it is gone. */
    boolean bounds(int window, int[] out);

    /** Window {@code window}'s pixels per screen coordinate (2 on a Retina display, else 1). */
    float pixelRatio(int window);

    /**
     * The scale window {@code window}'s content is shown at (the display's content scale times its pixel density,
     * SDL's window display scale): the UI keeps its size in points across monitors through it.
     */
    float contentScale(int window);
}
