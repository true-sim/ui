package com.truesim.ui.controls.data;

import com.truesim.ui.style.ResolvedStyle;

/**
 * A control's renderer chosen by the style key {@link DataStyles#RENDERER}: the control's own renderer until its
 * element's style names a renderer its {@link ValueRenderers} registry knows ({@code list-view#scores { renderer:
 * number; }}). Checked after each layout pass: one string comparison while the name stays (style values are shared,
 * so nothing allocates).
 *
 * @param <T> the value type
 */
final class StyledRenderer<T> {

    private final ValueRenderer<? super T> own;
    private ValueRenderers registry;
    private ValueRenderer<? super T> active;
    private String seen = "";

    StyledRenderer(ValueRenderer<? super T> own, ValueRenderers registry) {
        this.own = own;
        this.active = own;
        this.registry = registry;
    }

    /** The renderer in use. */
    ValueRenderer<? super T> active() {
        return active;
    }

    /** The control's own renderer. */
    ValueRenderer<? super T> own() {
        return own;
    }

    /** The registry names resolve in. */
    ValueRenderers registry() {
        return registry;
    }

    /** Resolves names in {@code value} from now on; the name is looked up again. */
    void registry(ValueRenderers value) {
        registry = value;
        seen = null;
    }

    @com.truesim.annotations.AllowAllocation("once: a style names a renderer of a control without a registry")
    private static ValueRenderers newRegistry() {
        return new ValueRenderers();
    }

    /** Reads the name from {@code style}; returns whether the renderer in use changed (views must be made again). */
    @SuppressWarnings("unchecked")
    boolean update(ResolvedStyle style) {
        String name = style.get(DataStyles.RENDERER);
        if (name == seen) {
            return false;
        }
        seen = name;
        ValueRenderer<? super T> want = own;
        if (!name.isEmpty()) {
            if (registry == null) {
                registry = newRegistry();
            }
            ValueRenderer<?> named = registry.named(name);
            if (named != null) {
                want = (ValueRenderer<? super T>) named;
            }
        }
        if (want == active) {
            return false;
        }
        active = want;
        return true;
    }
}
