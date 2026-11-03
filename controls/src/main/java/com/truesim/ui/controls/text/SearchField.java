package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.UiCommand;

/**
 * A search box (type {@code searchfield}): a {@link TextEditor} and a clear button (type {@code button}, style name
 * {@code clear}) shown while there is text. Enter submits ({@link Listener#search}); Escape (CANCEL) clears the text
 * first, and only an empty box lets it pass on (to close a dialog); every change is reported for search as you type.
 */
public final class SearchField implements UiListener, TextEditor.Listener {

    /** What a search box tells its owner. */
    public interface Listener {
        /** Enter: search for {@code query}. */
        void search(SearchField field, CharSequence query);

        /** The query changed (search as you type). */
        default void changed(SearchField field, CharSequence query) {
        }
    }

    private final Ui ui;
    private final Element root;
    private final TextEditor editor;
    private final Element clear;
    private Listener listener;

    private SearchField(Ui ui, String style, String label, String clearLabel) {
        this.ui = ui;
        root = ui.element("searchfield", style);
        root.semantics(Role.GROUP, null);
        editor = TextEditor.field(ui, null, label);
        clear = ui.element("button", "clear");
        clear.semantics(Role.BUTTON, clearLabel);
        root.add(editor.element());
        root.add(clear);
        ui.on(clear, EventType.CLICK, this);
        ui.on(root, EventType.COMMAND, this);
        editor.listener(this);
        showClear();
    }

    /** A search box with style name {@code style}, announced as {@code label}, its clear button as {@code clearLabel}. */
    @AllowAllocation("controls are made when a UI is built")
    public static SearchField create(Ui ui, String style, String label, String clearLabel) {
        return new SearchField(ui, style, label, clearLabel);
    }

    /** The box's root. */
    public Element element() {
        return root;
    }

    /** The editor. */
    public TextEditor editor() {
        return editor;
    }

    /** The clear button. */
    public Element clearButton() {
        return clear;
    }

    /** The query. */
    public CharSequence query() {
        return editor.text();
    }

    /** Sets the listener. */
    public SearchField listener(Listener value) {
        listener = value;
        return this;
    }

    /** Empties the box. */
    public void clear() {
        editor.text("");
        changed(editor);
    }

    private void showClear() {
        ui.layout().visible(clear.slot(), editor.text().length() > 0);
    }

    @Override
    public void handle(UiEvent e) {
        if (e.type() == EventType.CLICK) {
            clear();
            editor.focus();
            e.consume();
        } else if (e.command() == UiCommand.CANCEL && editor.text().length() > 0) {
            clear();
            e.consume();
        }
    }

    @Override
    public void changed(TextEditor e) {
        showClear();
        if (listener != null) {
            listener.changed(this, editor.text());
        }
    }

    @Override
    public void submitted(TextEditor e) {
        if (listener != null) {
            listener.search(this, editor.text());
        }
    }
}
