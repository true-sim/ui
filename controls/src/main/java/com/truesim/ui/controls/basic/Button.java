package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.UiCommand;
import java.util.Arrays;

/**
 * A push button (type {@code button}): text, an icon, or both ({@code button > icon}, {@code button > label}). A click
 * of the primary pointer button or the ACCEPT command (Enter, Space, the gamepad's confirm button) runs its actions;
 * ACCEPT shows {@code :pressed} for {@code press-flash} seconds too, so keys and gamepads see the press a pointer
 * sees. Focusable; announced as a button named by its text (or the name given to an icon button).
 */
public class Button extends Control {

    private Element icon;
    private Element label;
    private Runnable[] actions = new Runnable[2];
    private int actionCount;
    private long flashUntil = -1;
    private int clicks;

    /** A button of type {@code type} (subclasses: {@code toggle-button}, parts of other controls) and role. */
    @AllowAllocation("controls are built once")
    @SuppressWarnings("this-escape")
    Button(Controls controls, String type, Role role, CharSequence text, String iconName, String name) {
        super(controls, type, role, name != null ? name : text == null ? null : text.toString());
        if (iconName != null) {
            icon = controls.icon(iconName);
            root.add(icon);
        }
        if (text != null) {
            label = label(text);
            root.add(label);
        }
        focusable(root);
        listen(root, EventType.CLICK.bit() | EventType.COMMAND.bit());
        controls.poll(this);
    }

    /** A text button. */
    @AllowAllocation("controls are built once")
    @SuppressWarnings("this-escape")
    public Button(Controls controls, CharSequence text) {
        this(controls, "button", Role.BUTTON, text, null, null);
    }

    /** A button with an icon (named {@code iconName}, the {@code icon} element's id) and text (null: icon only). */
    @AllowAllocation("controls are built once")
    @SuppressWarnings("this-escape")
    public Button(Controls controls, String iconName, CharSequence text, String name) {
        this(controls, "button", Role.BUTTON, text, iconName, name);
    }

    /** Runs {@code action} on every click; returns this. */
    @AllowAllocation("actions are added when a control is built")
    public Button onClick(Runnable action) {
        if (actionCount == actions.length) {
            actions = Arrays.copyOf(actions, actionCount * 2);
        }
        actions[actionCount++] = action;
        return this;
    }

    /** Clicks the button as a pointer would: runs its actions (not while disabled). */
    public void click() {
        if (!enabled()) {
            return;
        }
        clicks++;
        clicked();
        for (int i = 0; i < actionCount; i++) {
            actions[i].run();
        }
    }

    /** What a subclass does on a click before the actions run. */
    void clicked() {
        // a push button only runs its actions
    }

    /** Clicks so far. */
    public int clicks() {
        return clicks;
    }

    /** Shows {@code text} (the button must have been made with text); the accessible name follows. */
    @AllowAllocation("text changes on edit")
    public Button text(CharSequence text) {
        if (label == null) {
            throw new IllegalStateException("an icon-only button has no text");
        }
        ui.text(label, text);
        root.semantics(ui.store().role(root.slot()), text.toString());
        return this;
    }

    /** The label element, or null for an icon-only button. */
    public Element labelElement() {
        return label;
    }

    /** The icon element, or null. */
    public Element iconElement() {
        return icon;
    }

    @Override
    public void handle(UiEvent event) {
        if (event.currentSlot() != root.slot() || event.consumed()) {
            return;
        }
        if (event.type() == EventType.CLICK) {
            event.consume();
            click();
        } else if (is(event, UiCommand.ACCEPT)) {
            event.consume();
            root.state(UiState.PRESSED, true);
            float seconds = ui.styles().styleOf(root.slot()).getFloat(BasicStyles.PRESS_FLASH);
            long now = event.time();
            flashUntil = now <= 0 ? 0 : now + (long) (seconds * 1e9f);
            ui.interaction().dispatch(EventType.CLICK, root, event.player());
        }
    }

    @Override
    public void poll(long nanos) {
        if (flashUntil >= 0 && (nanos <= 0 || nanos >= flashUntil)) {
            flashUntil = -1;
            if (ui.interaction().captured(0) != root) {
                root.state(UiState.PRESSED, false);
            }
        }
    }
}
