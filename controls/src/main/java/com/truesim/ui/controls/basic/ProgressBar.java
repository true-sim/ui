package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedDouble;
import com.truesim.common.state.Watch;
import com.truesim.ui.controls.anim.Visuals;
import com.truesim.ui.core.AccessibleValue;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.layout.Unit;

/**
 * A progress bar (type {@code progress}, a {@code progress-fill} inside), showing a {@link VersionedDouble} from 0 to
 * 1 as the fill's width (a percentage of the track, so it needs no layout of its own), or, indeterminate
 * ({@code :indeterminate}), a fill of the theme's width sweeping across the track once per {@code progress-period}
 * seconds on the UI clock. Frames allocate nothing; a still bar costs one version check.
 */
public final class ProgressBar extends Control {

    private final Element fill;
    private VersionedDouble model;
    private Watch watch;
    private boolean indeterminate;
    private long started = -1;

    /** A bar named {@code name} showing {@code model}. */
    @AllowAllocation("controls are built once")
    public ProgressBar(Controls controls, String name, VersionedDouble model) {
        super(controls, "progress", Role.PROGRESS, name);
        fill = part("progress-fill");
        root.add(fill);
        controls.poll(this);
        bind(model);
        root.accessibleValue(new AccessibleValue() {
            @Override
            @AllowAllocation("read when the semantics tree is taken, not per frame")
            public String text() {
                return indeterminate ? "" : AccessibleValue.number(ProgressBar.this.model.get());
            }
        });
    }

    /** Binds the bar to {@code value}; returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public ProgressBar bind(VersionedDouble value) {
        model = value;
        watch = value.watch();
        show();
        return this;
    }

    /** The model. */
    public VersionedDouble model() {
        return model;
    }

    /** Switches between showing the value and sweeping (unknown progress); returns this. */
    public ProgressBar indeterminate(boolean on) {
        if (on == indeterminate) {
            return this;
        }
        indeterminate = on;
        deepState(root, UiState.INDETERMINATE, on);
        Visuals v = ui.visuals();
        if (on) {
            v.clear(fill.slot(), Visuals.WIDTH);
            started = -1;
        } else {
            v.clear(fill.slot(), Visuals.TRANSLATE);
            show();
        }
        return this;
    }

    /** Whether the bar sweeps. */
    public boolean indeterminate() {
        return indeterminate;
    }

    /** The fill element. */
    public Element fill() {
        return fill;
    }

    private void show() {
        watch.update();
        if (indeterminate) {
            return;
        }
        double v = model.get();
        float f = v != v ? 0 : (float) Math.max(0, Math.min(1, v));
        ui.visuals().width(fill.slot(), f * 100, Unit.PERCENT);
    }

    @Override
    public void poll(long nanos) {
        if (watch.isChanged()) {
            show();
        }
        if (!indeterminate || ui.store().rootOf(root.slot()) == UiStore.NONE) {
            return;
        }
        if (started < 0 || nanos <= 0) {
            started = nanos;
        }
        float period = ui.styles().styleOf(root.slot()).getFloat(BasicStyles.PROGRESS_PERIOD);
        float t = period <= 0 ? 0 : (nanos - started) * 1e-9f / period;
        float phase = t - (float) Math.floor(t);
        float trackW = ui.layout().width(root.slot());
        float fillW = ui.layout().width(fill.slot());
        float x = -fillW + phase * (trackW + fillW);
        ui.visuals().translate(fill.slot(), controls.units(x), 0);
    }
}
