package com.truesim.ui.a11y;

import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.TunableSource;
import java.util.List;

/** The accessibility bridges' tunables, listed by {@code Ui.tunables()} through {@link TunableSource}. */
public final class AccessibilityTunables implements TunableSource {

    /**
     * Whether the OS accessibility bridge answers assistive technology ({@code on}) or the window shows none of the
     * UI ({@code off}).
     */
    public static final Tunable BRIDGE = Tunable.choice("ui.a11y.bridge", List.of("on", "off"), "on",
        "Expose the UI to screen readers and other assistive technology through the OS (Windows UI Automation)");

    @Override
    public List<Tunable> tunables() {
        return List.of(BRIDGE);
    }
}
