package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedValue;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;

/**
 * A checkbox (type {@code checkbox}: a {@code check-box} holding a {@code check-mark}, then its {@code label}), bound
 * two-way to a {@link VersionedValue} of {@link CheckState}. Checked shows {@code :checked}, mixed
 * {@code :indeterminate}; a click (or ACCEPT) turns a mixed or unchecked box on and a checked one off.
 */
public final class Checkbox extends Button {

    private final Element box;
    private final Element mark;
    private VersionedValue<CheckState> model;
    private Watch watch;

    /** A checkbox labelled {@code text}, bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public Checkbox(Controls controls, CharSequence text, VersionedValue<CheckState> model) {
        super(controls, "checkbox", Role.CHECKBOX, text, null, null);
        box = part("check-box");
        mark = part("check-mark");
        box.add(mark);
        root.add(box);
        Element label = labelElement();
        if (label != null) {
            label.detach();
            root.add(label);
        }
        bind(model);
    }

    /** Binds the checkbox to {@code value}; returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public Checkbox bind(VersionedValue<CheckState> value) {
        model = value;
        watch = value.watch();
        show();
        return this;
    }

    /** The model. */
    public VersionedValue<CheckState> model() {
        return model;
    }

    /** The state shown. */
    public CheckState state() {
        return model.get();
    }

    /** The box element. */
    public Element box() {
        return box;
    }

    /** The mark element. */
    public Element mark() {
        return mark;
    }

    @Override
    void clicked() {
        model.set(model.get() == CheckState.CHECKED ? CheckState.UNCHECKED : CheckState.CHECKED);
        show();
    }

    private void show() {
        watch.update();
        CheckState s = model.get();
        deepState(root, UiState.CHECKED, s == CheckState.CHECKED);
        deepState(root, UiState.INDETERMINATE, s == CheckState.INDETERMINATE);
    }

    @Override
    public void poll(long nanos) {
        super.poll(nanos);
        if (watch.isChanged()) {
            show();
        }
    }
}
