package com.truesim.ui.core.event;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.Cursor;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A UI without layout or styles for interaction tests: rectangles and style keys set by hand, a clock, a log. */
final class Rig {

    final UiStore store = new UiStore();
    final Layers layers = new Layers(store);
    final Geo geo = new Geo();
    final Interaction ia = new Interaction(layers, geo);
    final UiInput input = new UiInput(32);
    final List<String> log = new ArrayList<>();
    long now = 1_000_000_000L;

    /** Rectangles, radii, clips, pointer-events and cursors per slot. */
    static final class Geo implements PickGeometry {
        float[] rect = new float[256 * 4];
        float[] radii = new float[256 * 4];
        boolean[] clip = new boolean[256];
        boolean[] hidden = new boolean[256];
        PointerEvents[] events = new PointerEvents[256];
        Cursor[] cursors = new Cursor[256];

        Geo() {
            Arrays.fill(events, PointerEvents.AUTO);
            Arrays.fill(cursors, Cursor.AUTO);
            rect[2] = 1000; // the root covers the screen
            rect[3] = 1000;
        }

        @Override
        public float x(int slot) {
            return rect[slot * 4];
        }

        @Override
        public float y(int slot) {
            return rect[slot * 4 + 1];
        }

        @Override
        public float width(int slot) {
            return rect[slot * 4 + 2];
        }

        @Override
        public float height(int slot) {
            return rect[slot * 4 + 3];
        }

        @Override
        public boolean shown(int slot) {
            return !hidden[slot];
        }

        @Override
        public float radius(int slot, int corner) {
            return radii[slot * 4 + corner];
        }

        @Override
        public boolean clips(int slot) {
            return clip[slot];
        }

        @Override
        public PointerEvents pointerEvents(int slot) {
            return events[slot];
        }

        @Override
        public Cursor cursor(int slot) {
            return cursors[slot];
        }
    }

    Element root() {
        return store.root();
    }

    /** A new element named {@code id} under {@code parent} at {@code x, y, w, h}. */
    Element box(Element parent, String id, float x, float y, float w, float h) {
        Element e = store.create("panel", id);
        parent.add(e);
        place(e, x, y, w, h);
        return e;
    }

    /** A new element that listens to the pointer (so it is picked) and logs what it gets. */
    Element button(Element parent, String id, float x, float y, float w, float h) {
        Element e = box(parent, id, x, y, w, h);
        logAll(e);
        return e;
    }

    void place(Element e, float x, float y, float w, float h) {
        int o = e.slot() * 4;
        geo.rect[o] = x;
        geo.rect[o + 1] = y;
        geo.rect[o + 2] = w;
        geo.rect[o + 3] = h;
        ia.picking().invalidateAll();
    }

    /** Logs every event at {@code e} (bubble and target phase). */
    void logAll(Element e) {
        ia.on(e, EventType.ALL_MASK, this::record);
    }

    /** Logs every event at {@code e} in the capture phase. */
    void logCapture(Element e) {
        ia.onCapture(e, EventType.ALL_MASK, this::record);
    }

    void record(UiEvent e) {
        log.add(e.type() + " " + name(e.targetSlot()) + (e.phase() == Phase.TARGET ? ""
            : " " + e.phase().name().toLowerCase() + "@" + name(e.currentSlot())));
    }

    String name(int slot) {
        if (slot == UiStore.NONE) {
            return "-";
        }
        String id = store.id(slot);
        return id == null ? "#" + slot : id;
    }

    /** The log since the last call, of events of {@code types} only (all when none given). */
    List<String> take(EventType... types) {
        List<String> out = new ArrayList<>();
        for (String line : log) {
            if (types.length == 0) {
                out.add(line);
                continue;
            }
            for (EventType t : types) {
                if (line.startsWith(t.name() + " ")) {
                    out.add(line);
                }
            }
        }
        log.clear();
        return out;
    }

    Pointer mouse(float x, float y, int buttons) {
        return input.pointer(0, Pointer.Kind.MOUSE).moveTo(x, y).buttons(buttons);
    }

    Pointer pointer(int id, Pointer.Kind kind, float x, float y, int buttons) {
        return input.pointer(id, kind).moveTo(x, y).buttons(buttons);
    }

    Rig command(UiCommand command) {
        input.command(command, 0, 0);
        return this;
    }

    Rig command(UiCommand command, int player) {
        input.command(command, player, 0);
        return this;
    }

    /** Runs one frame of input, then forgets its commands and text. */
    Rig frame() {
        ia.process(input, now);
        input.beginFrame();
        return this;
    }

    /** Moves the clock on by {@code seconds}. */
    Rig advance(float seconds) {
        now += (long) (seconds * 1e9);
        return this;
    }

    /** A click of the mouse at {@code x, y}: press in one frame, release in the next. */
    Rig click(float x, float y) {
        mouse(x, y, Pointer.PRIMARY);
        frame();
        mouse(x, y, 0);
        return frame();
    }
}
