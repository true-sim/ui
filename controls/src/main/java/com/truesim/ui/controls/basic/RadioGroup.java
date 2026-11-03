package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedInt;
import com.truesim.ui.core.Element;

/**
 * A group of radio buttons (type {@code radio-group}; each option a {@code radio}: a {@code radio-box} holding a
 * {@code radio-dot}, then its {@code label}), bound to the chosen index. Up and down move the choice.
 */
public final class RadioGroup extends ChoiceGroup {

    /** A radio group named {@code name}, bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public RadioGroup(Controls controls, String name, VersionedInt model) {
        super(controls, "radio-group", "radio", name, false, model);
    }

    @Override
    @AllowAllocation("controls are built once")
    void decorate(Option option) {
        Element box = part("radio-box");
        box.add(part("radio-dot"));
        Element root = option.element();
        root.add(box);
        Element label = option.labelElement();
        if (label != null) {
            label.detach();
            root.add(label);
        }
    }
}
