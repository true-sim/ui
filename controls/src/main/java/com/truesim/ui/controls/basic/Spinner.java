package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowStrictMath;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.style.ResolvedStyle;

/**
 * A loading spinner (type {@code spinner}): {@code spinner-dots} dots ({@code spinner-dot}) on a circle filling the
 * spinner's box, their opacity chasing round once per {@code spinner-period} seconds on the UI clock, the last one
 * at {@code spinner-fade}. Animated frames allocate nothing (the circle's sines are kept per dot count); a spinner
 * outside the tree does no work. Announced as progress named "Loading" (or the name given).
 */
public final class Spinner extends Control {

    private Element[] dots = new Element[0];
    private float[] cos = new float[0];
    private float[] sin = new float[0];
    private float placedW = -1;
    private float placedH = -1;
    private float placedDot = -1;
    private long started = -1;

    /** A spinner announced as {@code name}. */
    @AllowAllocation("controls are built once")
    public Spinner(Controls controls, String name) {
        super(controls, "spinner", Role.PROGRESS, name);
        controls.poll(this);
    }

    /** How many dots it shows now. */
    public int dots() {
        return dots.length;
    }

    /** Dot {@code index}. */
    public Element dot(int index) {
        return dots[index];
    }

    @AllowAllocation("the dot count changes with the theme, rarely")
    @AllowStrictMath("the circle is computed when the dot count changes, not per frame")
    private void rebuild(int n) {
        for (Element d : dots) {
            ui.destroy(d);
        }
        dots = new Element[n];
        cos = new float[n];
        sin = new float[n];
        for (int i = 0; i < n; i++) {
            dots[i] = part("spinner-dot");
            root.add(dots[i]);
            double a = StrictMath.PI * 2 * i / n - StrictMath.PI / 2;
            cos[i] = (float) StrictMath.cos(a);
            sin[i] = (float) StrictMath.sin(a);
        }
        placedW = -1;
    }

    @Override
    public void poll(long nanos) {
        int slot = root.slot();
        if (ui.store().rootOf(slot) == UiStore.NONE) {
            return;
        }
        ResolvedStyle style = ui.styles().styleOf(slot);
        int n = (int) style.getFloat(BasicStyles.SPINNER_DOTS);
        if (n != dots.length) {
            rebuild(n);
        }
        Layout l = ui.layout();
        float w = l.width(slot);
        float h = l.height(slot);
        float dot = l.width(dots[0].slot());
        if (w != placedW || h != placedH || dot != placedDot) {
            placedW = w;
            placedH = h;
            placedDot = dot;
            float r = Math.min(w, h) * 0.5f - dot * 0.5f;
            for (int i = 0; i < n; i++) {
                float x = w * 0.5f + cos[i] * r - dot * 0.5f;
                float y = h * 0.5f + sin[i] * r - dot * 0.5f;
                ui.visuals().offsets(dots[i].slot(), controls.units(x), controls.units(y), 0, 0);
            }
        }
        if (started < 0) {
            started = nanos;
        }
        float period = style.getFloat(BasicStyles.SPINNER_PERIOD);
        float t = period <= 0 || nanos <= 0 ? 0 : (nanos - started) * 1e-9f / period;
        float head = (t - (float) Math.floor(t)) * n;
        float fade = style.getFloat(BasicStyles.SPINNER_FADE);
        for (int i = 0; i < n; i++) {
            float age = head - i;
            if (age < 0) {
                age += n;
            }
            ui.visuals().opacity(dots[i].slot(), 1 - (1 - fade) * age / n);
        }
    }
}
