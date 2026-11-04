package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.ElementAnchor;
import com.truesim.ui.controls.overlay.Overlay;
import com.truesim.ui.controls.overlay.OverlayInput;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.controls.overlay.PointAnchor;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import java.util.Arrays;
import java.util.List;

/**
 * Tooltips of one UI: a text or any element subtree (rich content) per target element, shown in one {@code tooltip}
 * element on the {@link Layers#TOOLTIP} layer.
 *
 * <ul>
 * <li>Pointer: after the pointer rests on the target for {@code ui.input.tooltipDelay} (the interaction's
 * {@link EventType#TOOLTIP}); hidden when it leaves the target, on any press and on the wheel. Moving to another target
 * within {@link #WARM_TIME} of a tooltip hiding (or while one shows) shows its tooltip at once.</li>
 * <li>Touch and pen: on a long press (which then does not click); hidden when the finger lifts.</li>
 * <li>Keys and gamepad: when the target gets visible focus (navigation), after {@code ui.input.tooltipDelay}; hidden
 * when focus leaves.</li>
 * </ul>
 *
 * <p>The tooltip sits next to its target ({@code tooltip-mode: anchored}, placed by its placement keys) or follows the
 * pointer ({@code tooltip-mode: follow}). Text tooltips show in a {@code label} under the {@code tooltip} element;
 * rich content replaces it. The tooltip's semantics are {@link Role#TOOLTIP} named by its text (or the name given for
 * rich content).
 */
public final class Tooltips implements Ui.FrameListener {

    /** How long after a tooltip hides the next one shows without the delay. */
    public static final Tunable WARM_TIME = Tunable.of("ui.tooltip.warmTime", 0.5f, 0, 5,
        "Seconds after a tooltip hides during which moving onto another target shows its tooltip at once.");

    private static final int POINTER = 1;
    private static final int TOUCH = 2;
    private static final int FOCUS = 3;
    private static final int CODE = 4;
    private static final int LISTENED = EventType.TOOLTIP.bit() | EventType.ENTER.bit() | EventType.EXIT.bit()
        | EventType.FOCUS_IN.bit() | EventType.FOCUS_OUT.bit() | EventType.LONG_PRESS.bit();

    private final Ui ui;
    private final Interaction interaction;
    private final UiStore store;
    private final Overlay overlay;
    private final Element container;
    private final Element label;
    private final ElementAnchor elementAnchor;
    private final PointAnchor pointAnchor = new PointAnchor(0, 0);
    private final UiListener listener = new TargetListener();

    // per target slot
    private Element[] targets = new Element[0];
    private CharSequence[] texts = new CharSequence[0];
    private Element[] contents = new Element[0];
    private String[] names = new String[0];

    private Element current;
    private Element shownContent;
    private int shownBy;
    private int pointerId = -1;
    private long hiddenAt = Long.MIN_VALUE / 2;
    private Element pending;
    private long pendingSince;
    private long now;

    /** The tooltips of the UI of {@code overlays}; registers a frame hook. */
    @AllowAllocation("one per UI")
    public Tooltips(Overlays overlays) {
        this.ui = overlays.ui();
        this.interaction = ui.interaction();
        this.store = ui.store();
        this.container = ui.element("tooltip", null);
        this.label = ui.textSystem() == null ? null : ui.label(null, "");
        if (label != null) {
            container.add(label);
        }
        this.elementAnchor = new ElementAnchor(ui.layout(), null);
        this.overlay = overlays.create(container, Layers.TOOLTIP).input(OverlayInput.PASS_THROUGH)
            .anchor(elementAnchor);
        container.semantics(Role.TOOLTIP, null);
        ui.addFrameListener(this);
    }

    /** The tunables tooltips read besides the interaction's delay ({@code ui.input.tooltipDelay}). */
    @AllowAllocation("asked once")
    public static List<Tunable> tunables() {
        return List.of(WARM_TIME);
    }

    @AllowAllocation("growing with the store, rarely")
    private void ensure() {
        int n = store.capacity();
        if (targets.length < n) {
            targets = Arrays.copyOf(targets, n);
            texts = Arrays.copyOf(texts, n);
            contents = Arrays.copyOf(contents, n);
            names = Arrays.copyOf(names, n);
        }
    }

    /** Gives {@code target} a text tooltip (the UI needs a text system); null removes it. */
    @AllowAllocation("tooltips are set when a UI is built")
    public void text(Element target, CharSequence text) {
        if (text == null) {
            remove(target);
            return;
        }
        if (label == null) {
            throw new IllegalStateException("a UI without a text system has no text tooltips");
        }
        int slot = register(target);
        texts[slot] = text;
        contents[slot] = null;
        names[slot] = null;
        if (current == target) {
            fill(slot);
        }
    }

    /**
     * Gives {@code target} a rich tooltip: {@code content} (any element subtree, not in the tree) shows in the
     * tooltip; {@code name} is what a screen reader announces.
     */
    @AllowAllocation("tooltips are set when a UI is built")
    public void content(Element target, Element content, String name) {
        int slot = register(target);
        texts[slot] = null;
        contents[slot] = content;
        names[slot] = name;
        if (current == target) {
            fill(slot);
        }
    }

    /** Removes {@code target}'s tooltip. */
    public void remove(Element target) {
        int slot = target.slot();
        if (slot < targets.length && targets[slot] == target) {
            interaction.off(target, listener);
            targets[slot] = null;
            texts[slot] = null;
            contents[slot] = null;
            names[slot] = null;
            if (current == target) {
                hide();
            }
        }
    }

    @AllowAllocation("tooltips are set when a UI is built")
    private int register(Element target) {
        ensure();
        int slot = target.slot();
        if (targets[slot] != target) {
            targets[slot] = target;
            interaction.on(target, LISTENED, listener);
        }
        return slot;
    }

    /** Whether {@code target} has a tooltip. */
    public boolean has(Element target) {
        int slot = target.slot();
        return slot < targets.length && targets[slot] == target;
    }

    /** The tooltip's element ({@code tooltip}). */
    public Element element() {
        return container;
    }

    /** The overlay showing it: its side, its place. */
    public Overlay overlay() {
        return overlay;
    }

    /** The target whose tooltip shows, or null. */
    public Element showingFor() {
        return overlay.showing() ? current : null;
    }

    /** Whether a tooltip shows. */
    public boolean showing() {
        return overlay.showing();
    }

    /** Shows {@code target}'s tooltip now, at the target (as for focus), until {@link #hide()}. */
    public void show(Element target) {
        if (has(target)) {
            show(target, CODE, -1, 0, 0);
        }
    }

    /** Hides the tooltip. */
    public void hide() {
        if (overlay.showing()) {
            overlay.hide();
            hiddenAt = now;
        }
        current = null;
        shownBy = 0;
        pointerId = -1;
    }

    private void show(Element target, int by, int pointer, float x, float y) {
        int slot = target.slot();
        current = target;
        shownBy = by;
        pointerId = pointer;
        pending = null;
        fill(slot);
        boolean follow = by == POINTER
            && ui.styles().styleOf(container.slot()).get(WindowStyles.TOOLTIP_MODE) == TooltipMode.FOLLOW;
        if (follow) {
            pointAnchor.set(x, y);
            overlay.anchor(pointAnchor);
        } else {
            elementAnchor.element(target);
            overlay.anchor(elementAnchor);
        }
        if (!overlay.showing()) {
            overlay.show();
        }
    }

    /** Puts {@code slot}'s text or content into the tooltip element. */
    private void fill(int slot) {
        Element content = contents[slot];
        if (shownContent != null && shownContent != content && shownContent.alive()
            && shownContent.parent() == container) {
            shownContent.detach();
        }
        if (content != null) {
            if (label != null && label.parent() == container) {
                label.detach();
            }
            if (content.parent() != container) {
                container.add(content);
            }
            shownContent = content;
            container.semantics(Role.TOOLTIP, names[slot]);
        } else {
            shownContent = null;
            if (label.parent() != container) {
                container.add(label);
            }
            ui.text(label, texts[slot]);
            String name = store.label(container.slot());
            if (name == null || !name.contentEquals(texts[slot])) {
                nameFromText(slot);
            }
        }
    }

    @AllowAllocation("the tooltip's accessible name changes with its text, not per frame")
    private void nameFromText(int slot) {
        String text = texts[slot].toString();
        container.semantics(Role.TOOLTIP, text);
        label.semantics(Role.TEXT, text);
    }

    @Override
    public void frame(long nanos) {
        UiInput input = ui.input();
        now = nanos;
        if (pending != null) {
            if (!pending.alive() || interaction.focusedSlot(0) != pending.slot()) {
                pending = null;
            } else if (nanos - pendingSince >= seconds(Interaction.TOOLTIP_DELAY.get())) {
                show(pending, FOCUS, -1, 0, 0);
            }
        }
        if (!overlay.showing()) {
            return;
        }
        Element target = current;
        if (target == null || !target.alive() || store.rootOf(target.slot()) == UiStore.NONE
            || interaction.disabled(target.slot())) {
            hide();
            return;
        }
        if (input == null || shownBy == FOCUS || shownBy == CODE) {
            return;
        }
        boolean seen = false;
        for (int i = 0; i < input.pointerCount(); i++) {
            Pointer p = input.pointerAt(i);
            if (p.scrollX() != 0 || p.scrollY() != 0) {
                hide();
                return;
            }
            if (p.id() == pointerId) {
                seen = true;
                if (shownBy == TOUCH ? p.buttons() == 0 : p.buttons() != 0) {
                    hide(); // a press hides a pointer's tooltip; lifting the finger a touch one
                    return;
                }
                pointAnchor.set(p.x(), p.y());
            } else if (p.buttons() != 0 && shownBy == POINTER) {
                hide();
                return;
            }
        }
        if (!seen) {
            hide(); // the pointer left
        }
    }

    private static long seconds(float s) {
        return (long) (s * 1e9);
    }

    /** Handles the targets' events. */
    private final class TargetListener implements UiListener {
        @Override
        public void handle(UiEvent e) {
            Element target = e.currentTarget();
            switch (e.type()) {
                case TOOLTIP -> {
                    e.consume(); // the nearest target's tooltip, not its ancestors'
                    if (current != target || !overlay.showing()) {
                        show(target, POINTER, e.pointerId(), e.x(), e.y());
                    }
                }
                case ENTER -> {
                    boolean warm = overlay.showing() && shownBy == POINTER
                        || e.time() - hiddenAt <= seconds(WARM_TIME.get());
                    if (warm && e.buttons() == 0 && current != target && e.pointerKind() != Pointer.Kind.TOUCH) {
                        show(target, POINTER, e.pointerId(), e.x(), e.y());
                    }
                }
                case EXIT -> {
                    if (current == target && (shownBy == POINTER || shownBy == TOUCH)) {
                        now = e.time();
                        hide();
                    }
                }
                case LONG_PRESS -> {
                    if (e.pointerKind() == Pointer.Kind.TOUCH || e.pointerKind() == Pointer.Kind.PEN) {
                        e.consume();
                        show(target, TOUCH, e.pointerId(), e.x(), e.y());
                    }
                }
                case FOCUS_IN -> {
                    if (e.targetSlot() == e.currentSlot() && e.focusVisible()) {
                        pending = target;
                        pendingSince = e.time();
                    }
                }
                case FOCUS_OUT -> {
                    if (e.targetSlot() == e.currentSlot()) {
                        if (pending == target) {
                            pending = null;
                        }
                        if (current == target && shownBy == FOCUS) {
                            now = e.time();
                            hide();
                        }
                    }
                }
                default -> {
                }
            }
        }
    }
}
