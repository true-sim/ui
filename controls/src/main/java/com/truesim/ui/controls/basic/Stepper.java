package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import java.util.Arrays;

/**
 * A stepper or wizard (type {@code stepper}): a {@code stepper-steps} row of {@code step} buttons (a number label of
 * style name {@code step-number} and the title; {@code :selected} for the current step, {@code :checked} for those
 * done), the current step's page in {@code stepper-content}, and a {@code stepper-buttons} row with back (id
 * {@code back}) and next (id {@code next}; it reads the finish text on the last step). Bound two-way to a
 * {@link VersionedInt} step. A {@link Guard} may keep the player on a step (an invalid form); steps already reached
 * can be clicked to go back.
 */
public final class Stepper extends Control {

    /** Decides whether the player may leave a step forwards. */
    @FunctionalInterface
    public interface Guard {
        /** Whether step {@code index} is complete. */
        boolean complete(int index);
    }

    private static final String[] NUMBERS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"};

    private final Element steps;
    private final Element content;
    private final Button back;
    private final Button next;
    private final CharSequence nextText;
    private final CharSequence finishText;
    private Button[] stepButtons = new Button[4];
    private Element[] pages = new Element[4];
    private int count;
    private int reached;
    private VersionedInt model;
    private Watch watch;
    private Guard guard;
    private Runnable finish;

    /** A stepper named {@code name} with button texts {@code backText}, {@code nextText}, {@code finishText}. */
    @AllowAllocation("controls are built once")
    public Stepper(Controls controls, String name, CharSequence backText, CharSequence nextText,
        CharSequence finishText, VersionedInt step) {
        super(controls, "stepper", Role.GROUP, name);
        this.nextText = nextText;
        this.finishText = finishText;
        steps = part("stepper-steps");
        steps.semantics(Role.TAB_LIST, name);
        content = part("stepper-content");
        Element buttons = part("stepper-buttons");
        back = new Button(controls, backText);
        back.element().id("back");
        next = new Button(controls, nextText);
        next.element().id("next");
        buttons.add(back.element()).add(next.element());
        root.add(steps).add(content).add(buttons);
        back.onClick(() -> go(model.get() - 1));
        next.onClick(this::forward);
        controls.poll(this);
        model = step;
        watch = step.watch();
    }

    /** Adds a step titled {@code title} showing {@code page}; returns this. */
    @AllowAllocation("controls are built once")
    public Stepper add(CharSequence title, Element page) {
        if (count == stepButtons.length) {
            stepButtons = Arrays.copyOf(stepButtons, count * 2);
            pages = Arrays.copyOf(pages, count * 2);
        }
        int index = count;
        Button b = new Button(controls, "step", Role.TAB, title, null, null);
        Element number = ui.label("step-number", index < NUMBERS.length ? NUMBERS[index] : Integer.toString(index + 1));
        number.semantics(Role.NONE, null);
        b.labelElement().detach();
        b.element().add(number).add(b.labelElement());
        b.onClick(() -> {
            if (index <= reached) {
                go(index);
            }
        });
        stepButtons[count] = b;
        pages[count] = page;
        count++;
        steps.add(b.element());
        show();
        return this;
    }

    /** Keeps the player on incomplete steps; returns this. */
    public Stepper guard(Guard value) {
        guard = value;
        return this;
    }

    /** Runs {@code action} when next is pressed on the last step; returns this. */
    public Stepper onFinish(Runnable action) {
        finish = action;
        return this;
    }

    /** The model. */
    public VersionedInt model() {
        return model;
    }

    /** The current step. */
    public int step() {
        return model.get();
    }

    /** The furthest step reached. */
    public int reached() {
        return reached;
    }

    /** Step {@code index}'s button. */
    public Button stepButton(int index) {
        return stepButtons[index];
    }

    /** The back button. */
    public Button backButton() {
        return back;
    }

    /** The next button. */
    public Button nextButton() {
        return next;
    }

    /** Goes forward if the guard allows (finishing on the last step). */
    public void forward() {
        int s = model.get();
        if (guard != null && !guard.complete(s)) {
            return;
        }
        if (s >= count - 1) {
            if (finish != null) {
                finish.run();
            }
            return;
        }
        go(s + 1);
    }

    /** Goes to step {@code index} (clamped). */
    public void go(int index) {
        if (count == 0) {
            return;
        }
        model.set(Math.max(0, Math.min(count - 1, index)));
        show();
    }

    private void show() {
        watch.update();
        int s = Math.max(0, Math.min(count - 1, model.get()));
        reached = Math.max(reached, s);
        for (int i = 0; i < count; i++) {
            Element e = stepButtons[i].element();
            deepState(e, UiState.SELECTED, i == s);
            deepState(e, UiState.CHECKED, i < s || i < reached && i != s);
            e.state(UiState.DISABLED, i > reached);
            if (i != s && pages[i].parent() == content) {
                pages[i].detach();
            }
        }
        if (count > 0 && pages[s].parent() != content) {
            content.add(pages[s]);
        }
        back.element().state(UiState.DISABLED, s <= 0);
        next.text(s >= count - 1 ? finishText : nextText);
    }

    @Override
    public void poll(long nanos) {
        if (watch.isChanged()) {
            show();
        }
    }
}
