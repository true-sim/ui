package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedValue;
import com.truesim.ui.controls.data.ValueEditor;
import com.truesim.ui.controls.data.ViewContext;
import com.truesim.ui.core.Element;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A text field as the data controls' {@link ValueEditor} of strings: a table cell's inline editor, a property's
 * field. Editing starts with the value selected (typing replaces it); Enter commits (the field passes SUBMIT on to the
 * table), Escape cancels, focus leaving commits. A {@link #validator} refuses invalid text: editing goes on and the
 * field shows {@code :invalid}. The field is a {@code textfield} with style name {@code cell}, so themes style it apart
 * from forms' fields.
 */
public final class TextCellEditor implements ValueEditor<String> {

    private final String label;
    private Predicate<CharSequence> validator;
    private int maxLength;
    private final Map<Element, Editing> editing = new IdentityHashMap<>();

    private TextCellEditor(String label) {
        this.label = label;
    }

    /** An editor of strings, its fields announced as {@code label}. */
    @AllowAllocation("editors are made once per column")
    public static TextCellEditor create(String label) {
        return new TextCellEditor(label);
    }

    /** Refuses commits of text {@code test} rejects; returns this. */
    public TextCellEditor validator(Predicate<CharSequence> test) {
        validator = test;
        return this;
    }

    /** At most {@code n} characters (0: no limit); returns this. */
    public TextCellEditor maxLength(int n) {
        maxLength = n;
        return this;
    }

    @Override
    @AllowAllocation("an editor view is made once per column")
    public Element create(ViewContext context) {
        TextEditor field = TextEditor.field(context.ui(), "cell", label).passSubmit(true);
        if (validator != null) {
            field.validator(validator);
        }
        if (maxLength > 0) {
            field.maxLength(maxLength);
        }
        Editing e = new Editing(field);
        editing.put(field.element(), e);
        return field.element();
    }

    /** The text field of {@code view} (made by {@link #create}). */
    public TextEditor field(Element view) {
        return editing.get(view).field;
    }

    @Override
    public void edit(Element view, VersionedValue<String> value) {
        Editing e = editing.get(view);
        e.value = value;
        String v = value.get();
        e.field.text(v == null ? "" : v);
        e.field.editable().selectAll();
    }

    @Override
    @AllowAllocation("a commit makes the new value's string")
    public boolean commit(Element view) {
        Editing e = editing.get(view);
        if (e.value == null || !e.field.valid()) {
            return e.value == null;
        }
        e.value.set(e.field.text().toString());
        e.value = null;
        return true;
    }

    @Override
    public void cancel(Element view) {
        editing.get(view).value = null;
    }

    /** A view's field and the value it edits. */
    private static final class Editing {
        final TextEditor field;
        VersionedValue<String> value;

        Editing(TextEditor field) {
            this.field = field;
        }
    }
}
