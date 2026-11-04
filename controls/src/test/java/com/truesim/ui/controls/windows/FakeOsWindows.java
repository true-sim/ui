package com.truesim.ui.controls.windows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OS windows in memory: a desktop of monitors, the main window and the windows docking opens, a cursor; windows move
 * where asked at once (as SDL's do after a sync). For tests of {@link OsDockHost} without an engine.
 */
final class FakeOsWindows implements OsWindows {

    /** One fake window. */
    static final class Win {
        final int id;
        String title;
        int x;
        int y;
        int w;
        int h;
        boolean maximized;
        boolean closed;
        float pixelRatio = 1;
        float contentScale = 1;

        Win(int id, String title, int x, int y, int w, int h) {
            this.id = id;
            this.title = title;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    final Map<Integer, Win> windows = new LinkedHashMap<>();
    final List<Monitor> monitors = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    float cursorX;
    float cursorY;
    boolean globalCursor = true;
    int nextId = 100;
    int raised;

    /** A desktop of one 1920 x 1080 primary monitor (task bar 40 high), the main window at 100, 100, w x h. */
    FakeOsWindows(int mainWidth, int mainHeight) {
        monitors.add(new Monitor("Primary", 0, 0, 1920, 1080, 0, 0, 1920, 1040, true));
        windows.put(MAIN, new Win(MAIN, "main", 100, 100, mainWidth, mainHeight));
    }

    Win main() {
        return windows.get(MAIN);
    }

    /** The open windows docking made, in the order they opened. */
    List<Win> open() {
        List<Win> out = new ArrayList<>();
        for (Win w : windows.values()) {
            if (w.id != MAIN && !w.closed) {
                out.add(w);
            }
        }
        return out;
    }

    Win win(int id) {
        return windows.get(id);
    }

    @Override
    public List<Monitor> monitors() {
        return List.copyOf(monitors);
    }

    @Override
    public boolean cursor(float[] out) {
        out[0] = cursorX;
        out[1] = cursorY;
        return globalCursor;
    }

    @Override
    public int open(String title, int x, int y, int width, int height) {
        int id = nextId++;
        windows.put(id, new Win(id, title, x, y, width, height));
        calls.add("open " + title + " " + x + " " + y + " " + width + " " + height);
        return id;
    }

    @Override
    public void close(int window) {
        windows.get(window).closed = true;
        calls.add("close " + window);
    }

    @Override
    public void move(int window, int x, int y) {
        Win w = windows.get(window);
        w.x = x;
        w.y = y;
    }

    @Override
    public void title(int window, String title) {
        windows.get(window).title = title;
    }

    @Override
    public void raise(int window) {
        raised = window;
    }

    @Override
    public void maximize(int window, boolean on) {
        Win w = windows.get(window);
        w.maximized = on;
        calls.add((on ? "maximize " : "restore ") + window);
    }

    @Override
    public boolean maximized(int window) {
        Win w = windows.get(window);
        return w != null && w.maximized;
    }

    @Override
    public boolean bounds(int window, int[] out) {
        Win w = windows.get(window);
        if (w == null || w.closed) {
            return false;
        }
        out[0] = w.x;
        out[1] = w.y;
        out[2] = w.w;
        out[3] = w.h;
        return true;
    }

    @Override
    public float pixelRatio(int window) {
        Win w = windows.get(window);
        return w == null ? 1 : w.pixelRatio;
    }

    @Override
    public float contentScale(int window) {
        Win w = windows.get(window);
        return w == null ? 1 : w.contentScale;
    }
}
