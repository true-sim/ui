package com.truesim.ui.controls.files;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Button;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.Dialog;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import java.util.List;

/**
 * Open and save dialogs (the plan additions of design 13): a {@link FileBrowser} in a modal {@link Dialog} (style name
 * {@code file-dialog}) with an accept button ({@code Open}, {@code Save}, {@code Choose}) and {@code Cancel}. Accepting
 * takes the browser's {@linkplain FileBrowser#chosen choice}; saving over an existing file asks first (a confirm
 * dialog). The chosen paths go to the listener (empty when cancelled) and their folder to the model's recent places.
 *
 * <pre>{@code
 * FileDialog.open(controls, files, "Open map").filters(List.of(FileFilter.of("Maps (*.map)", "map")))
 *     .show(paths -> load(paths));
 * }</pre>
 */
public final class FileDialog {

    /** Told what was chosen. */
    @FunctionalInterface
    public interface Chosen {
        /** The chosen paths; empty when the dialog was cancelled. */
        void chosen(List<String> paths);
    }

    private static final int ACCEPT = 1;
    private static final int CANCEL = 0;

    private final Controls controls;
    private final FileBrowser browser;
    private final Dialog dialog;
    private final Button acceptButton;
    private Chosen chosen;
    private Dialog confirm;
    private List<String> result = List.of();
    private boolean done;

    @AllowAllocation("dialogs are built when shown")
    private FileDialog(Controls controls, FileModel model, CharSequence title, FileBrowser.Mode mode,
        CharSequence accept) {
        this.controls = controls;
        Ui ui = controls.ui();
        browser = new FileBrowser(controls, model).mode(mode);
        dialog = new Dialog(controls, title, "");
        dialog.element().style("file-dialog");
        ui.layout().visible(dialog.messageLabel().slot(), false);
        // the browser goes between the title and the buttons
        UiStore store = ui.store();
        Element buttons = store.element(store.lastChild(dialog.element().slot()));
        dialog.element().add(browser.element()).add(buttons);
        dialog.cancelButton("Cancel", CANCEL);
        // the accept button does not close by itself: the choice may be a folder to enter or need a confirmation
        acceptButton = new Button(controls, accept);
        acceptButton.element().style("default");
        acceptButton.onClick(this::accept);
        buttons.add(acceptButton.element());
        dialog.onClose(this::closed);
        browser.onAccept(this::accept);
    }

    /** A dialog choosing existing files, titled {@code title}. */
    @AllowAllocation("dialogs are built when shown")
    public static FileDialog open(Controls controls, FileModel model, CharSequence title) {
        return new FileDialog(controls, model, title, FileBrowser.Mode.OPEN, "Open");
    }

    /** A dialog naming a file to write, titled {@code title}. */
    @AllowAllocation("dialogs are built when shown")
    public static FileDialog save(Controls controls, FileModel model, CharSequence title) {
        return new FileDialog(controls, model, title, FileBrowser.Mode.SAVE, "Save");
    }

    /** A dialog choosing a folder, titled {@code title}. */
    @AllowAllocation("dialogs are built when shown")
    public static FileDialog folder(Controls controls, FileModel model, CharSequence title) {
        return new FileDialog(controls, model, title, FileBrowser.Mode.FOLDERS, "Choose");
    }

    /** Lets several files be chosen (open); returns this. */
    public FileDialog multiple(boolean on) {
        browser.multiple(on);
        return this;
    }

    /** The filter choices; returns this. */
    public FileDialog filters(List<FileFilter> choices) {
        browser.filters(choices);
        return this;
    }

    /** Starts in {@code directory}; returns this. */
    public FileDialog at(String directory) {
        browser.navigate(directory);
        return this;
    }

    /** Suggests {@code name} (save); returns this. */
    public FileDialog name(String name) {
        browser.nameField().text(name);
        return this;
    }

    /** Shows the dialog; {@code listener} is told the choice once it closes. Returns this. */
    public FileDialog show(Chosen listener) {
        chosen = listener;
        done = false;
        dialog.show();
        controls.focusSoon(browser.nameField().element(), 0);
        return this;
    }

    /** The browser inside. */
    public FileBrowser browser() {
        return browser;
    }

    /** The accept button. */
    public Button acceptButton() {
        return acceptButton;
    }

    /** The dialog. */
    public Dialog dialog() {
        return dialog;
    }

    /** The overwrite confirmation while it shows, else null. */
    public Dialog confirmation() {
        return confirm != null && confirm.showing() ? confirm : null;
    }

    /** What was chosen when it closed (empty: cancelled, or still open). */
    public List<String> result() {
        return result;
    }

    /** Accepts as the accept button does: takes the browser's choice, confirming an overwrite first. */
    @AllowAllocation("a user action")
    public void accept() {
        List<String> paths = browser.chosen();
        if (paths == null) {
            return; // a folder was entered, or nothing is chosen yet
        }
        if (browser.mode() == FileBrowser.Mode.SAVE && browser.model().stat(paths.get(0)) != null) {
            FileEntry existing = browser.model().stat(paths.get(0));
            confirm = Dialog.confirm(controls, "Replace " + existing.name() + "?",
                existing.name() + " exists already. Replace it?", "Replace", "Cancel");
            confirm.element().style("file-confirm");
            confirm.onClose(r -> {
                if (r == 1) {
                    finish(paths);
                }
            });
            confirm.show();
            return;
        }
        finish(paths);
    }

    @AllowAllocation("a user action")
    private void finish(List<String> paths) {
        result = List.copyOf(paths);
        FilePlaces places = browser.model().places();
        String folder = browser.model().parent(paths.get(0));
        places.used(browser.mode() == FileBrowser.Mode.FOLDERS || folder == null ? paths.get(0) : folder);
        done = true;
        dialog.close(ACCEPT);
    }

    /** The dialog closed: accepted, or by Cancel or CANCEL. */
    private void closed(int value) {
        if (value != ACCEPT) {
            result = List.of();
        }
        browser.close();
        if (chosen != null) {
            chosen.chosen(result);
        }
    }
}
