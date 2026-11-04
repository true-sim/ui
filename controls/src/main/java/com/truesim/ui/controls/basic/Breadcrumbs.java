package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import java.util.List;

/**
 * Breadcrumbs (type {@code breadcrumbs}): the path to where the player is, each step a {@code crumb} link button with a
 * {@code crumb-separator} icon between them; the last step is the current place ({@code :selected}). Clicking a step
 * tells the listener its index.
 */
public final class Breadcrumbs extends Control {

    /** Told which step was chosen. */
    @FunctionalInterface
    public interface Chosen {
        /** Step {@code index} (0: the first) was clicked. */
        void chosen(int index);
    }

    private Button[] crumbs = new Button[0];
    private Element[] separators = new Element[0];
    private Chosen chosen;

    /** Breadcrumbs named {@code name}. */
    @AllowAllocation("controls are built once")
    public Breadcrumbs(Controls controls, String name) {
        super(controls, "breadcrumbs", Role.GROUP, name);
    }

    /** Runs {@code listener} when a step is clicked; returns this. */
    public Breadcrumbs onChosen(Chosen listener) {
        chosen = listener;
        return this;
    }

    /** Shows the path {@code steps}; returns this. */
    @AllowAllocation("the path changes on navigation")
    public Breadcrumbs path(List<? extends CharSequence> steps) {
        for (Button b : crumbs) {
            ui.destroy(b.element());
        }
        for (Element e : separators) {
            ui.destroy(e);
        }
        int n = steps.size();
        crumbs = new Button[n];
        separators = new Element[Math.max(0, n - 1)];
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                separators[i - 1] = controls.icon("crumb-separator");
                root.add(separators[i - 1]);
            }
            Button b = new Button(controls, "crumb", Role.LINK, steps.get(i), null, null);
            int index = i;
            b.onClick(() -> choose(index));
            deepState(b.element(), UiState.SELECTED, i == n - 1);
            crumbs[i] = b;
            root.add(b.element());
        }
        return this;
    }

    private void choose(int index) {
        if (chosen != null) {
            chosen.chosen(index);
        }
    }

    /** How many steps show. */
    public int steps() {
        return crumbs.length;
    }

    /** Step {@code index}'s button. */
    public Button crumb(int index) {
        return crumbs[index];
    }
}
