package com.truesim.ui.core;

import java.util.List;

/**
 * The tunables of a UI module built on top of the controls (charts, gizmos): listed as a {@link java.util.ServiceLoader}
 * service ({@code META-INF/services/com.truesim.ui.core.TunableSource}), so {@code Ui.tunables()} lists them with the
 * rest without depending on the module, and an engine registers all of them as settings.
 */
public interface TunableSource {

    /** The module's tunables, each once. */
    List<Tunable> tunables();
}
