package com.truesim.ui.controls.windows;

/**
 * Where a dock space's undocked panels go. Two kinds:
 *
 * <ul>
 * <li>Floating one by one: by default in floating windows of the same UI ({@link WindowDockHost}); the space marks the
 * panel floating and the host shows its content ({@link #undock}, {@link #release}, {@link #raise}, {@link #bounds}).
 * <li>Into dock areas of their own ({@link #areas()} true): a host with OS windows ({@link OsDockHost}) opens a window
 * per area ({@link #openArea}), which the space docks the panel into, so more panels dock there, split and tab as in
 * the main area, and move between windows. A tab dragged beyond every window ({@link #outside}) tears out into a new
 * window that follows the pointer until released ({@link #follow}); closing a window re-docks its panels where they
 * last were in the main area ({@link DockSpace#closeArea}).
 * </ul>
 *
 * Positions a host takes are its own units ({@link #toHost}): panel units of the UI for floating windows, screen
 * coordinates for OS windows.
 */
public interface DockHost {

    /**
     * Shows {@code panel}'s content ({@link DockPanel#content()}) floating at {@code x, y}, {@code width x height}
     * (panel units of the host's own space). The panel is marked floating by the dock space. Not called for a host of
     * {@link #areas()}.
     */
    void undock(DockPanel panel, float x, float y, float width, float height);

    /** Takes {@code panel} back: it docked again or closed; its content was moved out already. */
    void release(DockPanel panel);

    /** Brings {@code panel}'s floating host to the front. */
    void raise(DockPanel panel);

    /**
     * Where {@code panel} floats now ({@code x, y, width, height} in panel units, into {@code out}), for a saved
     * layout; false if it does not float here.
     */
    boolean bounds(DockPanel panel, float[] out);

    /** Converts a pointer position in UI pixels to the host's units, into {@code out} (x, y). */
    void toHost(float x, float y, float[] out);

    /** Whether undocked panels go into dock areas of their own (OS windows) rather than floating one by one. */
    default boolean areas() {
        return false;
    }

    /**
     * Opens a window for a new dock area of {@code space} ({@link DockSpace#newArea}) at {@code x, y} (host units),
     * {@code width x height} panel units, titled {@code title}, and returns the area (empty: the space docks into it).
     */
    default DockArea openArea(DockSpace space, float x, float y, float width, float height, String title) {
        throw new UnsupportedOperationException("this host floats panels one by one");
    }

    /** {@code area} emptied or its window closes: close the window it is in. */
    default void closeArea(DockArea area) {
        // nothing by default
    }

    /** The title of {@code area}'s window should now be {@code title} (its first pane's shown panel). */
    default void titleArea(DockArea area, String title) {
        // nothing by default
    }

    /** Brings {@code area}'s window to the front. */
    default void raiseArea(DockArea area) {
        // nothing by default
    }

    /**
     * Whether a pointer at {@code x, y} (UI pixels) is beyond every window of the UI: a tab dragged there tears out
     * into a window of its own.
     */
    default boolean outside(float x, float y) {
        return false;
    }

    /**
     * Makes {@code area}'s window follow the pointer of the drag of {@code panel} until it ends, held {@code grabX,
     * grabY} (host units) from its top left; null stops. Called when a tab tears out, and when the only tab of a
     * window is dragged.
     */
    default void follow(DockArea area, float grabX, float grabY) {
        // nothing by default
    }

    /** The area whose window follows the pointer now, or null. */
    default DockArea following() {
        return null;
    }

    /** {@code area}'s window as data, its tree {@code root}, for a saved layout; null to leave it out. */
    default DockLayout.Window describe(DockArea area, DockLayout.Node root) {
        return null;
    }

    /** Opens a window as {@code saved} says (its monitor, place, size, maximised) for a new area of {@code space}. */
    default DockArea restoreArea(DockSpace space, DockLayout.Window saved) {
        return null;
    }
}
