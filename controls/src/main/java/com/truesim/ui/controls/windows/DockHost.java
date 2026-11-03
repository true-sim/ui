package com.truesim.ui.controls.windows;

/**
 * Where a dock space's undocked panels float: by default a floating window of the same UI ({@link WindowDockHost}).
 * The hook for separate OS windows: an engine with several windows implements a host that opens an OS window with a
 * UI of its own per panel (the panel's content moved into it) and reports drags of that window back through
 * {@link DockSpace#dragFloating} and {@link DockSpace#dropFloating}, so it docks again; that needs the engine's side
 * (an OS window per viewport, its own presenter and input), not built here.
 */
public interface DockHost {

    /**
     * Shows {@code panel}'s content ({@link DockPanel#content()}) floating at {@code x, y}, {@code width x height}
     * (panel units of the host's own space). The panel is marked floating by the dock space.
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

    /** Converts a pointer position in UI pixels to the host's panel units, into {@code out} (x, y). */
    void toHost(float x, float y, float[] out);
}
