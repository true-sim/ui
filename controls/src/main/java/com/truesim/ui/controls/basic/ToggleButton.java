package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;

/**
 * A button that stays on or off (type {@code toggle-button}, {@code :checked} when on), bound two-way to a
 * {@link VersionedBoolean}: a click flips the model, and a change of the model shows in the frame it happens.
 */
public final class ToggleButton extends Button {

    private VersionedBoolean model;
    private Watch watch;

    /** A toggle button showing {@code text} and/or the icon {@code iconName}, bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public ToggleButton(Controls controls, String iconName, CharSequence text, String name, VersionedBoolean model) {
        super(controls, "toggle-button", Role.TOGGLE_BUTTON, text, iconName, name);
        bind(model);
    }

    /** Binds the button to {@code value}; returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public ToggleButton bind(VersionedBoolean value) {
        model = value;
        watch = value.watch();
        show();
        return this;
    }

    /** The model. */
    public VersionedBoolean model() {
        return model;
    }

    /** Whether the button is on. */
    public boolean on() {
        return model.get();
    }

    @Override
    void clicked() {
        model.set(!model.get());
        show();
    }

    private void show() {
        watch.update();
        deepState(root, UiState.CHECKED, model.get());
    }

    @Override
    public void poll(long nanos) {
        super.poll(nanos);
        if (watch.isChanged()) {
            show();
        }
    }
}
