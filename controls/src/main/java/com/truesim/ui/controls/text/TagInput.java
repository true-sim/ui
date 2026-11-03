package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.UiCommand;
import java.util.ArrayList;
import java.util.List;

/**
 * Tag (chip) input (type {@code taginput}): chips (type {@code chip}, each a label and a remove button of style name
 * {@code chip-remove}) followed by a {@link TextEditor}. Enter or a separator character turns the typed text into a
 * chip (trimmed, empty and repeated tags dropped); Backspace in an empty editor removes the last chip; a chip's
 * button removes it.
 */
@AllowAllocation("chips are made and removed when tags are added and removed, not per frame")
public final class TagInput implements UiListener, TextEditor.Listener {

    /** What a tag input tells its owner. */
    @FunctionalInterface
    public interface Listener {
        /** The tags changed. */
        void changed(TagInput input, List<String> tags);
    }

    private final Ui ui;
    private final Element root;
    private final TextEditor editor;
    private final List<String> tags = new ArrayList<>();
    private final List<Element> chips = new ArrayList<>();
    private final List<Element> removes = new ArrayList<>();
    private final String removeLabel;
    private String separators = ",;";
    private Listener listener;

    private TagInput(Ui ui, String style, String label, String removeLabel) {
        this.ui = ui;
        this.removeLabel = removeLabel;
        root = ui.element("taginput", style);
        root.semantics(Role.GROUP, null);
        editor = TextEditor.field(ui, null, label);
        root.add(editor.element());
        editor.listener(this);
        ui.interaction().onCapture(root, EventType.COMMAND.bit() | EventType.TEXT.bit(), this);
    }

    /**
     * A tag input with style name {@code style}, its editor announced as {@code label}, each chip's remove button as
     * {@code removeLabel} followed by the tag.
     */
    public static TagInput create(Ui ui, String style, String label, String removeLabel) {
        return new TagInput(ui, style, label, removeLabel);
    }

    /** The input's root. */
    public Element element() {
        return root;
    }

    /** The editor. */
    public TextEditor editor() {
        return editor;
    }

    /** The tags, in order (a live view: do not change it). */
    public List<String> tags() {
        return tags;
    }

    /** The characters that end a tag as they are typed (default comma and semicolon). */
    public TagInput separators(String value) {
        separators = value;
        return this;
    }

    /** Sets the listener. */
    public TagInput listener(Listener value) {
        listener = value;
        return this;
    }

    /** Adds {@code tag} (trimmed; nothing if empty or already there); true if added. */
    public boolean add(String tag) {
        String t = tag.strip();
        if (t.isEmpty() || tags.contains(t)) {
            return false;
        }
        Element chip = ui.element("chip", null);
        chip.semantics(Role.GROUP, null);
        Element text = ui.label(null, t);
        Element remove = ui.element("button", "chip-remove");
        remove.semantics(Role.BUTTON, removeLabel + " " + t);
        chip.add(text);
        chip.add(remove);
        ui.on(remove, EventType.CLICK, this);
        // before the editor: chips come first
        editor.element().detach();
        root.add(chip);
        root.add(editor.element());
        tags.add(t);
        chips.add(chip);
        removes.add(remove);
        changed();
        return true;
    }

    /** Removes tag {@code index}. */
    public void remove(int index) {
        tags.remove(index);
        removes.remove(index);
        ui.destroy(chips.remove(index));
        changed();
    }

    private void changed() {
        if (listener != null) {
            listener.changed(this, tags);
        }
    }

    /**
     * Turns the editor's text into tags, split at separators; the text after the last separator stays in the editor
     * unless {@code all} (Enter).
     */
    private void commit(boolean all) {
        String typed = editor.text().toString();
        int from = 0;
        for (int i = 0; i < typed.length(); i++) {
            if (separators.indexOf(typed.charAt(i)) >= 0) {
                add(typed.substring(from, i));
                from = i + 1;
            }
        }
        if (all) {
            add(typed.substring(from));
            from = typed.length();
        }
        editor.text(typed.substring(from));
    }

    @Override
    public void handle(UiEvent e) {
        switch (e.type()) {
            case CLICK -> {
                for (int i = 0; i < removes.size(); i++) {
                    if (removes.get(i).slot() == e.currentSlot()) {
                        remove(i);
                        editor.focus();
                        e.consume();
                        return;
                    }
                }
            }
            case COMMAND -> {
                if (e.command() == UiCommand.DELETE_BACK && editor.text().length() == 0 && !tags.isEmpty()) {
                    remove(tags.size() - 1);
                    e.consume();
                }
            }
            case TEXT -> {
                CharSequence typed = e.text();
                for (int i = 0; i < typed.length(); i++) {
                    if (separators.indexOf(typed.charAt(i)) >= 0) {
                        editor.editable().type(typed);
                        commit(false);
                        e.consume();
                        return;
                    }
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void submitted(TextEditor e) {
        commit(true);
    }
}
