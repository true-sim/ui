package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.TextInputHost;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.text.edit.TextFilters;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A number field (type {@code numberfield}): a drag handle (type {@code handle}: drag sideways to change the value,
 * {@code :pressed} while dragging), a {@link TextEditor} taking a number (filtered as typed, {@code :invalid} out of
 * range), and steppers (type {@code button}, style names {@code step-down} and {@code step-up}). Up and Down step the
 * value, Page Up and Page Down by ten steps; Enter or leaving the field commits the typed value, clamped to the range
 * and rounded to the decimals.
 */
public final class NumberField implements UiListener, TextEditor.Listener {

    /** Pixels of a drag on the handle per step. */
    public static final Tunable DRAG_PIXELS = Tunable.of("ui.input.numberDragPixels", 4, 1, 100,
        "Pixels a number field's handle is dragged sideways per step of its value.");

    /** What a number field tells its owner. */
    @FunctionalInterface
    public interface Listener {
        /** The value changed (stepped, dragged, or typed and committed). */
        void changed(NumberField field, double value);
    }

    private final Ui ui;
    private final Element root;
    private final Element handle;
    private final TextEditor editor;
    private final Element down;
    private final Element up;
    private double value;
    private double min = -Double.MAX_VALUE;
    private double max = Double.MAX_VALUE;
    private double step = 1;
    private int decimals;
    private Listener listener;
    private float dragX;
    private double dragStart;
    private boolean dragging;

    private NumberField(Ui ui, String style, String label, String downLabel, String upLabel) {
        this.ui = ui;
        root = ui.element("numberfield", style);
        root.semantics(Role.SPIN_BUTTON, label);
        handle = ui.element("handle", null);
        handle.semantics(Role.SLIDER, label);
        editor = TextEditor.field(ui, null, label);
        ui.interaction().textInputKind(editor.element(), TextInputHost.NUMBER);
        down = ui.element("button", "step-down");
        down.semantics(Role.BUTTON, downLabel);
        up = ui.element("button", "step-up");
        up.semantics(Role.BUTTON, upLabel);
        root.add(handle);
        root.add(editor.element());
        root.add(down);
        root.add(up);
        ui.on(down, EventType.CLICK, this);
        ui.on(up, EventType.CLICK, this);
        ui.on(root, EventType.COMMAND, this);
        ui.interaction().on(handle, EventType.PRESS.bit() | EventType.MOVE.bit() | EventType.RELEASE.bit()
            | EventType.DRAG_START.bit(), this);
        editor.listener(this);
        filters();
        show();
    }

    /**
     * A number field with style name {@code style}, announced as {@code label}, its steppers as {@code downLabel} and
     * {@code upLabel}.
     */
    @AllowAllocation("controls are made when a UI is built")
    public static NumberField create(Ui ui, String style, String label, String downLabel, String upLabel) {
        return new NumberField(ui, style, label, downLabel, upLabel);
    }

    /** The field's root. */
    public Element element() {
        return root;
    }

    /** The editor. */
    public TextEditor editor() {
        return editor;
    }

    /** The drag handle. */
    public Element handle() {
        return handle;
    }

    /** The step-down button. */
    public Element stepDown() {
        return down;
    }

    /** The step-up button. */
    public Element stepUp() {
        return up;
    }

    /** The value. */
    public double value() {
        return value;
    }

    /** Sets the value (clamped and rounded) and shows it. */
    public NumberField value(double v) {
        set(v, false);
        return this;
    }

    /** The range. */
    @AllowAllocation("settings change when a UI is built")
    public NumberField range(double minimum, double maximum) {
        if (!(minimum <= maximum)) {
            throw new IllegalArgumentException("range " + minimum + " to " + maximum);
        }
        min = minimum;
        max = maximum;
        filters();
        set(value, false);
        return this;
    }

    /** The step of Up, Down, the steppers and the drag. */
    public NumberField step(double value) {
        step = value;
        return this;
    }

    /** Decimals shown and typed (0: whole numbers). */
    @AllowAllocation("settings change when a UI is built")
    public NumberField decimals(int n) {
        decimals = Math.max(0, n);
        filters();
        set(value, false);
        return this;
    }

    /** Sets the listener. */
    public NumberField listener(Listener l) {
        listener = l;
        return this;
    }

    @AllowAllocation("filters are made when settings change")
    private void filters() {
        editor.filter(TextFilters.number(min < 0, decimals));
        editor.validator(TextFilters.range(min, max, false));
    }

    private void set(double v, boolean notify) {
        double clamped = Math.max(min, Math.min(max, v));
        double rounded = round(clamped);
        boolean changed = rounded != value;
        value = rounded;
        show();
        if (changed && notify && listener != null) {
            listener.changed(this, value);
        }
    }

    private double round(double v) {
        return BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    @AllowAllocation("the shown value is written when it changes")
    private void show() {
        editor.text(BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toPlainString());
        root.state(UiState.INVALID, false);
    }

    /** Takes the typed value: clamped, rounded, shown. */
    private void commit() {
        double typed = TextFilters.parse(editor.text());
        set(Double.isNaN(typed) ? value : typed, true);
    }

    @Override
    public void handle(UiEvent e) {
        switch (e.type()) {
            case CLICK -> {
                set(value + (e.currentSlot() == up.slot() ? step : -step), true);
                e.consume();
            }
            case COMMAND -> {
                double steps = switch (e.command()) {
                    case UP -> 1;
                    case DOWN -> -1;
                    case PAGE_UP -> 10;
                    case PAGE_DOWN -> -10;
                    default -> 0;
                };
                if (steps != 0) {
                    commit();
                    set(value + steps * step, true);
                    e.consume();
                }
            }
            case PRESS -> {
                if (e.button() == Pointer.PRIMARY) {
                    dragging = true;
                    dragX = e.x();
                    dragStart = value;
                    e.consume(); // captured: the moves come here
                }
            }
            case MOVE -> {
                if (dragging && (e.buttons() & Pointer.PRIMARY) != 0) {
                    float pixels = DRAG_PIXELS.get() * (ui.layout().scale() > 0 ? ui.layout().scale() : 1);
                    set(dragStart + Math.round((e.x() - dragX) / pixels) * step, true);
                    e.consume();
                }
            }
            case RELEASE -> dragging = false;
            case DRAG_START -> e.consume();
            default -> {
            }
        }
    }

    @Override
    public void submitted(TextEditor e) {
        commit();
    }

    @Override
    public void changed(TextEditor e) {
        root.state(UiState.INVALID, !e.valid());
    }

    @Override
    public void left(TextEditor e) {
        commit();
    }
}
