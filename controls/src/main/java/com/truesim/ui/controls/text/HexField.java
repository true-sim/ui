package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.ColorPicker;
import com.truesim.ui.text.edit.TextFilters;

/**
 * A text field in a colour picker's hex slot ({@link ColorPicker#hexSlot()}), in place of its hex label: it shows the
 * colour as {@code #RRGGBBAA} while it is not being edited, takes hex digits only, and sets the colour on Enter and when
 * the focus leaves ({@link ColorPicker#hex(CharSequence)}: {@code #RGB}, {@code #RGBA}, {@code #RRGGBB},
 * {@code #RRGGBBAA}); text that is none of those shows {@code :invalid} and changes nothing. The field is a
 * {@code textfield} with style name {@code hex}.
 */
public final class HexField implements TextEditor.Listener, Runnable {

    private final ColorPicker picker;
    private final TextEditor field;

    private HexField(Ui ui, ColorPicker picker, String label) {
        this.picker = picker;
        field = TextEditor.field(ui, "hex", label).maxLength(9).filter(TextFilters.chars("[#0-9a-fA-F]"))
            .validator(this::parses);
        field.listener(this);
        picker.hexLabel().detach();
        picker.hexSlot().add(field.element());
        picker.onChange(this);
        run();
    }

    /** Puts a hex field into {@code picker}'s hex slot, announced as {@code label}. */
    @AllowAllocation("controls are made when a UI is built")
    public static HexField attach(Ui ui, ColorPicker picker, String label) {
        return new HexField(ui, picker, label);
    }

    /** The field. */
    public TextEditor field() {
        return field;
    }

    private boolean parses(CharSequence text) {
        int n = text.length() - (text.length() > 0 && text.charAt(0) == '#' ? 1 : 0);
        return n == 3 || n == 4 || n == 6 || n == 8;
    }

    /** The colour changed: shown unless the user is typing in the field. */
    @Override
    public void run() {
        if (!field.focused()) {
            field.text(picker.hex());
        }
    }

    @Override
    public void submitted(TextEditor editor) {
        commit();
    }

    @Override
    public void left(TextEditor editor) {
        commit();
        field.text(picker.hex());
    }

    private void commit() {
        if (field.valid()) {
            picker.hex(field.text());
        }
    }
}
