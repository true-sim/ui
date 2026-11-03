package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.UiCommand;
import java.util.Arrays;

/**
 * One choice among several, bound two-way to a {@link VersionedInt} (the chosen index, -1 for none): the base of
 * {@link RadioGroup} and {@link SegmentedControl}. The chosen option shows {@code :checked}; arrow keys move the choice
 * (and focus) within the group, wrapping, as radio groups do.
 */
public abstract class ChoiceGroup extends Control {

    private final String optionType;
    private final boolean horizontal;
    private Option[] options = new Option[4];
    private int count;
    private VersionedInt model;
    private Watch watch;

    /** One option: a button whose click chooses it. */
    public final class Option extends Button {
        private final int index;

        @AllowAllocation("controls are built once")
        Option(CharSequence text, String iconName, String name, int index) {
            super(ChoiceGroup.this.controls, optionType, Role.RADIO, text, iconName, name);
            this.index = index;
        }

        /** Its index in the group. */
        public int index() {
            return index;
        }

        @Override
        void clicked() {
            select(index);
        }
    }

    @AllowAllocation("controls are built once")
    ChoiceGroup(Controls controls, String type, String optionType, String name, boolean horizontal,
        VersionedInt model) {
        super(controls, type, Role.GROUP, name);
        this.optionType = optionType;
        this.horizontal = horizontal;
        listen(root, EventType.COMMAND.bit());
        controls.poll(this);
        bind(model);
    }

    /** Adds an option showing {@code text}; returns it. */
    @AllowAllocation("controls are built once")
    public Option add(CharSequence text) {
        return add(text, null, null);
    }

    /** Adds an option with {@code text} (null: icon only) and the icon {@code iconName} (null: none); returns it. */
    @AllowAllocation("controls are built once")
    public Option add(CharSequence text, String iconName, String name) {
        Option o = new Option(text, iconName, name, count);
        decorate(o);
        if (count == options.length) {
            options = Arrays.copyOf(options, count * 2);
        }
        options[count++] = o;
        root.add(o.element());
        show();
        return o;
    }

    /** Adds parts to a new option (a radio's dot). */
    void decorate(Option option) {
        // segments have none
    }

    /** Binds the group to {@code value}; returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public ChoiceGroup bind(VersionedInt value) {
        model = value;
        watch = value.watch();
        show();
        return this;
    }

    /** The model: the chosen index, -1 for none. */
    public VersionedInt model() {
        return model;
    }

    /** The chosen index. */
    public int selected() {
        return model.get();
    }

    /** Chooses option {@code index} (-1: none). */
    public void select(int index) {
        model.set(index);
        show();
    }

    /** Option {@code index}. */
    public Option option(int index) {
        return options[index];
    }

    /** How many options there are. */
    public int count() {
        return count;
    }

    private void show() {
        if (watch == null) {
            return;
        }
        watch.update();
        int chosen = model.get();
        for (int i = 0; i < count; i++) {
            deepState(options[i].element(), UiState.CHECKED, i == chosen);
        }
    }

    @Override
    public void handle(UiEvent event) {
        if (event.type() != EventType.COMMAND || count == 0) {
            return;
        }
        UiCommand c = event.command();
        int step = 0;
        if (c == (horizontal ? UiCommand.LEFT : UiCommand.UP)) {
            step = -1;
        } else if (c == (horizontal ? UiCommand.RIGHT : UiCommand.DOWN)) {
            step = 1;
        }
        if (step == 0) {
            return;
        }
        int from = focusedIndex(event.player());
        if (from < 0) {
            return;
        }
        int next = from;
        for (int tries = 0; tries < count; tries++) {
            next = (next + step + count) % count;
            if (!interaction().disabled(options[next].element().slot())) {
                break;
            }
        }
        event.consume();
        select(next);
        interaction().focus(options[next].element(), event.player());
    }

    private int focusedIndex(int player) {
        Element f = interaction().focused(player);
        for (int i = 0; i < count; i++) {
            if (options[i].element() == f) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void poll(long nanos) {
        if (watch.isChanged()) {
            show();
        }
    }
}
