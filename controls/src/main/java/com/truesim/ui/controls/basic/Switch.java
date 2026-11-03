package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;

/**
 * An on/off switch (type {@code switch}: a {@code switch-track} holding a {@code switch-knob}, then its
 * {@code label}), bound two-way to a {@link VersionedBoolean}; on shows {@code :checked}. Where the knob sits and how
 * it moves is the theme's (its offsets under {@code :checked}, with a {@code transition} of {@code offsets}).
 */
public final class Switch extends Button {

    private final Element track;
    private final Element knob;
    private VersionedBoolean model;
    private Watch watch;

    /** A switch labelled {@code text} (null: none; then give it a name), bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public Switch(Controls controls, CharSequence text, String name, VersionedBoolean model) {
        super(controls, "switch", Role.SWITCH, text, null, name);
        track = part("switch-track");
        knob = part("switch-knob");
        track.add(knob);
        root.add(track);
        Element label = labelElement();
        if (label != null) {
            label.detach();
            root.add(label);
        }
        bind(model);
    }

    /** Binds the switch to {@code value}; returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public Switch bind(VersionedBoolean value) {
        model = value;
        watch = value.watch();
        show();
        return this;
    }

    /** The model. */
    public VersionedBoolean model() {
        return model;
    }

    /** Whether it is on. */
    public boolean on() {
        return model.get();
    }

    /** The knob element. */
    public Element knob() {
        return knob;
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
