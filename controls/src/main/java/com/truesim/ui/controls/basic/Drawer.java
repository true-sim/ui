package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.Watch;
import com.truesim.ui.controls.anim.Animator;
import com.truesim.ui.controls.anim.Visuals;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.layout.GrowDirection;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.Facts;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Transition;

/**
 * A sidebar or drawer (type {@code drawer}, facts {@code side=left|right|top|bottom} and {@code mode=modal|push}),
 * bound two-way to a {@link VersionedBoolean} (open; {@code :expanded}).
 *
 * <ul>
 *   <li><b>Modal</b>: on the {@link com.truesim.ui.core.Layers#MODAL} layer over a {@code drawer-backdrop} (a press on
 *   it closes the drawer), sliding in from its side: its offset from the edge animates with the drawer's
 *   {@code transition} of {@code offsets}. Opening pushes a modal focus scope (CANCEL closes; focus returns).</li>
 *   <li><b>Push</b>: placed by the game beside its content (a row or column); opening grows its width (height for top
 *   and bottom) from 0 to the theme's size with its {@code transition} of {@code width} ({@code height}), pushing the
 *   content aside; closed, its content leaves the tree.</li>
 * </ul>
 */
public final class Drawer extends Control implements Animator.Animated, Animator.Listener {

    /** The side a drawer comes from. */
    public enum Side {
        LEFT, RIGHT, TOP, BOTTOM
    }

    private final Side side;
    private final boolean modal;
    private final Element backdrop;
    private final Element content;
    private VersionedBoolean model;
    private Watch watch;
    private boolean shownOpen;
    private float openness;
    private final Runnable cancel = () -> open(false);

    /** A drawer named {@code name} from {@code side}, modal or pushing, bound to {@code open}. */
    @AllowAllocation("controls are built once")
    public Drawer(Controls controls, String name, Side side, boolean modal, VersionedBoolean open) {
        super(controls, "drawer", Role.GROUP, name);
        this.side = side;
        this.modal = modal;
        String sideName = side == Side.LEFT ? "left" : side == Side.RIGHT ? "right" : side == Side.TOP ? "top"
            : "bottom";
        ui.styles().facts(root.slot(), Facts.of("side", sideName, "mode", modal ? "modal" : "push"));
        content = part("drawer-content");
        root.add(content);
        if (modal) {
            backdrop = part("drawer-backdrop");
            backdrop.semantics(Role.GROUP, null);
            backdrop.add(root);
            ui.visuals().width(backdrop.slot(), 100, Unit.PERCENT);
            ui.visuals().height(backdrop.slot(), 100, Unit.PERCENT);
            ui.interaction().pickable(backdrop, PointerEvents.ALL);
            listen(backdrop, EventType.CLICK.bit());
            boolean horizontal = side == Side.LEFT || side == Side.RIGHT;
            float a = side == Side.RIGHT || side == Side.BOTTOM ? 1 : 0;
            if (horizontal) {
                ui.visuals().anchors(root.slot(), a, 0, a, 1);
            } else {
                ui.visuals().anchors(root.slot(), 0, a, 1, a);
            }
        } else {
            backdrop = null;
        }
        controls.poll(this);
        model = open;
        watch = open.watch();
        watch.update();
        shownOpen = open.get();
        openness = shownOpen ? 1 : 0;
        if (shownOpen) {
            attach();
        } else if (!modal) {
            size(0);
            content.detach();
        }
        place();
    }

    /** The model: open. */
    public VersionedBoolean model() {
        return model;
    }

    /** The content panel to fill. */
    public Element content() {
        return content;
    }

    /** The backdrop of a modal drawer, or null. */
    public Element backdrop() {
        return backdrop;
    }

    /** Whether it is (or is becoming) open. */
    public boolean isOpen() {
        return model.get();
    }

    /** How open it shows now: 0 closed, 1 open. */
    public float openness() {
        return openness;
    }

    /** Opens or closes it (animated). */
    public void open(boolean on) {
        model.set(on);
        apply();
    }

    /** Flips it. */
    public void toggle() {
        open(!model.get());
    }

    private boolean horizontal() {
        return side == Side.LEFT || side == Side.RIGHT;
    }

    /** The drawer's full size along its axis: its style's width (height) in pixels, else its last layout's. */
    private float fullSize() {
        Length l = ui.styles().styleOf(root.slot()).get(horizontal() ? Styles.WIDTH : Styles.HEIGHT);
        if (l.unit() == Length.Unit.PX) {
            return l.value();
        }
        Layout layout = ui.layout();
        return controls.units(horizontal() ? layout.width(root.slot()) : layout.height(root.slot()));
    }

    private void apply() {
        watch.update();
        boolean on = model.get();
        if (on == shownOpen) {
            return;
        }
        shownOpen = on;
        if (on) {
            attach();
        } else if (modal) {
            interaction().popModal(root);
        }
        root.state(UiState.EXPANDED, on);
        if (modal) {
            backdrop.state(UiState.EXPANDED, on);
        }
        Transition t = Animator.find(ui.styles().styleOf(root.slot()).get(Styles.TRANSITION),
            modal ? "offsets" : horizontal() ? "width" : "height");
        float seconds = t == null ? 0 : t.duration();
        ui.animator().animate(this, 0, 0, openness, on ? 1 : 0, seconds, t == null ? null : t.easing(), this);
    }

    @AllowAllocation("opening a modal drawer opens a modal scope")
    private void attach() {
        if (modal) {
            if (backdrop.parent() == null) {
                controls.modalLayer().root().add(backdrop);
            }
            interaction().pushModal(root, cancel);
            backdrop.state(UiState.EXPANDED, true);
        } else if (content.parent() == null) {
            root.add(content);
        }
        root.state(UiState.EXPANDED, true);
    }

    @Override
    public void animate(int key, int channel, float value) {
        openness = value;
        place();
    }

    @Override
    public void finished(int key, int channel, boolean completed) {
        if (!completed || shownOpen) {
            if (completed && !modal) {
                ui.visuals().clear(root.slot(), horizontal() ? Visuals.WIDTH : Visuals.HEIGHT);
            }
            return;
        }
        if (modal) {
            backdrop.detach();
        } else {
            content.detach();
        }
    }

    private void place() {
        if (modal) {
            float hidden = -(1 - openness) * fullSize();
            Visuals v = ui.visuals();
            switch (side) {
                case LEFT -> v.offsets(root.slot(), hidden, 0, 0, 0);
                case RIGHT -> v.offsets(root.slot(), 0, 0, -hidden, 0);
                case TOP -> v.offsets(root.slot(), 0, hidden, 0, 0);
                default -> v.offsets(root.slot(), 0, 0, 0, -hidden);
            }
            ui.layout().growDirection(root.slot(), side == Side.RIGHT ? GrowDirection.BEGIN : GrowDirection.END,
                side == Side.BOTTOM ? GrowDirection.BEGIN : GrowDirection.END);
        } else if (openness < 1) {
            size(openness * fullSize());
        }
    }

    private void size(float value) {
        if (horizontal()) {
            ui.visuals().width(root.slot(), value, Unit.PX);
        } else {
            ui.visuals().height(root.slot(), value, Unit.PX);
        }
    }

    @Override
    public void handle(UiEvent event) {
        if (event.type() == EventType.CLICK && event.currentSlot() == backdrop.slot()
            && event.targetSlot() == backdrop.slot()) {
            event.consume();
            open(false);
        }
    }

    @Override
    public void poll(long nanos) {
        if (watch.isChanged()) {
            apply();
        }
    }
}
