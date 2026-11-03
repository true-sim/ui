package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;

/**
 * A password field (type {@code passwordfield}): a {@link TextEditor} that shows each character as the style's
 * {@code text-mask}, starts the platform's text input as a password (no IME composition, prediction or learning;
 * composition events are ignored), never copies or cuts (nothing reaches the clipboard), deletes by character, and a
 * reveal toggle (type {@code toggle}, style name {@code reveal}, {@code :checked} while revealed) that shows the text.
 *
 * <pre>{@code
 * PasswordField pw = PasswordField.create(ui, null, "Password", "Show password");
 * form.add(pw.element());
 * pw.editor().listener(new TextEditor.Listener() { ... });
 * }</pre>
 */
public final class PasswordField implements UiListener {

    private final Ui ui;
    private final Element root;
    private final TextEditor editor;
    private final Element reveal;

    private PasswordField(Ui ui, String style, String label, String revealLabel) {
        this.ui = ui;
        root = ui.element("passwordfield", style);
        root.semantics(Role.GROUP, null);
        editor = TextEditor.field(ui, null, label);
        editor.password(true);
        reveal = ui.element("toggle", "reveal");
        reveal.semantics(Role.TOGGLE_BUTTON, revealLabel);
        root.add(editor.element());
        root.add(reveal);
        ui.on(reveal, EventType.CLICK, this);
    }

    /** A password field with style name {@code style}, announced as {@code label}; its toggle as {@code revealLabel}. */
    @AllowAllocation("controls are made when a UI is built")
    public static PasswordField create(Ui ui, String style, String label, String revealLabel) {
        return new PasswordField(ui, style, label, revealLabel);
    }

    /** The field's root (add it to a parent). */
    public Element element() {
        return root;
    }

    /** The editor (its text, listener, maximum length). */
    public TextEditor editor() {
        return editor;
    }

    /** The reveal toggle. */
    public Element revealToggle() {
        return reveal;
    }

    /** Shows ({@code true}) or masks the text. */
    public void revealed(boolean on) {
        editor.reveal(on);
        reveal.state(UiState.CHECKED, on);
        ui.repaint(editor.element());
    }

    /** Whether the text shows. */
    public boolean revealed() {
        return reveal.is(UiState.CHECKED);
    }

    /** The password (valid until the next edit; never put it on the clipboard). */
    public CharSequence text() {
        return editor.text();
    }

    @Override
    public void handle(UiEvent event) {
        revealed(!revealed());
        editor.focus(); // typing goes on in the field
        event.consume();
    }
}
