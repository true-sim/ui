package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.anim.Animator;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import java.util.Arrays;

/**
 * Notifications ("toasts"): a {@code toast-stack} on the {@code notifications} layer (z 350: over pop-ups, under
 * tooltips; where it sits is the theme's) of {@code toast}s, each a title label (style name {@code toast-title}), a
 * message label ({@code toast-message}) and a close button (id {@code dismiss}). A toast shows for
 * {@code ui.toast.duration} seconds (or its own), paused while the pointer is over it; at most {@code ui.toast.limit}
 * show (the oldest goes first). Like dialogs, a toast shows {@code :expanded} from its second frame until it is
 * dismissed and leaves when its opacity transition ends, so the theme animates it.
 */
public final class Toasts implements Controls.Polled {

    /** One notification. */
    public final class Toast {
        final Element element;
        final Button close;
        long deadline;
        float seconds;
        int frames;
        boolean leaving;
        Runnable onDismiss;

        @AllowAllocation("a toast is made when it is shown")
        Toast(CharSequence title, CharSequence message, float seconds) {
            element = controls.ui().element("toast", null);
            element.semantics(Role.ALERT, message.toString());
            if (title != null) {
                element.add(controls.ui().label("toast-title", title));
            }
            element.add(controls.ui().label("toast-message", message));
            close = new Button(controls, "dismiss", null, "Dismiss");
            close.element().id("dismiss");
            close.onClick(this::dismiss);
            element.add(close.element());
            controls.ui().interaction().pickable(element, com.truesim.ui.core.event.PointerEvents.ALL);
            this.seconds = seconds;
        }

        /** Dismisses the toast now (it fades out). */
        public void dismiss() {
            if (leaving) {
                return;
            }
            leaving = true;
            element.state(UiState.EXPANDED, false);
            if (onDismiss != null) {
                onDismiss.run();
            }
        }

        /** Runs {@code action} when the toast is dismissed (timed out or closed); returns this. */
        public Toast onDismiss(Runnable action) {
            onDismiss = action;
            return this;
        }

        /** The toast's element. */
        public Element element() {
            return element;
        }

        /** The close button. */
        public Button closeButton() {
            return close;
        }

        /** Whether it is leaving or gone. */
        public boolean dismissed() {
            return leaving;
        }
    }

    private final Controls controls;
    private Element stack;
    private Toast[] toasts = new Toast[8];
    private int count;
    private long last = -1;

    @AllowAllocation("one per controls")
    Toasts(Controls controls) {
        this.controls = controls;
        controls.poll(this);
    }

    /** Shows a notification for {@code ui.toast.duration} seconds; returns it. */
    public Toast show(CharSequence title, CharSequence message) {
        return show(title, message, Controls.TOAST_DURATION.get());
    }

    /** Shows a notification for {@code seconds} (0: until dismissed); returns it. */
    @AllowAllocation("a toast is made when it is shown")
    public Toast show(CharSequence title, CharSequence message, float seconds) {
        if (stack == null) {
            stack = controls.ui().element("toast-stack", null);
            stack.semantics(Role.GROUP, null);
            controls.notificationsLayer().add(stack);
        }
        Toast t = new Toast(title, message, seconds);
        if (count == toasts.length) {
            toasts = Arrays.copyOf(toasts, count * 2);
        }
        toasts[count++] = t;
        t.deadline = -1;
        stack.add(t.element);
        int live = 0;
        for (int i = count - 1; i >= 0; i--) {
            if (!toasts[i].leaving && ++live > Controls.TOAST_LIMIT.get()) {
                toasts[i].dismiss();
            }
        }
        return t;
    }

    /** Toasts showing now (also those leaving). */
    public int count() {
        return count;
    }

    /** Toast {@code index}, oldest first. */
    public Toast toast(int index) {
        return toasts[index];
    }

    /** The stack element, or null before the first toast. */
    public Element stack() {
        return stack;
    }

    @Override
    public void poll(long nanos) {
        long dt = last < 0 || nanos <= 0 ? 0 : nanos - last;
        last = nanos;
        Animator a = controls.ui().animator();
        for (int i = 0; i < count; i++) {
            Toast t = toasts[i];
            if (t.frames++ == 1 && !t.leaving) {
                t.element.state(UiState.EXPANDED, true);
            }
            if (t.leaving) {
                if (!a.running(t.element.slot(), Animator.OPACITY) && controls.ui().store().dirty(t.element.slot()) == 0
                    && t.frames > 1) {
                    controls.ui().destroy(t.element);
                    System.arraycopy(toasts, i + 1, toasts, i, count - i - 1);
                    toasts[--count] = null;
                    i--;
                }
                continue;
            }
            if (t.seconds <= 0) {
                continue;
            }
            if (t.deadline < 0) {
                t.deadline = nanos + (long) (t.seconds * 1e9f);
            }
            if (t.element.is(UiState.HOVER)) {
                t.deadline += dt; // paused while the pointer rests on it
            } else if (nanos <= 0 || nanos >= t.deadline) {
                t.dismiss();
            }
        }
    }
}
