package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedValue;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;

/**
 * Minimal {@link ValueEditor}s for inline editing: numbers (typed digits, or stepped by the arrows and the wheel,
 * clamped), booleans (toggled by a click or the arrows) and enum constants (cycled by a click or the arrows). Text is
 * edited by the text-editing controls through the same interface. Each editor edits one value at a time (a table
 * edits one cell at a time). Views: a {@code value-editor} panel (numbers and enums: with a label) or a
 * {@code value-check} (booleans), with {@code :editing} while editing.
 */
public final class Editors {

    private Editors() {
    }

    /** Whole numbers in {@code min..max}, stepped by {@code step}. */
    public static ValueEditor<Integer> integer(int min, int max, int step) {
        return new IntegerEditor(min, max, step);
    }

    /** Numbers in {@code min..max}, stepped by {@code step}, shown with at most {@code decimals} decimals. */
    public static ValueEditor<Double> decimal(double min, double max, double step, int decimals) {
        return new DecimalEditor(min, max, step, decimals);
    }

    /** Booleans. */
    public static ValueEditor<Boolean> bool() {
        return new BooleanEditor();
    }

    /** Constants of {@code type}. */
    public static <E extends Enum<E>> ValueEditor<E> enumeration(Class<E> type) {
        return new EnumEditor<>(type);
    }

    /** Numbers: typed or stepped. */
    abstract static class NumberEditor<N extends Number> implements ValueEditor<N>, UiListener {

        private final double min;
        private final double max;
        private final double step;
        private final int decimals;
        private final StringBuilder text = new StringBuilder();
        private Ui ui;
        private Element label;
        private Element view;
        private VersionedValue<N> value;
        private boolean typing;

        NumberEditor(double min, double max, double step, int decimals) {
            this.min = min;
            this.max = max;
            this.step = step;
            this.decimals = decimals;
        }

        abstract N box(double v);

        @Override
        @AllowAllocation("editors are made once per column")
        public Element create(ViewContext context) {
            ui = context.ui();
            view = ui.element("value-editor", null);
            label = ui.label(null, "");
            label.id("number");
            view.add(label);
            view.semantics(Role.SPIN_BUTTON, ui.text(label));
            ui.interaction().focusable(view, true);
            ui.interaction().textInput(view, true);
            ui.interaction().on(view, EventType.COMMAND.bit() | EventType.TEXT.bit() | EventType.WHEEL.bit(), this);
            return view;
        }

        @Override
        public void edit(Element editorView, VersionedValue<N> holder) {
            value = holder;
            typing = false;
            show();
        }

        private void show() {
            text.setLength(0);
            N v = value.get();
            Format.append(text, v == null ? 0 : v.doubleValue(), decimals);
            ui.text(label, text);
        }

        private void set(double v) {
            double clamped = Math.max(min, Math.min(max, v));
            value.set(box(decimals == 0 ? Math.rint(clamped) : clamped));
            typing = false;
            show();
        }

        @Override
        @AllowAllocation("editing: typed text is parsed at commit")
        public boolean commit(Element editorView) {
            if (typing) {
                try {
                    set(Double.parseDouble(text.toString()));
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void cancel(Element editorView) {
            typing = false;
        }

        @Override
        public void handle(UiEvent e) {
            if (value == null) {
                return;
            }
            switch (e.type()) {
                case COMMAND -> {
                    switch (e.command()) {
                        case UP, RIGHT -> set(current() + step);
                        case DOWN, LEFT -> set(current() - step);
                        case DELETE_BACK -> {
                            if (!typing) {
                                typing = true;
                                text.setLength(0);
                            } else if (!text.isEmpty()) {
                                text.setLength(text.length() - 1);
                            }
                            ui.text(label, text);
                        }
                        default -> {
                            return;
                        }
                    }
                    e.consume();
                }
                case TEXT -> {
                    CharSequence typed = e.text();
                    if (!typing) {
                        typing = true;
                        text.setLength(0);
                    }
                    for (int i = 0; i < typed.length(); i++) {
                        char c = typed.charAt(i);
                        if (c >= '0' && c <= '9' || c == '.' && decimals > 0 || c == '-' && text.isEmpty() && min < 0) {
                            text.append(c);
                        }
                    }
                    ui.text(label, text);
                    e.consume();
                }
                case WHEEL -> {
                    set(current() + (e.scrollY() > 0 ? step : -step));
                    e.consume();
                }
                default -> {
                }
            }
        }

        private double current() {
            N v = value.get();
            return v == null ? 0 : v.doubleValue();
        }
    }

    /** Whole numbers. */
    static final class IntegerEditor extends NumberEditor<Integer> {

        IntegerEditor(int min, int max, int step) {
            super(min, max, step, 0);
        }

        @Override
        @AllowAllocation("editing: the edited value is boxed")
        Integer box(double v) {
            return (int) v;
        }
    }

    /** Numbers with decimals. */
    static final class DecimalEditor extends NumberEditor<Double> {

        DecimalEditor(double min, double max, double step, int decimals) {
            super(min, max, step, Math.max(0, Math.min(9, decimals)));
        }

        @Override
        @AllowAllocation("editing: the edited value is boxed")
        Double box(double v) {
            return v;
        }
    }

    /** Booleans: a check toggled by a click or the arrows. */
    static final class BooleanEditor implements ValueEditor<Boolean>, UiListener {

        private Element view;
        private VersionedValue<Boolean> value;

        @Override
        @AllowAllocation("editors are made once per column")
        public Element create(ViewContext context) {
            Ui ui = context.ui();
            view = ui.element("value-check", null);
            view.semantics(Role.CHECKBOX, "value");
            ui.interaction().focusable(view, true);
            ui.interaction().on(view, EventType.CLICK.bit() | EventType.COMMAND.bit(), this);
            return view;
        }

        @Override
        public void edit(Element editorView, VersionedValue<Boolean> holder) {
            value = holder;
            view.state(UiState.CHECKED, Boolean.TRUE.equals(holder.get()));
        }

        private void toggle() {
            boolean on = !Boolean.TRUE.equals(value.get());
            value.set(on ? Boolean.TRUE : Boolean.FALSE);
            view.state(UiState.CHECKED, on);
        }

        @Override
        public void handle(UiEvent e) {
            if (value == null) {
                return;
            }
            if (e.type() == EventType.CLICK) {
                toggle();
                e.consume();
            } else if (e.type() == EventType.COMMAND) {
                switch (e.command()) {
                    case LEFT, RIGHT, UP, DOWN -> {
                        toggle();
                        e.consume();
                    }
                    default -> {
                    }
                }
            }
        }
    }

    /** Enum constants: cycled. */
    static final class EnumEditor<E extends Enum<E>> implements ValueEditor<E>, UiListener {

        private final E[] constants;
        private Ui ui;
        private Element label;
        private VersionedValue<E> value;

        EnumEditor(Class<E> type) {
            constants = type.getEnumConstants();
        }

        @Override
        @AllowAllocation("editors are made once per column")
        public Element create(ViewContext context) {
            ui = context.ui();
            Element view = ui.element("value-editor", null);
            label = ui.label(null, "");
            label.id("enum");
            view.add(label);
            view.semantics(Role.COMBO_BOX, ui.text(label));
            ui.interaction().focusable(view, true);
            ui.interaction().on(view, EventType.CLICK.bit() | EventType.COMMAND.bit(), this);
            return view;
        }

        @Override
        public void edit(Element editorView, VersionedValue<E> holder) {
            value = holder;
            show();
        }

        private void show() {
            E v = value.get();
            ui.text(label, v == null ? "" : v.name());
        }

        private void cycle(int by) {
            E v = value.get();
            int i = v == null ? 0 : v.ordinal();
            int n = constants.length;
            value.set(constants[((i + by) % n + n) % n]);
            show();
        }

        @Override
        public void handle(UiEvent e) {
            if (value == null) {
                return;
            }
            if (e.type() == EventType.CLICK) {
                cycle(1);
                e.consume();
            } else if (e.type() == EventType.COMMAND) {
                switch (e.command()) {
                    case RIGHT, DOWN -> cycle(1);
                    case LEFT, UP -> cycle(-1);
                    default -> {
                        return;
                    }
                }
                e.consume();
            }
        }
    }
}
