package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.layout.Layout;
import java.util.List;

/**
 * Runs an action while an element is held (the repeat of a {@link RepeatButton}, a number field's steppers, a date
 * picker's month arrows): once on the press, then after {@link #DELAY} seconds again {@link #RATE} times a second
 * (at most once a frame) until the release, as a held key repeats through the engine's action system (the same delay and rate by default).
 * While the held pointer is off the element the repeats pause, and resume when it comes back. A click from the keyboard
 * or a gamepad ({@code ACCEPT}) runs the action once: their repeats arrive as more commands. The press's own click is
 * swallowed, so nothing runs twice.
 */
public final class Repeater implements UiListener, Ui.FrameListener {

    /** Seconds a held button waits before it repeats (the action system's {@code ui.repeatDelay} by default). */
    public static final Tunable DELAY = Tunable.of("ui.button.repeatDelay", 0.4f, 0.05f, 2,
        "Seconds a held repeat button (a stepper, a scroll arrow) waits before it repeats");
    /** Repeats a second of a held button (the action system's {@code ui.repeatRate} by default). */
    public static final Tunable RATE = Tunable.of("ui.button.repeatRate", 20, 1, 60,
        "Times a second a held repeat button repeats its action");

    private final Ui ui;
    private final Element element;
    private final Runnable action;
    private boolean held;
    private boolean over;
    private int pointer = -1;
    private long next;
    private long now;
    private int repeats;

    /** Repeats {@code action} while {@code element} is held with the primary pointer button. */
    @AllowAllocation("controls are built once")
    @SuppressWarnings("this-escape")
    public Repeater(Ui ui, Element element, Runnable action) {
        this.ui = ui;
        this.element = element;
        this.action = action;
        ui.interaction().on(element, EventType.PRESS.bit() | EventType.MOVE.bit() | EventType.RELEASE.bit()
            | EventType.CLICK.bit() | EventType.DRAG_START.bit(), this);
        ui.addFrameListener(this);
    }

    /** The tunables of repeating buttons. */
    public static List<Tunable> tunables() {
        return List.of(DELAY, RATE);
    }

    /** The element held. */
    public Element element() {
        return element;
    }

    /** Whether it is held now. */
    public boolean held() {
        return held;
    }

    /** How many repeats (after the first run) the current or last hold made. */
    public int repeats() {
        return repeats;
    }

    @Override
    public void handle(UiEvent e) {
        switch (e.type()) {
            case PRESS -> {
                if (e.button() != Pointer.PRIMARY || element.is(UiState.DISABLED)) {
                    return;
                }
                e.consume(); // captured: the moves and the release come here
                held = true;
                over = true;
                pointer = e.pointerId();
                repeats = 0;
                long start = e.time() > 0 ? e.time() : now;
                next = start + (long) (DELAY.get() * 1e9f);
                action.run();
            }
            case MOVE -> {
                if (held && e.pointerId() == pointer) {
                    over = inside(e.x(), e.y());
                }
            }
            case RELEASE -> {
                if (e.pointerId() == pointer) {
                    held = false;
                    pointer = -1;
                }
            }
            case DRAG_START -> {
                if (held) {
                    e.consume(); // a held stepper is not dragged
                }
            }
            case CLICK -> {
                e.consume();
                if (e.pointerId() < 0) {
                    action.run(); // ACCEPT from keys or a gamepad: once
                }
            }
            default -> {
                // nothing else
            }
        }
    }

    private boolean inside(float x, float y) {
        Layout l = ui.layout();
        int s = element.slot();
        return x >= l.x(s) && y >= l.y(s) && x < l.x(s) + l.width(s) && y < l.y(s) + l.height(s);
    }

    @Override
    public void frame(long nanos) {
        now = nanos;
        if (!held || nanos <= 0) {
            return;
        }
        if (!element.alive() || element.is(UiState.DISABLED)) {
            held = false;
            return;
        }
        long period = (long) (1e9f / Math.max(1, RATE.get()));
        if (!over) {
            if (nanos > next) {
                next = nanos; // paused off the element: repeats go on from where the pointer comes back
            }
            return;
        }
        if (nanos >= next) { // at most once a frame: a long frame does not flood the action
            action.run();
            repeats++;
            next = Math.max(next + period, nanos - period / 2);
        }
    }
}
