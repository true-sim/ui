package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedInt;

/**
 * A row of joined segments, one chosen (type {@code segmented}, each option a {@code segment}: text and/or an icon),
 * bound to the chosen index. Left and right move the choice.
 */
public final class SegmentedControl extends ChoiceGroup {

    /** A segmented control named {@code name}, bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public SegmentedControl(Controls controls, String name, VersionedInt model) {
        super(controls, "segmented", "segment", name, true, model);
    }
}
