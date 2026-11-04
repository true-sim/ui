package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.anim.Animator;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.layout.Unit;
import java.util.Arrays;

/**
 * A modal message or confirm dialog on the {@link com.truesim.ui.core.Layers#MODAL} layer: a {@code dialog-backdrop}
 * covering the screen (it takes every pointer) holding the {@code dialog} (a title label of style name
 * {@code dialog-title}, a message label of style name {@code dialog-message}, and a {@code dialog-buttons} row; the
 * default button has the style name {@code default}). Showing it opens a modal focus scope
 * ({@link com.truesim.ui.core.event.Interaction#pushModal}): focus moves to the default button and stays inside; ACCEPT
 * presses the focused button, CANCEL the cancel button (or closes with {@link #CANCELLED}); focus returns to where it
 * was when it closes.
 *
 * <p>Both elements show {@code :expanded} from the frame after they appear until they are closed, so a theme
 * animates the dialog in and out with transitions ({@code dialog { opacity: 0; scale: 0.96; transition: ... }
 * dialog:expanded { opacity: 1; scale: 1; }}); a closed dialog leaves the layer when its opacity transition ends.
 */
public final class Dialog extends Control {

    /** The result of a dialog closed by CANCEL without a cancel button. */
    public static final int CANCELLED = -1;

    /** Told how a dialog closed. */
    @FunctionalInterface
    public interface Closed {
        /** The dialog closed with {@code result} (its button's, or {@link #CANCELLED}). */
        void closed(int result);
    }

    private final Element backdrop;
    private final Element buttons;
    private final Element titleLabel;
    private final Element messageLabel;
    private Button[] buttonList = new Button[2];
    private int[] results = new int[2];
    private int count;
    private Button defaultButton;
    private int cancelResult = CANCELLED;
    private Closed closed;
    private Element restoreFocus;
    private boolean showing;
    private boolean closing;
    private int frames;
    private final Runnable cancel = () -> close(cancelResult);
    private int result;

    /** A dialog titled {@code title} saying {@code message}; add its buttons, then {@link #show()}. */
    @AllowAllocation("dialogs are built when shown")
    public Dialog(Controls controls, CharSequence title, CharSequence message) {
        super(controls, "dialog", Role.DIALOG, title.toString());
        backdrop = part("dialog-backdrop");
        titleLabel = ui.label("dialog-title", title);
        messageLabel = ui.label("dialog-message", message);
        buttons = part("dialog-buttons");
        root.add(titleLabel).add(messageLabel).add(buttons);
        backdrop.add(root);
        ui.visuals().width(backdrop.slot(), 100, Unit.PERCENT);
        ui.visuals().height(backdrop.slot(), 100, Unit.PERCENT);
        controls.poll(this);
    }

    /** A message dialog with one button, {@code ok}, that is both default and cancel. */
    @AllowAllocation("dialogs are built when shown")
    public static Dialog message(Controls controls, CharSequence title, CharSequence message, CharSequence ok) {
        Dialog d = new Dialog(controls, title, message);
        d.defaultButton(ok, 0);
        d.cancelResult = 0;
        return d;
    }

    /** A confirm dialog: {@code ok} (result 1, the default) and {@code cancel} (result 0, also CANCEL's). */
    @AllowAllocation("dialogs are built when shown")
    public static Dialog confirm(Controls controls, CharSequence title, CharSequence message, CharSequence ok,
        CharSequence cancel) {
        Dialog d = new Dialog(controls, title, message);
        d.cancelButton(cancel, 0);
        d.defaultButton(ok, 1);
        return d;
    }

    /** Adds a button closing the dialog with {@code value}; returns it. */
    @AllowAllocation("dialogs are built when shown")
    public Button button(CharSequence text, int value) {
        Button b = new Button(controls, text);
        if (count == buttonList.length) {
            buttonList = Arrays.copyOf(buttonList, count * 2);
            results = Arrays.copyOf(results, count * 2);
        }
        buttonList[count] = b;
        results[count] = value;
        count++;
        b.onClick(() -> close(value));
        buttons.add(b.element());
        return b;
    }

    /** Adds the default button (focused first, style name {@code default}); returns it. */
    @AllowAllocation("dialogs are built when shown")
    public Button defaultButton(CharSequence text, int value) {
        Button b = button(text, value);
        b.element().style("default");
        defaultButton = b;
        return b;
    }

    /** Adds the cancel button: CANCEL presses it; returns it. */
    @AllowAllocation("dialogs are built when shown")
    public Button cancelButton(CharSequence text, int value) {
        Button b = button(text, value);
        cancelResult = value;
        return b;
    }

    /** Runs {@code listener} when the dialog closes; returns this. */
    public Dialog onClose(Closed listener) {
        closed = listener;
        return this;
    }

    /** Shows the dialog: modal, focus on the default button. */
    @AllowAllocation("showing a dialog opens a modal scope")
    public void show() {
        if (showing && !closing) {
            return;
        }
        if (!showing) {
            restoreFocus = interaction().focused(0);
            controls.modalLayer().root().add(backdrop);
            frames = 0;
        }
        interaction().pushModal(root, cancel); // a dialog shown again while fading out is modal again
        showing = true;
        closing = false;
        if (frames > 0) {
            expanded(true);
        }
        if (defaultButton != null) {
            controls.focusSoon(defaultButton.element(), 0);
        }
    }

    /** Closes the dialog with {@code value}: the listener is told at once, the dialog leaves when it has faded. */
    public void close(int value) {
        if (!showing || closing) {
            return;
        }
        closing = true;
        result = value;
        interaction().popModal(root);
        expanded(false);
        if (closed != null) {
            closed.closed(value);
        }
        finishIfStill();
    }

    private void expanded(boolean on) {
        backdrop.state(UiState.EXPANDED, on);
        root.state(UiState.EXPANDED, on);
    }

    private void finishIfStill() {
        Animator a = ui.animator();
        if (closing && !a.running(root.slot(), Animator.OPACITY) && !root.is(UiState.EXPANDED)
            && ui.store().dirty(root.slot()) == 0) {
            backdrop.detach();
            showing = false;
            closing = false;
            // the modal layer trapped focus while the dialog faded out: give it back now
            interaction().modalRoot(); // the layer's trap is gone with the backdrop
            Element f = interaction().focused(0);
            if ((f == null || controls.inside(backdrop.slot(), f.slot())) && restoreFocus != null
                && restoreFocus.alive()) {
                interaction().focus(restoreFocus);
            }
            restoreFocus = null;
        }
    }

    /** Whether it shows (also while fading out). */
    public boolean showing() {
        return showing;
    }

    /** The result it closed with last. */
    public int result() {
        return result;
    }

    /** Button {@code index}. */
    public Button button(int index) {
        return buttonList[index];
    }

    /** The backdrop element. */
    public Element backdrop() {
        return backdrop;
    }

    /** The message label. */
    public Element messageLabel() {
        return messageLabel;
    }

    /** The title label. */
    public Element titleLabel() {
        return titleLabel;
    }

    @Override
    public void poll(long nanos) {
        if (!showing) {
            return;
        }
        if (!closing) {
            if (frames++ == 1) {
                expanded(true);
            }
            return;
        }
        finishIfStill();
    }
}
