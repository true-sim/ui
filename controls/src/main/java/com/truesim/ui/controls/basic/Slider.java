package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedDouble;
import com.truesim.common.state.Watch;
import com.truesim.ui.controls.anim.Visuals;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.GrowDirection;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.style.Facts;

/**
 * A slider (type {@code slider} with the fact {@code orientation=horizontal} or {@code vertical}; parts
 * {@code slider-track}, {@code slider-fill}, {@code slider-tick}s and one or two {@code slider-thumb}s), bound two-way
 * to a {@link VersionedDouble} (or two, for a range) between a minimum and maximum, snapped to a step when it has one.
 *
 * <p>The slider lays out as anchors: the code anchors the track across the middle, each thumb centred on its value's
 * fraction and the fill between the minimum (or the low thumb) and the thumb, so a value change moves them in the
 * same frame without measuring; sizes and looks are the theme's. A press anywhere jumps the nearest thumb there and
 * drags it ({@code :pressed} on the thumb); the arrow keys step it, page keys move a tenth (or
 * {@code ui.slider.pageFraction}), home and end go to the ends, the wheel steps it while the pointer is over it. A
 * range slider's thumbs take focus and keys each.
 *
 * <p>A {@link SliderScale} ({@link #scale}) maps values to the track: linear, logarithmic or symlog; thumbs, ticks
 * ({@link #ticks}, {@link #ticksAt}, {@link #scaleTicks}), drags and keyboard steps work in its coordinate.
 */
public final class Slider extends Control {

    private final boolean vertical;
    private final boolean range;
    private final Element track;
    private final Element fill;
    private final Element[] thumbs;
    private Element[] ticks = new Element[0];
    private final VersionedDouble[] models;
    private final Watch[] watches;
    private double min;
    private double max;
    private double step;
    private SliderScale scale = SliderScale.LINEAR;
    private int dragging = -1;

    /** A single-value slider named {@code name} over {@code min..max}, bound to {@code value}. */
    @AllowAllocation("controls are built once")
    public Slider(Controls controls, String name, VersionedDouble value, double min, double max, boolean vertical) {
        this(controls, name, null, value, null, min, max, vertical);
    }

    /**
     * A range slider named {@code name} over {@code min..max}, its thumbs named {@code lowName} and {@code highName}
     * and bound to {@code low} and {@code high}.
     */
    @AllowAllocation("controls are built once")
    public Slider(Controls controls, String name, String lowName, VersionedDouble low, String highName,
        VersionedDouble high, double min, double max, boolean vertical) {
        this(controls, name, new String[] {lowName, highName}, low, high, min, max, vertical);
    }

    @AllowAllocation("controls are built once")
    private Slider(Controls controls, String name, String[] thumbNames, VersionedDouble a, VersionedDouble b,
        double min, double max, boolean vertical) {
        super(controls, "slider", Role.SLIDER, name);
        if (!(max > min)) {
            throw new IllegalArgumentException("the maximum must exceed the minimum: " + min + ".." + max);
        }
        this.vertical = vertical;
        this.range = b != null;
        this.min = min;
        this.max = max;
        ui.styles().facts(root.slot(), Facts.of("orientation", vertical ? "vertical" : "horizontal"));
        track = part("slider-track");
        fill = part("slider-fill");
        root.add(track).add(fill);
        int n = range ? 2 : 1;
        thumbs = new Element[n];
        models = new VersionedDouble[n];
        watches = new Watch[n];
        models[0] = a;
        if (range) {
            models[1] = b;
        }
        for (int i = 0; i < n; i++) {
            thumbs[i] = part("slider-thumb");
            root.add(thumbs[i]);
            grow(thumbs[i]);
            watches[i] = models[i].watch();
            if (range) {
                thumbs[i].semantics(Role.SLIDER, thumbNames[i]);
                focusable(thumbs[i]);
            }
        }
        grow(track);
        grow(fill);
        if (vertical) {
            ui.visuals().anchors(track.slot(), 0.5f, 0, 0.5f, 1);
        } else {
            ui.visuals().anchors(track.slot(), 0, 0.5f, 1, 0.5f);
        }
        if (!range) {
            focusable(root);
        }
        listen(root, EventType.PRESS.bit() | EventType.MOVE.bit() | EventType.RELEASE.bit() | EventType.WHEEL.bit()
            | EventType.COMMAND.bit() | EventType.CLICK.bit());
        controls.poll(this);
        show();
    }

    private void grow(Element e) {
        ui.layout().growDirection(e.slot(), GrowDirection.BOTH, GrowDirection.BOTH);
    }

    /** Snaps values to multiples of {@code value} from the minimum (0: continuous); returns this. */
    public Slider step(double value) {
        step = value < 0 ? 0 : value;
        return this;
    }

    /** The step (0: continuous). */
    public double step() {
        return step;
    }

    /**
     * Maps values to the track by {@code value} (linear by default; {@link SliderScale#logarithmic},
     * {@link SliderScale#symlog}); thumbs, ticks, drags and keyboard steps follow it; returns this.
     */
    public Slider scale(SliderScale value) {
        value.check(min, max);
        scale = value;
        show();
        return this;
    }

    /** The scale. */
    public SliderScale scale() {
        return scale;
    }

    /** Changes the range to {@code minimum..maximum} (the values are clamped into it); returns this. */
    public Slider range(double minimum, double maximum) {
        scale.check(minimum, maximum);
        min = minimum;
        max = maximum;
        for (int i = 0; i < models.length; i++) {
            double v = models[i].get();
            if (v < min || v > max) {
                models[i].set(Math.max(min, Math.min(max, v)));
            }
        }
        show();
        return this;
    }

    /** The range's minimum. */
    public double min() {
        return min;
    }

    /** The range's maximum. */
    public double max() {
        return max;
    }

    /**
     * Shows {@code count} ticks ({@code slider-tick}) evenly spaced along the track from the minimum to the maximum
     * (evenly in the scale's coordinate: at powers on a logarithmic one; 0: none); returns this.
     */
    @AllowAllocation("ticks are made when a control is configured")
    public Slider ticks(int count) {
        float[] at = new float[count < 2 ? 0 : count];
        for (int i = 0; i < at.length; i++) {
            at[i] = i / (float) (at.length - 1);
        }
        return placeTicks(at);
    }

    /** Shows ticks at {@code values} (placed on the scale; values outside the range are left out); returns this. */
    @AllowAllocation("ticks are made when a control is configured")
    public Slider ticksAt(double... values) {
        int n = 0;
        float[] at = new float[values.length];
        for (double v : values) {
            if (v >= min && v <= max) {
                at[n++] = (float) scale.fraction(v, min, max);
            }
        }
        return placeTicks(java.util.Arrays.copyOf(at, n));
    }

    /**
     * Shows ticks at the scale's natural values: the powers of a logarithmic scale's base, zero and the powers either
     * side on a symlog one, the step's multiples (at most 101) or ten intervals on a linear one; returns this.
     */
    @AllowAllocation("ticks are made when a control is configured")
    public Slider scaleTicks() {
        if (scale.linear()) {
            int intervals = step > 0 && (max - min) / step <= 100 ? (int) Math.round((max - min) / step) : 10;
            return ticks(intervals + 1);
        }
        double[] values = new double[64];
        int n = scale.ticks(min, max, values);
        return ticksAt(java.util.Arrays.copyOf(values, n));
    }

    @AllowAllocation("ticks are made when a control is configured")
    private Slider placeTicks(float[] fractions) {
        for (Element t : ticks) {
            ui.destroy(t);
        }
        ticks = new Element[fractions.length];
        for (int i = 0; i < ticks.length; i++) {
            Element t = part("slider-tick");
            grow(t);
            float f = fractions[i];
            if (vertical) {
                ui.visuals().anchors(t.slot(), 0.5f, 1 - f, 0.5f, 1 - f);
            } else {
                ui.visuals().anchors(t.slot(), f, 0.5f, f, 0.5f);
            }
            ticks[i] = t;
        }
        // ticks draw under the fill and thumbs
        fill.detach();
        for (Element t : ticks) {
            root.add(t);
        }
        root.add(fill);
        for (Element th : thumbs) {
            th.detach();
            root.add(th);
        }
        return this;
    }

    /** Tick {@code index}. */
    public Element tick(int index) {
        return ticks[index];
    }

    /** How many ticks show. */
    public int ticks() {
        return ticks.length;
    }

    /** The value (the low end of a range). */
    public double value() {
        return models[0].get();
    }

    /** The high end of a range (the value of a single slider). */
    public double high() {
        return models[range ? 1 : 0].get();
    }

    /** The model of thumb {@code index} (0, or 1 for a range's high end). */
    public VersionedDouble model(int index) {
        return models[index];
    }

    /** Thumb {@code index}. */
    public Element thumb(int index) {
        return thumbs[index];
    }

    /** The fill element. */
    public Element fill() {
        return fill;
    }

    /** The track element. */
    public Element track() {
        return track;
    }

    /** Sets thumb {@code index}'s value: snapped, clamped, and kept on its side of the other thumb. */
    public void set(int index, double value) {
        double v = snap(value);
        if (range) {
            v = index == 0 ? Math.min(v, models[1].get()) : Math.max(v, models[0].get());
        }
        models[index].set(v);
        show();
    }

    private double snap(double value) {
        double v = Math.max(min, Math.min(max, value));
        if (step > 0) {
            v = min + Math.rint((v - min) / step) * step;
            v = Math.max(min, Math.min(max, v));
        }
        return v;
    }

    private float fraction(double value) {
        return (float) scale.fraction(value, min, max);
    }

    private void show() {
        Visuals v = ui.visuals();
        for (int i = 0; i < thumbs.length; i++) {
            watches[i].update();
            float f = fraction(models[i].get());
            if (vertical) {
                v.anchors(thumbs[i].slot(), 0.5f, 1 - f, 0.5f, 1 - f);
            } else {
                v.anchors(thumbs[i].slot(), f, 0.5f, f, 0.5f);
            }
        }
        float lo = range ? fraction(models[0].get()) : 0;
        float hi = fraction(models[range ? 1 : 0].get());
        if (vertical) {
            v.anchors(fill.slot(), 0.5f, 1 - hi, 0.5f, 1 - lo);
        } else {
            v.anchors(fill.slot(), lo, 0.5f, hi, 0.5f);
        }
    }

    @Override
    public void poll(long nanos) {
        for (Watch w : watches) {
            if (w.isChanged()) {
                show();
                return;
            }
        }
    }

    /** The value under the pointer at UI pixels {@code x, y}. */
    private double valueAt(float x, float y) {
        Layout l = ui.layout();
        int t = track.slot();
        float f;
        if (vertical) {
            float h = l.height(t);
            f = h <= 0 ? 0 : 1 - (y - l.y(t)) / h;
        } else {
            float w = l.width(t);
            f = w <= 0 ? 0 : (x - l.x(t)) / w;
        }
        return scale.value(f, min, max);
    }

    private int nearest(double value) {
        if (!range) {
            return 0;
        }
        double lo = models[0].get();
        double hi = models[1].get();
        if (lo == hi) {
            return value < lo ? 0 : 1;
        }
        return Math.abs(value - lo) <= Math.abs(value - hi) ? 0 : 1;
    }

    private int focusedThumb(int player) {
        if (!range) {
            return 0;
        }
        Element f = interaction().focused(player);
        return f == thumbs[1] ? 1 : f == thumbs[0] ? 0 : -1;
    }

    private double keyStep() {
        return step > 0 ? step : (max - min) / 100;
    }

    private double pageStep() {
        double page = (max - min) * Controls.SLIDER_PAGE.get();
        return step > 0 ? Math.max(step, Math.rint(page / step) * step) : page;
    }

    /**
     * {@code value} moved {@code steps} key steps ({@code page}: page steps): by the step (or a hundredth of the range)
     * on a linear scale; on another, by a hundredth of the track (a page: {@code ui.slider.pageFraction}) in the scale's
     * coordinate, then snapped to the step, at least one step on.
     */
    private double stepped(double value, int steps, boolean page) {
        if (scale.linear()) {
            return value + steps * (page ? pageStep() : keyStep());
        }
        double unit = page ? Controls.SLIDER_PAGE.get() : 0.01;
        double next = scale.value(scale.fraction(value, min, max) + steps * unit, min, max);
        if (step > 0 && Math.abs(snap(next) - snap(value)) < step * 0.5) {
            next = value + Math.signum(steps) * step; // the scale moved less than a step here: one step
        }
        return next;
    }

    @Override
    public void handle(UiEvent event) {
        switch (event.type()) {
            case PRESS -> {
                event.consume();
                double v = valueAt(event.x(), event.y());
                dragging = nearest(v);
                thumbs[dragging].state(UiState.PRESSED, true);
                set(dragging, v);
            }
            case MOVE -> {
                if (dragging >= 0 && event.buttons() != 0) {
                    set(dragging, valueAt(event.x(), event.y()));
                }
            }
            case RELEASE -> {
                if (dragging >= 0) {
                    thumbs[dragging].state(UiState.PRESSED, false);
                    if (range) {
                        interaction().focus(thumbs[dragging], event.player()); // after the press moved focus
                    }
                    dragging = -1;
                }
            }
            case CLICK -> event.consume();
            case WHEEL -> {
                float d = vertical || event.scrollY() != 0 ? event.scrollY() : event.scrollX();
                if (d != 0) {
                    event.consume();
                    int i = range ? Math.max(0, focusedThumb(event.player())) : 0;
                    set(i, stepped(models[i].get(), d > 0 ? 1 : -1, false));
                }
            }
            case COMMAND -> command(event);
            default -> {
                // nothing else
            }
        }
    }

    private void command(UiEvent event) {
        int i = focusedThumb(event.player());
        if (i < 0) {
            return;
        }
        UiCommand c = event.command();
        double v = models[i].get();
        double next;
        if (c == UiCommand.RIGHT && !vertical || c == UiCommand.UP && vertical) {
            next = stepped(v, 1, false);
        } else if (c == UiCommand.LEFT && !vertical || c == UiCommand.DOWN && vertical) {
            next = stepped(v, -1, false);
        } else if (c == UiCommand.PAGE_UP) {
            next = stepped(v, 1, true);
        } else if (c == UiCommand.PAGE_DOWN) {
            next = stepped(v, -1, true);
        } else if (c == UiCommand.HOME) {
            next = min;
        } else if (c == UiCommand.END) {
            next = max;
        } else {
            return;
        }
        event.consume();
        set(i, next);
    }
}
