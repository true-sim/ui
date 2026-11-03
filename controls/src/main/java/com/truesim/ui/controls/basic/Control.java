package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;

/**
 * A control: a thin typed facade over its root element and parts (design 4.2), with its model objects. Controls are
 * made by {@link Controls}; each listens to its own elements' events ({@link #handle}) and, when it has a model,
 * polls it once a frame ({@link #poll}) so model changes show without a refresh. Everything presentational comes from
 * the theme: a control sets its elements' types, states and facts, never a look.
 */
public abstract class Control implements UiListener, Controls.Polled {

    protected final Controls controls;
    protected final Ui ui;
    protected final Element root;

    /** A control whose root is a new element of {@code type}, announced as {@code role} named {@code name}. */
    @AllowAllocation("controls are built once")
    protected Control(Controls controls, String type, Role role, String name) {
        this.controls = controls;
        this.ui = controls.ui();
        this.root = ui.element(type, null);
        root.semantics(role, name);
    }

    /** The root element: add it to a parent to show the control. */
    public Element element() {
        return root;
    }

    /** Enables or disables the control (a disabled one gets no events and shows {@code :disabled}); returns this. */
    public Control enabled(boolean on) {
        root.state(UiState.DISABLED, !on);
        return this;
    }

    /** Whether the control is enabled (it and its ancestors). */
    public boolean enabled() {
        return !interaction().disabled(root.slot());
    }

    /** Sets the accessible name; returns this. */
    public Control name(String name) {
        root.semantics(ui.store().role(root.slot()), name);
        return this;
    }

    /** The accessible name. */
    public String name() {
        return ui.store().label(root.slot());
    }

    /** The UI's interaction. */
    protected final Interaction interaction() {
        return ui.interaction();
    }

    /** Listens to {@code types} ({@link EventType#bit()} or'ed) at {@code element} with this control. */
    @AllowAllocation("listeners are added when a control is built")
    protected final void listen(Element element, int types) {
        ui.interaction().on(element, types, this);
    }

    /** Makes {@code element} focusable. */
    protected final void focusable(Element element) {
        ui.interaction().focusable(element, true);
    }

    /** A new label of this control's UI showing {@code text}, its accessible name {@code text} too. */
    @AllowAllocation("controls are built once")
    protected final Element label(CharSequence text) {
        return ui.label(null, text);
    }

    /** A new element of {@code type}. */
    @AllowAllocation("controls are built once")
    protected final Element part(String type) {
        return ui.element(type, null);
    }

    /** Polls the model once a frame (registered with {@link Controls#poll}); the default does nothing. */
    @Override
    public void poll(long nanos) {
        // no model
    }

    @Override
    public void handle(UiEvent event) {
        // no events by default
    }

    /**
     * Turns state {@code bits} on or off for {@code element} and its whole subtree: controls copy their checked,
     * selected, indeterminate and expanded states onto their parts, so themes style a part by its own state and no
     * rule needs a state on an ancestor (which would restyle a subtree on every hover).
     */
    public static void deepState(Element element, int bits, boolean on) {
        element.state(bits, on);
        UiStore store = element.store();
        for (int c = store.firstChild(element.slot()); c != UiStore.NONE; c = store.nextSibling(c)) {
            deepState(store.element(c), bits, on);
        }
    }

    /** Whether {@code event} is the command {@code command} (a COMMAND event). */
    protected static boolean is(UiEvent event, com.truesim.ui.core.input.UiCommand command) {
        return event.type() == EventType.COMMAND && event.command() == command;
    }
}
