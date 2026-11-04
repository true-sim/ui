package com.truesim.ui.controls.debug;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.ControlsGallery;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.testkit.SyntheticInput;

/**
 * The UI debugger over the controls gallery (1280 x 800): the inspector and frame windows shown, the layout bounds
 * and clips overlays on, a button of the gallery selected (its path, state, rectangle and resolved styles with their
 * rules). For the debugger's tests and the engine's GPU screenshots.
 */
public final class DebuggerGallery {

    /** The UI. */
    public final Ui ui;
    /** The debugger. */
    public final UiDebugger debugger;
    /** The synthetic input the frames use. */
    public final SyntheticInput input = new SyntheticInput();
    private final UiInput frameInput = new UiInput(16);
    private final float width;
    private final float height;
    private long nanos = 1_000_000_000L;

    private DebuggerGallery(Ui ui, float width, float height) {
        this.ui = ui;
        this.width = width;
        this.height = height;
        ControlsGallery.registerIcons(ui);
        ControlsGallery.build(new Controls(ui), ui.root());
        debugger = new UiDebugger(ui);
    }

    /** Builds the gallery into {@code ui} and runs frames until it settles. */
    public static DebuggerGallery build(Ui ui, float width, float height) {
        DebuggerGallery g = new DebuggerGallery(ui, width, height);
        g.frames(30);
        g.debugger.show(true);
        g.debugger.overlay(UiDebugger.BOUNDS, true);
        g.debugger.overlay(UiDebugger.CLIPS, true);
        g.debugger.select(g.first("button"));
        g.frames(60);
        return g;
    }

    /** The first element of {@code type} in the HUD, depth first, or null. */
    public Element first(String type) {
        return first(ui.store().rootSlot(), type);
    }

    private Element first(int slot, String type) {
        UiStore store = ui.store();
        if (type.equals(store.type(slot))) {
            return store.element(slot);
        }
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            Element found = first(c, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Runs {@code n} frames of 16 ms with the synthetic input. */
    public DebuggerGallery frames(int n) {
        for (int i = 0; i < n; i++) {
            frameInput.beginFrame();
            input.poll(frameInput);
            ui.update(width, height, frameInput, nanos);
            nanos += 16_000_000L;
        }
        return this;
    }
}
