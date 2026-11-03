package com.truesim.ui.layout;

import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiStore;
import java.util.Arrays;

/**
 * A store, a layout and a measure for tests: text leaves are {@code chars} characters 8 units wide and 16 high, wrapped
 * by whole characters to the width they get; box leaves have a fixed content size.
 */
final class TestUi {

    static final float CHAR = 8;
    static final float LINE = 16;

    final UiStore store = new UiStore();
    final Tunable scale = Tunable.of("test.scale", 1, 0.25f, 8, "test");
    final Layout layout = new Layout(store, scale);
    int[] chars = new int[64];
    float[] boxW = new float[64];
    float[] boxH = new float[64];
    int measures;
    final Measure measure = this::measure;

    TestUi() {
        layout.snapping(false);
    }

    int root() {
        return store.rootSlot();
    }

    /** A plain element under {@code parent}. */
    int node(int parent) {
        int s = store.create("panel", null).slot();
        store.append(parent, s);
        return s;
    }

    /** A text leaf of {@code n} characters. */
    int text(int parent, int n) {
        int s = node(parent);
        grow(s);
        chars[s] = n;
        boxW[s] = -1;
        layout.measured(s, true);
        return s;
    }

    /** A leaf whose content is {@code w x h}. */
    int box(int parent, float w, float h) {
        int s = node(parent);
        grow(s);
        boxW[s] = w;
        boxH[s] = h;
        layout.measured(s, true);
        return s;
    }

    void setText(int s, int n) {
        chars[s] = n;
        store.markDirty(s, UiStore.DIRTY_LAYOUT);
    }

    private void grow(int s) {
        if (s >= chars.length) {
            int n = Math.max(s + 1, chars.length * 2);
            chars = Arrays.copyOf(chars, n);
            boxW = Arrays.copyOf(boxW, n);
            boxH = Arrays.copyOf(boxH, n);
        }
    }

    long measure(int slot, float w, int wm, float h, int hm) {
        measures++;
        if (boxW[slot] >= 0) {
            return Measure.size(boxW[slot], boxH[slot]);
        }
        return Measure.size(textWidth(chars[slot], w, wm), textHeight(chars[slot], w, wm));
    }

    static float textWidth(int n, float w, int wm) {
        int perLine = perLine(n, w, wm);
        return Math.min(n, perLine) * CHAR;
    }

    static float textHeight(int n, float w, int wm) {
        int perLine = perLine(n, w, wm);
        return n == 0 ? LINE : ((n + perLine - 1) / perLine) * LINE;
    }

    private static int perLine(int n, float w, int wm) {
        if (wm == Measure.UNDEFINED || n == 0) {
            return Math.max(n, 1);
        }
        return Math.max(1, (int) Math.floor(w / CHAR + 1e-3f));
    }

    /** One frame: layout, then the frame's end clears the dirty bits and the queue. */
    void run(float w, float h) {
        layout.update(measure, w, h);
        int[] q = store.dirtySlots();
        for (int i = 0; i < store.dirtyCount(); i++) {
            store.clearDirty(q[i], ~0);
        }
        store.clearDirtyQueue();
    }

    void run() {
        run(400, 300);
    }

    float[] rect(int s) {
        return new float[] {layout.x(s), layout.y(s), layout.width(s), layout.height(s)};
    }
}
