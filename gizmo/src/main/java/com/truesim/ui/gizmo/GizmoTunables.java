package com.truesim.ui.gizmo;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.TunableHolder;
import java.util.ArrayList;
import java.util.List;

/** The gizmos' tunables, for {@code Ui.tunables()} (a {@link TunableHolder} service). */
public final class GizmoTunables implements TunableHolder {

    /** For the service loader. */
    public GizmoTunables() {
    }

    @Override
    @AllowAllocation("asked once")
    public List<Tunable> tunables() {
        List<Tunable> all = new ArrayList<>(Gizmo.tunables());
        all.add(GizmoGrab.GRAB_RADIUS);
        all.add(ViewCube.ORBIT_SPEED);
        return all;
    }
}
