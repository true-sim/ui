package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.UiCommand;

/**
 * A time picker (type {@code time-picker}) bound two-way to a {@link VersionedInt} of seconds since midnight. Its
 * fields ({@code time-field}, ids {@code hours}, {@code minutes}, optionally {@code seconds}; each a label between a
 * {@code time-up} and a {@code time-down} button) are separated by {@code time-separator} labels; on a 12-hour clock a
 * {@code time-period} button switches AM and PM. A field takes focus: up and down (and the wheel) step it, wrapping
 * within its range, left and right move between fields. Changing the time allocates nothing.
 */
public final class TimePicker extends Control {

    private static final String[] TWO_DIGITS = new String[60];

    static {
        for (int i = 0; i < TWO_DIGITS.length; i++) {
            TWO_DIGITS[i] = (i < 10 ? "0" : "") + i;
        }
    }

    private static final int[] UNIT = {3600, 60, 1};

    private final boolean twelveHour;
    private final Element[] fields;
    private final Element[] values;
    private final Button[] ups;
    private final Button[] downs;
    private final Button period;
    private final String am;
    private final String pm;
    private VersionedInt model;
    private Watch watch;

    /**
     * A picker named {@code name} bound to {@code model}, showing seconds when {@code withSeconds}, on a 12-hour clock
     * labelled {@code am} and {@code pm} when {@code twelveHour}.
     */
    @AllowAllocation("controls are built once")
    public TimePicker(Controls controls, String name, VersionedInt model, boolean withSeconds, boolean twelveHour,
        String am, String pm) {
        super(controls, "time-picker", Role.GROUP, name);
        this.twelveHour = twelveHour;
        this.am = am;
        this.pm = pm;
        int n = withSeconds ? 3 : 2;
        fields = new Element[n];
        values = new Element[n];
        ups = new Button[n];
        downs = new Button[n];
        String[] ids = {"hours", "minutes", "seconds"};
        String[] names = {"Hours", "Minutes", "Seconds"};
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                Element sep = ui.label("time-separator", ":");
                sep.semantics(Role.NONE, null);
                root.add(sep);
            }
            Element field = part("time-field");
            field.id(ids[i]);
            field.semantics(Role.SPIN_BUTTON, names[i]);
            focusable(field);
            listen(field, EventType.COMMAND.bit() | EventType.WHEEL.bit());
            ups[i] = new Button(controls, "up", null, "More " + names[i].toLowerCase());
            ups[i].element().style("time-up");
            downs[i] = new Button(controls, "down", null, "Fewer " + names[i].toLowerCase());
            downs[i].element().style("time-down");
            values[i] = label("00");
            values[i].semantics(Role.NONE, null);
            field.add(ups[i].element()).add(values[i]).add(downs[i].element());
            int index = i;
            ups[i].onClick(() -> step(index, 1));
            downs[i].onClick(() -> step(index, -1));
            fields[i] = field;
            root.add(field);
        }
        if (twelveHour) {
            period = new Button(controls, am);
            period.element().style("time-period");
            period.onClick(() -> set(get() + 12 * 3600));
            root.add(period.element());
        } else {
            period = null;
        }
        controls.poll(this);
        bind(model);
    }

    /** Binds the picker to {@code value} (seconds since midnight); returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public TimePicker bind(VersionedInt value) {
        model = value;
        watch = value.watch();
        show();
        return this;
    }

    /** The model. */
    public VersionedInt model() {
        return model;
    }

    /** Seconds since midnight. */
    public int get() {
        return model.get();
    }

    /** Sets the time to {@code seconds} since midnight (wrapped into a day). */
    public void set(int seconds) {
        model.set(Math.floorMod(seconds, 24 * 3600));
        show();
    }

    /** Field {@code index} (0 hours, 1 minutes, 2 seconds). */
    public Element field(int index) {
        return fields[index];
    }

    /** The value label of field {@code index}. */
    public Element valueLabel(int index) {
        return values[index];
    }

    /** The AM/PM button, or null on a 24-hour clock. */
    public Button periodButton() {
        return period;
    }

    /** Steps field {@code index} by {@code delta}, wrapping within the field (the hours within the day). */
    public void step(int index, int delta) {
        int t = model.get();
        int h = t / 3600;
        int m = t / 60 % 60;
        int s = t % 60;
        if (index == 0) {
            h = Math.floorMod(h + delta, 24);
        } else if (index == 1) {
            m = Math.floorMod(m + delta, 60);
        } else {
            s = Math.floorMod(s + delta, 60);
        }
        set(h * UNIT[0] + m * UNIT[1] + s);
    }

    private void show() {
        watch.update();
        int t = model.get();
        int h = t / 3600;
        int shown = h;
        if (twelveHour) {
            shown = h % 12 == 0 ? 12 : h % 12;
            ui.text(period.labelElement(), h < 12 ? am : pm);
        }
        ui.text(values[0], TWO_DIGITS[shown]);
        ui.text(values[1], TWO_DIGITS[t / 60 % 60]);
        if (values.length > 2) {
            ui.text(values[2], TWO_DIGITS[t % 60]);
        }
    }

    @Override
    public void poll(long nanos) {
        if (watch.isChanged()) {
            show();
        }
    }

    private int fieldOf(int slot) {
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].slot() == slot) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void handle(UiEvent event) {
        int i = fieldOf(event.currentSlot());
        if (i < 0) {
            return;
        }
        if (event.type() == EventType.WHEEL) {
            if (event.scrollY() != 0) {
                event.consume();
                step(i, event.scrollY() > 0 ? 1 : -1);
            }
            return;
        }
        UiCommand c = event.command();
        if (c == UiCommand.UP || c == UiCommand.PAGE_UP) {
            event.consume();
            step(i, c == UiCommand.UP ? 1 : i == 0 ? 6 : 10);
        } else if (c == UiCommand.DOWN || c == UiCommand.PAGE_DOWN) {
            event.consume();
            step(i, c == UiCommand.DOWN ? -1 : i == 0 ? -6 : -10);
        } else if (c == UiCommand.RIGHT && i + 1 < fields.length || c == UiCommand.LEFT && i > 0) {
            event.consume();
            interaction().focus(fields[c == UiCommand.RIGHT ? i + 1 : i - 1], event.player());
        }
    }
}
