package com.truesim.ui.controls.windows;

import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeyHolder;

/**
 * The style keys of tooltips, floating windows and docking, besides the standard ones and {@code OverlayStyles}'
 * placement keys. Registered for {@code .tss} as a {@link StyleKeyHolder} service.
 */
public final class WindowStyles implements StyleKeyHolder {

    /** Whether a tooltip sits at its target ({@code anchored}) or follows the pointer ({@code follow}). */
    public static final StyleKey<TooltipMode> TOOLTIP_MODE =
        StyleKey.of("tooltip-mode", TooltipMode.class, TooltipMode.ANCHORED);
    /**
     * How thick a window's resize edges are (read from the window), in panel units: half inside the window's box, half
     * outside it; the corners are squares of that size.
     */
    public static final StyleKey<Float> RESIZE_HANDLE = StyleKey.number("resize-handle", 8, 0, 256);
    /**
     * How deep a dock pane's side drop zones reach into it, as a fraction of its width or height (read from the dock
     * space): a tab dropped nearer an edge than this splits the pane there; elsewhere it joins the pane's tabs.
     */
    public static final StyleKey<Float> DOCK_ZONE = StyleKey.number("dock-zone", 0.3f, 0.05f, 0.5f);
    /** The smallest a docked pane gets when a splitter is dragged, in panel units (read from the dock space). */
    public static final StyleKey<Float> DOCK_MIN_PANE = StyleKey.number("dock-min-pane", 48, 0, 10_000);

    /**
     * The opacity a window is drawn at while it is dragged (read from a floating {@code window} and a
     * {@code dock-window}), so the dock targets under it show; 1 turns it off. Floating windows multiply their
     * subtree's opacity by it; OS windows ask the system ({@link OsWindows#opacity}) and stay opaque where it refuses.
     */
    public static final StyleKey<Float> DRAG_OPACITY = StyleKey.number("drag-opacity", 1, 0.05f, 1);

    /** For the service loader: creating it registers the keys. */
    public WindowStyles() {
    }
}
