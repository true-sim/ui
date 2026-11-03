package com.truesim.ui.controls.overlay;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.anim.Animator;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Transition;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;

/**
 * One overlay: an element shown over the UI on a {@link Layer} (pop-ups, tooltips, dialogs), made by
 * {@link Overlays#create}. It is {@linkplain #anchor anchored} to an element, a point or a scene object and placed next
 * to it by its style ({@link OverlayStyles}: side, alignment, gap, shift, viewport margin, flipping), kept on screen;
 * or it is {@linkplain #centred() centred}, or at a {@linkplain #position(float, float) position} set in code. It takes
 * pointer input as its {@link OverlayInput} says: {@link OverlayInput#BLOCK_SCREEN} puts a dimming {@code backdrop}
 * element (stylable: colour, opacity, its transition) under it. A {@linkplain #modal(boolean) modal} overlay also
 * traps focus and closes on {@code CANCEL}.
 *
 * <p>The side it ended on is the root's fact {@code side} ({@code tooltip[side=top]} in styles: an arrow's side).
 */
public final class Overlay {

    /** What an overlay tells its owner. */
    public interface Listener {
        /**
         * The user asked to close the overlay: {@code CANCEL} on a modal one, a press on its backdrop with
         * {@linkplain #lightDismiss light dismiss}. The default hides it.
         */
        default void dismissed(Overlay overlay) {
            overlay.hide();
        }
    }

    private static final Listener HIDE = new Listener() {
    };

    final Overlays owner;
    private final Element root;
    private final Layer layer;
    private Element host;
    private OverlayAnchor anchor;
    private OverlayInput input = OverlayInput.BLOCK_BOUNDS;
    private Listener listener = HIDE;
    private Element backdrop;
    private boolean modal;
    private boolean lightDismiss;
    private boolean centred;
    private boolean showing;
    boolean anchorVisible = true;
    float x;
    float y;
    float arrow;
    Placement side;
    int index = -1;
    private final Runnable cancel = new Cancel();
    private final UiListener backdropPress = new BackdropPress();
    private final Animator.Listener faded = new Faded();
    boolean appearing; // shown this frame: fades in from transparent once styled (Overlays.afterLayout)
    private boolean fadingOut;

    @AllowAllocation("overlays are made when a UI is built")
    Overlay(Overlays owner, Element root, Layer layer) {
        this.owner = owner;
        this.root = root;
        this.layer = layer;
        this.host = layer.root();
    }

    /** The overlay's root element: what it shows. */
    public Element root() {
        return root;
    }

    /** The layer it shows on. */
    public Layer layer() {
        return layer;
    }

    /** The element it is added under: the layer's root, or a host set with {@link #host(Element)}. */
    public Element host() {
        return host;
    }

    /**
     * Shows the overlay under {@code value} (a container covering what the overlay may use: a world panel's root, a
     * window; it is laid out as {@code layout: none}) instead of its layer's root; it is placed within the host's box.
     * Returns this.
     */
    public Overlay host(Element value) {
        if (showing) {
            throw new IllegalStateException("the overlay is showing");
        }
        host = value;
        return this;
    }

    /** Anchors the overlay to {@code value} (null: not anchored); returns this. */
    public Overlay anchor(OverlayAnchor value) {
        anchor = value;
        if (value != null) {
            centred = false;
        }
        return this;
    }

    /** What the overlay is anchored to, or null. */
    public OverlayAnchor anchor() {
        return anchor;
    }

    /** Centres the overlay in its host (a dialog); returns this. */
    public Overlay centred() {
        centred = true;
        anchor = null;
        return this;
    }

    /** Puts the overlay's top left corner at {@code px, py} (UI pixels), not anchored; returns this. */
    public Overlay position(float px, float py) {
        anchor = null;
        centred = false;
        x = px;
        y = py;
        if (showing) {
            owner.place(this);
        }
        return this;
    }

    /** Sets how the overlay takes pointer input; returns this. */
    public Overlay input(OverlayInput value) {
        input = value;
        if (showing) {
            applyInput();
        }
        return this;
    }

    /** How the overlay takes pointer input. */
    public OverlayInput input() {
        return input;
    }

    /** Makes the overlay modal (focus trapped inside, CANCEL dismisses it); set before showing. Returns this. */
    public Overlay modal(boolean value) {
        if (showing) {
            throw new IllegalStateException("the overlay is showing");
        }
        modal = value;
        return this;
    }

    /** Whether the overlay is modal. */
    public boolean modal() {
        return modal;
    }

    /** Makes a press on the backdrop ({@link OverlayInput#BLOCK_SCREEN}) dismiss the overlay; returns this. */
    public Overlay lightDismiss(boolean value) {
        lightDismiss = value;
        return this;
    }

    /** Sets what is told when the user dismisses the overlay; returns this. */
    public Overlay listener(Listener value) {
        listener = value == null ? HIDE : value;
        return this;
    }

    /** The backdrop element once made (the first show with {@link OverlayInput#BLOCK_SCREEN}), or null. */
    public Element backdrop() {
        return backdrop;
    }

    /** Whether the overlay shows. */
    public boolean showing() {
        return showing;
    }

    /** The side of its anchor it is on (TOP, BOTTOM, LEFT or RIGHT), or null before it was placed next to one. */
    public Placement side() {
        return side;
    }

    /** Where the anchor's centre is along that side, from the overlay's edge (an arrow's position), in UI pixels. */
    public float arrow() {
        return arrow;
    }

    /** Whether the anchor was visible when last placed (the overlay of a hidden anchor hides with it). */
    public boolean anchorVisible() {
        return anchorVisible;
    }

    /** The overlay's left edge as placed, in UI pixels. */
    public float x() {
        return x;
    }

    /** The overlay's top edge as placed, in UI pixels. */
    public float y() {
        return y;
    }

    boolean centredNow() {
        return centred;
    }

    /** Shows the overlay on top of its layer's other overlays; showing it again brings it to the top. */
    @AllowAllocation("an overlay opens on user action, not per frame")
    public Overlay show() {
        UiStore store = root.store();
        if (input == OverlayInput.BLOCK_SCREEN) {
            if (backdrop == null) {
                backdrop = store.create("backdrop", null);
                owner.interaction().pickable(backdrop, PointerEvents.ALL);
                owner.interaction().on(backdrop, EventType.PRESS, backdropPress);
                backdrop.semantics(Role.NONE, null);
            }
            host.add(backdrop);
        } else if (backdrop != null && backdrop.parent() != null) {
            backdrop.detach();
        }
        host.add(root);
        boolean was = showing;
        showing = true;
        appearing = !was;
        fadingOut = false; // shown again while fading out: fades back in from where it is
        anchorVisible = true;
        applyInput();
        owner.shown(this);
        if (modal && !was) {
            owner.interaction().pushModal(root, cancel);
        }
        return this;
    }

    /**
     * Hides the overlay: its root (and backdrop) leave the layer, after fading out when its style has an
     * {@code opacity} transition (taking no input meanwhile); focus returns from a modal one at once.
     */
    public void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        appearing = false;
        if (modal) {
            owner.interaction().popModal(root);
        }
        owner.hidden(this);
        boolean rootFades = fade(root, 0, true);
        boolean backdropFades = backdrop != null && fade(backdrop, 0, true);
        fadingOut = rootFades || backdropFades;
        if (fadingOut) {
            owner.interaction().pickable(root, PointerEvents.NONE);
            if (backdrop != null) {
                owner.interaction().pickable(backdrop, PointerEvents.NONE);
            }
        }
        if (!rootFades) {
            detach(root);
        }
        if (!backdropFades && backdrop != null) {
            detach(backdrop);
        }
    }

    /** Whether it is fading out after {@link #hide()} (still on its layer, taking no input). */
    public boolean fadingOut() {
        return fadingOut;
    }

    /** Fades it in from transparent: the first frame it is styled after {@link #show()}. */
    void appear() {
        appearing = false;
        fade(root, 1, false);
        if (backdrop != null) {
            fade(backdrop, 1, false);
        }
    }

    /**
     * Animates {@code e}'s opacity out (to 0) or in (from 0 to its style's) by its style's {@code opacity}
     * transition; false (nothing animated) without one, with animations off, or when {@code e} is not shown.
     */
    private boolean fade(Element e, float target, boolean out) {
        if (!e.alive() || e.parent() == null || Animator.SPEED.get() <= 0) {
            return false;
        }
        com.truesim.ui.style.ResolvedStyle s = owner.ui().styles().styleOf(e.slot());
        Transition t = Animator.find(s.get(Styles.TRANSITION), "opacity");
        if (t == null || t.duration() <= 0) {
            return false;
        }
        Animator a = owner.ui().animator();
        float styled = s.getFloat(Styles.OPACITY);
        if (out) {
            a.animate(e.slot(), Animator.OPACITY, Float.NaN, 0, t.duration(), t.easing(), true, faded);
        } else {
            a.animate(e.slot(), Animator.OPACITY, a.running(e.slot(), Animator.OPACITY) ? Float.NaN : 0, styled,
                t.duration(), t.easing(), false, null);
        }
        return true;
    }

    private void detach(Element e) {
        if (!e.alive()) {
            return;
        }
        if (e.parent() != null) {
            e.detach();
        }
        owner.ui().visuals().clear(e.slot(), com.truesim.ui.controls.anim.Visuals.OPACITY);
    }

    /** A fade-out ended: what faded leaves the layer, unless the overlay was shown again meanwhile. */
    private final class Faded implements Animator.Listener {
        @Override
        public void finished(int key, int channel, boolean completed) {
            if (!fadingOut || !completed) {
                return;
            }
            if (key == root.slot()) {
                detach(root);
            } else if (backdrop != null && key == backdrop.slot()) {
                detach(backdrop);
            }
            if (root.parent() == null && (backdrop == null || backdrop.parent() == null)) {
                fadingOut = false;
                applyInput();
                if (backdrop != null) {
                    owner.interaction().pickable(backdrop, PointerEvents.ALL);
                }
            }
        }
    }

    /** Tells the listener the user dismissed the overlay (by default it hides). */
    public void dismiss() {
        listener.dismissed(this);
    }

    private void applyInput() {
        owner.interaction().pickable(root,
            input == OverlayInput.PASS_THROUGH ? PointerEvents.NONE : PointerEvents.ALL);
    }

    private final class Cancel implements Runnable {
        @Override
        public void run() {
            dismiss();
        }
    }

    private final class BackdropPress implements UiListener {
        @Override
        public void handle(UiEvent event) {
            event.consume();
            if (lightDismiss) {
                dismiss();
            }
        }
    }

    @Override
    public String toString() {
        return "Overlay[" + root + (showing ? " showing" : "") + "]";
    }
}
