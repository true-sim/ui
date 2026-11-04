package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;

/**
 * A button that repeats while held (type {@code button}, like any button): its actions run on the press, then after
 * {@code ui.button.repeatDelay} seconds again {@code ui.button.repeatRate} times a second until the release
 * ({@link Repeater}). {@code ACCEPT} (Enter, Space, the gamepad's confirm) clicks it once; a held key repeats through
 * the action system as more commands. Steppers, scroll arrows and the month arrows of a date picker are repeat buttons.
 */
public class RepeatButton extends Button {

    private final Repeater repeater;

    /** A repeat button of type {@code type} and role (parts of other controls). */
    @AllowAllocation("controls are built once")
    @SuppressWarnings("this-escape")
    RepeatButton(Controls controls, String type, Role role, CharSequence text, String iconName, String name) {
        super(controls, type, role, text, iconName, name);
        repeater = new Repeater(controls.ui(), root, this::click);
    }

    /** A text repeat button. */
    @AllowAllocation("controls are built once")
    @SuppressWarnings("this-escape")
    public RepeatButton(Controls controls, CharSequence text) {
        this(controls, "button", Role.BUTTON, text, null, null);
    }

    /** A repeat button with an icon (named {@code iconName}) and text (null: icon only), named {@code name}. */
    @AllowAllocation("controls are built once")
    @SuppressWarnings("this-escape")
    public RepeatButton(Controls controls, String iconName, CharSequence text, String name) {
        this(controls, "button", Role.BUTTON, text, iconName, name);
    }

    /** The repeater (whether it is held, how often it repeated). */
    public Repeater repeater() {
        return repeater;
    }

    @Override
    public void handle(UiEvent event) {
        if (event.type() == EventType.CLICK) {
            return; // the repeater runs the clicks: on the press, and once for ACCEPT
        }
        super.handle(event);
    }
}
