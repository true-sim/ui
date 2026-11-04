package com.truesim.ui.core;

import java.util.List;

/**
 * A module's tunables, found on the class path as a {@link java.util.ServiceLoader service}
 * ({@code META-INF/services/com.truesim.ui.core.TunableHolder}): modules above the controls (the scene bridge, gizmos)
 * list their tunables there, so {@code Ui.tunables()}, which an engine registers as settings, includes them wherever
 * the module is present. Needs a public no-argument constructor.
 */
public interface TunableHolder {

    /** The module's tunables. */
    List<Tunable> tunables();
}
