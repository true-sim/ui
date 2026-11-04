package com.truesim.ui.scene;

import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.TunableHolder;
import java.util.List;

/** The scene bridge's tunables, for {@code Ui.tunables()} (a {@link TunableHolder} service). */
public final class SceneTunables implements TunableHolder {

    /** For the service loader. */
    public SceneTunables() {
    }

    @Override
    public List<Tunable> tunables() {
        return ScenePicking.tunables();
    }
}
