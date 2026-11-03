package com.truesim.ui.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.DrawList;
import java.util.Arrays;

/**
 * The layer stack of one UI: named layers ordered by z, each a root of the store with its own draw list. The
 * {@link #HUD} layer is the store's {@link UiStore#root() root}; the other standard layers are made the first time
 * they are asked for, and a game adds its own between them by z ({@link #add}).
 *
 * <p>Standard layers, back to front: {@link #HUD} (0), {@link #OVERLAY} (100, floating windows and docked panels),
 * {@link #MODAL} (200, modal dialogs: modal), {@link #POPUP} (300, menus, drop-downs, popovers: above modal dialogs so
 * a dialog's combo box opens over it), {@link #TOOLTIP} (400, no input), {@link #DRAG} (500, drag previews, no input)
 * and {@link #DEBUG} (1000, no input). Layer roots other than the HUD's are elements of type {@code layer} with the
 * layer's name as id, so styles select {@code layer#modal}.
 */
public final class Layers {

    public static final String HUD = "hud";
    public static final String OVERLAY = "overlay";
    public static final String MODAL = "modal";
    public static final String POPUP = "popup";
    public static final String TOOLTIP = "tooltip";
    public static final String DRAG = "drag";
    public static final String DEBUG = "debug";

    private static final String[] STANDARD = {HUD, OVERLAY, MODAL, POPUP, TOOLTIP, DRAG, DEBUG};
    private static final int[] STANDARD_Z = {0, 100, 200, 300, 400, 500, 1000};

    private final UiStore store;
    private Layer[] layers = new Layer[8];
    private DrawList[] lists = new DrawList[8];
    private int count;
    private long version;

    /** The stack of {@code store}: its root is the {@link #HUD} layer. */
    @AllowAllocation("one per UI")
    public Layers(UiStore store) {
        this.store = store;
        insert(new Layer(HUD, 0, store.root(), Layer.Input.PASS_THROUGH, false));
    }

    /**
     * The layer named {@code name}: an existing one, or a standard layer made now. Throws for an unknown name that
     * was not {@linkplain #add added}.
     */
    public Layer get(String name) {
        Layer found = find(name);
        if (found != null) {
            return found;
        }
        for (int i = 0; i < STANDARD.length; i++) {
            if (STANDARD[i].equals(name)) {
                return make(name, STANDARD_Z[i]);
            }
        }
        throw new IllegalArgumentException("no layer " + name + " (add it with its z first)");
    }

    @AllowAllocation("layers are made when a UI is built")
    private Layer make(String name, int z) {
        Layer.Input input = name.equals(TOOLTIP) || name.equals(DRAG) || name.equals(DEBUG)
            ? Layer.Input.NONE : Layer.Input.PASS_THROUGH;
        Layer layer = new Layer(name, z, store.createRoot("layer", name), input, name.equals(MODAL));
        insert(layer);
        return layer;
    }

    /** Adds a layer named {@code name} at {@code z} (pass-through input, not modal); the name must be new. */
    public Layer add(String name, int z) {
        if (find(name) != null) {
            throw new IllegalArgumentException("layer " + name + " exists");
        }
        return make(name, z);
    }

    /** The layer named {@code name}, or null. */
    public Layer find(String name) {
        for (int i = 0; i < count; i++) {
            if (layers[i].name().equals(name)) {
                return layers[i];
            }
        }
        return null;
    }

    @AllowAllocation("layers are made when a UI is built")
    private void insert(Layer layer) {
        if (count == layers.length) {
            layers = Arrays.copyOf(layers, count * 2);
        }
        int at = count;
        while (at > 0 && layers[at - 1].z() > layer.z()) {
            layers[at] = layers[at - 1];
            at--;
        }
        layers[at] = layer;
        count++;
        lists = new DrawList[count];
        for (int i = 0; i < count; i++) {
            lists[i] = layers[i].drawList();
        }
        version++;
    }

    /** How many layers exist. */
    public int count() {
        return count;
    }

    /** Layer {@code index}, back to front (0 is the lowest z). */
    public Layer at(int index) {
        return layers[index];
    }

    /** The index of the layer whose root is {@code rootSlot}, or -1. */
    public int indexOfRoot(int rootSlot) {
        for (int i = 0; i < count; i++) {
            if (layers[i].root().slot() == rootSlot) {
                return i;
            }
        }
        return -1;
    }

    /** The index of the layer {@code slot} is in, or -1 if it is in no layer (detached). */
    public int indexOf(int slot) {
        int root = store.rootOf(slot);
        return root == UiStore.NONE ? -1 : indexOfRoot(root);
    }

    /** The layer {@code slot} is in, or null. */
    public Layer of(int slot) {
        int i = indexOf(slot);
        return i < 0 ? null : layers[i];
    }

    /** The layers' draw lists, back to front: what the renderer draws. */
    public DrawList[] drawLists() {
        return lists;
    }

    /** Grows when a layer is added. */
    public long version() {
        return version;
    }

    /** The store the layers' roots are in. */
    public UiStore store() {
        return store;
    }
}
