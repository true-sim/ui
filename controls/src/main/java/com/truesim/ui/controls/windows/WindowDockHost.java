package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.overlay.HostBox;
import java.util.HashMap;
import java.util.Map;

/**
 * Undocked panels in floating windows of a {@link WindowManager} (one window per panel, its id the panel's): the
 * window's title is the panel's, closing it closes the panel, and dragging it by its title over a dock space docks it
 * again.
 */
public final class WindowDockHost implements DockHost {

    private final WindowManager windows;
    private final Map<DockPanel, FloatingWindow> byPanel = new HashMap<>();

    /** Panels float in windows of {@code windows}. */
    @AllowAllocation("one per dock space")
    public WindowDockHost(WindowManager windows) {
        this.windows = windows;
    }

    /** The window manager. */
    public WindowManager windows() {
        return windows;
    }

    /** The window {@code panel} floats in, or null. */
    public FloatingWindow window(DockPanel panel) {
        FloatingWindow w = byPanel.get(panel);
        return w != null && w.isOpen() ? w : null;
    }

    @Override
    @AllowAllocation("a panel undocks on user action")
    public void undock(DockPanel panel, float x, float y, float width, float height) {
        FloatingWindow w = byPanel.get(panel);
        if (w == null) {
            w = windows.find(panel.id());
            if (w == null) {
                w = windows.create(panel.id(), panel.title());
            }
            w.listener(new Closer(panel));
            w.dockSource(panel);
            byPanel.put(panel, w);
        }
        w.title(panel.title());
        w.content().add(panel.content());
        w.bounds(x, y, width, height);
        w.restore();
        w.open();
    }

    @Override
    public void release(DockPanel panel) {
        FloatingWindow w = byPanel.get(panel);
        if (w != null && w.isOpen()) {
            w.close();
        }
    }

    @Override
    public void raise(DockPanel panel) {
        FloatingWindow w = byPanel.get(panel);
        if (w != null && w.isOpen()) {
            w.open();
        }
    }

    @Override
    public boolean bounds(DockPanel panel, float[] out) {
        FloatingWindow w = window(panel);
        if (w == null) {
            return false;
        }
        out[0] = w.x();
        out[1] = w.y();
        out[2] = w.width();
        out[3] = w.height();
        return true;
    }

    @Override
    public void toHost(float x, float y, float[] out) {
        int host = windows.host().slot();
        float scale = HostBox.scale(windows.ui());
        out[0] = (x - HostBox.left(windows.ui(), host)) / scale;
        out[1] = (y - HostBox.top(windows.ui(), host)) / scale;
    }

    /** Closing the window closes the panel. */
    private static final class Closer implements FloatingWindow.Listener {
        private final DockPanel panel;

        Closer(DockPanel panel) {
            this.panel = panel;
        }

        @Override
        public boolean closing(FloatingWindow window) {
            return panel.listener.closing(panel);
        }

        @Override
        public void closed(FloatingWindow window) {
            if (panel.floating) {
                panel.space().closedFloating(panel);
            }
        }
    }
}
