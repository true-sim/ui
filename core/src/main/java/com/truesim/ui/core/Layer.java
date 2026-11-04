package com.truesim.ui.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.DrawList;

/**
 * One layer of a UI's {@link Layers} stack: a root element covering the screen, its own {@link DrawList}, its place
 * in the z-order and how it takes input. Elements belong to the layer whose root they are under; layers draw back to
 * front by z, and picking runs front to back.
 *
 * <p><b>Input.</b> {@link Input#PASS_THROUGH} (the default) takes pointer input only on its interactive elements; a
 * pointer anywhere else falls through to the layers below (and, past the last one, to the game). {@link Input#BLOCK}
 * takes the whole screen while the layer shows anything (its root has children): nothing below gets pointer input,
 * as under a modal backdrop. {@link Input#NONE} never takes input (tooltips, drag previews, debug overlays). A
 * {@linkplain #modal() modal} layer blocks like {@code BLOCK} and also traps focus inside itself while it shows
 * anything; {@code CANCEL} then closes its top-most modal scope.
 */
public final class Layer {

    /** How a layer takes pointer input. */
    public enum Input {
        /** Only its interactive elements take pointers; elsewhere they fall through. */
        PASS_THROUGH,
        /** Takes every pointer over the screen while it shows anything. */
        BLOCK,
        /** Takes no input. */
        NONE
    }

    private final String name;
    private final int z;
    private final Element root;
    private final DrawList list;
    private Input input;
    private boolean modal;

    @AllowAllocation("layers are made when a UI is built")
    Layer(String name, int z, Element root, Input input, boolean modal) {
        this.name = name;
        this.z = z;
        this.root = root;
        this.list = new DrawList();
        this.input = input;
        this.modal = modal;
    }

    /** The layer's name: its root's element id ({@code hud}, {@code popup}). */
    public String name() {
        return name;
    }

    /** The z-order: higher draws over lower and is picked first. */
    public int z() {
        return z;
    }

    /** The root element: add the layer's content under it. */
    public Element root() {
        return root;
    }

    /** The root's slot. */
    public int rootSlot() {
        return root.slot();
    }

    /** The layer's primitives. */
    public DrawList drawList() {
        return list;
    }

    /** How the layer takes pointer input. */
    public Input input() {
        return input;
    }

    /** Sets how the layer takes pointer input; returns this. */
    public Layer input(Input value) {
        input = value;
        return this;
    }

    /** Whether the layer is modal: it blocks the layers below and traps focus while it shows anything. */
    public boolean modal() {
        return modal;
    }

    /** Makes the layer modal or not; returns this. */
    public Layer modal(boolean value) {
        modal = value;
        return this;
    }

    /** Whether the layer shows anything: its root has a child. */
    public boolean showing() {
        return root.store().firstChild(root.slot()) != UiStore.NONE;
    }

    /** Whether the layer takes every pointer below it now: blocking or modal, and showing something. */
    public boolean blocking() {
        return (modal || input == Input.BLOCK) && input != Input.NONE && showing();
    }

    @Override
    public String toString() {
        return "Layer[" + name + " z=" + z + "]";
    }
}
