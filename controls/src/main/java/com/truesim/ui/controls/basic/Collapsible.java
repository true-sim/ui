package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.Watch;
import com.truesim.ui.controls.anim.Animator;
import com.truesim.ui.controls.anim.Visuals;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.value.Transition;

/**
 * A collapsing section (type {@code collapsible}, or {@code expander}: Lemur's roll-up panel): a header button
 * ({@code collapsible-header}: a {@code collapsible-arrow} icon and the title) over a {@code collapsible-body} holding
 * the content, bound two-way to a {@link VersionedBoolean} (expanded; {@code :expanded} on the section and its
 * header). Opening and closing animate the body's height between 0 and the content's height with the body's
 * {@code transition} of {@code height} (instant without one); an interrupted animation reverses from where it is.
 * A closed section's content is out of the tree, so focus cannot reach it; an open one follows its content's size.
 * Sections of an {@link Accordion} close each other.
 */
public final class Collapsible extends Control implements Animator.Listener {

    private static final int IDLE = 0;
    private static final int MEASURE = 1;
    private static final int ANIMATING = 2;

    private final Button header;
    private final Element body;
    private final Element content;
    private VersionedBoolean model;
    private Watch watch;
    private boolean shownExpanded;
    private int phase = IDLE;
    Accordion accordion;

    /** A section of {@code type} titled {@code title} showing {@code content}, bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public Collapsible(Controls controls, String type, CharSequence title, Element content, VersionedBoolean model) {
        super(controls, type, Role.GROUP, title.toString());
        header = new Button(controls, "collapsible-header", Role.BUTTON, title, "collapsible-arrow", null);
        body = part("collapsible-body");
        this.content = content;
        root.add(header.element()).add(body);
        header.onClick(this::toggle);
        controls.poll(this);
        this.model = model;
        watch = model.watch();
        watch.update();
        shownExpanded = model.get();
        if (shownExpanded) {
            body.add(content);
        } else {
            ui.visuals().height(body.slot(), 0, Unit.PX);
        }
        states();
    }

    /** A collapsible titled {@code title} (type {@code collapsible}). */
    @AllowAllocation("controls are built once")
    public Collapsible(Controls controls, CharSequence title, Element content, VersionedBoolean model) {
        this(controls, "collapsible", title, content, model);
    }

    /** The model: expanded. */
    public VersionedBoolean model() {
        return model;
    }

    /** Whether it is (or is becoming) expanded. */
    public boolean expanded() {
        return model.get();
    }

    /** Expands or collapses it (animated). */
    public void expanded(boolean on) {
        model.set(on);
        apply();
    }

    /** Flips it. */
    public void toggle() {
        expanded(!model.get());
    }

    /** The header button. */
    public Button header() {
        return header;
    }

    /** The body element (its height animates). */
    public Element body() {
        return body;
    }

    /** The content element. */
    public Element content() {
        return content;
    }

    /** Whether the height animates now (or waits a frame to measure the content). */
    public boolean animating() {
        return phase != IDLE;
    }

    private void states() {
        root.state(UiState.EXPANDED, shownExpanded);
        deepState(header.element(), UiState.EXPANDED, shownExpanded);
    }

    private void apply() {
        watch.update();
        boolean on = model.get();
        if (on == shownExpanded) {
            return;
        }
        shownExpanded = on;
        states();
        if (on && accordion != null) {
            accordion.expanded(this);
        }
        Visuals v = ui.visuals();
        int b = body.slot();
        if (on) {
            if (content.parent() != body) {
                body.add(content);
            }
            if (!v.has(b, Visuals.HEIGHT)) {
                v.height(b, controls.units(ui.layout().height(b)), Unit.PX);
            }
            phase = MEASURE; // the content's height is known after its layout this frame
        } else {
            float from = v.has(b, Visuals.HEIGHT) ? v.height(b) : controls.units(ui.layout().height(b));
            animate(from, 0);
        }
    }

    private void animate(float from, float to) {
        Transition t = Animator.find(ui.styles().styleOf(body.slot()).get(
            com.truesim.ui.style.Styles.TRANSITION), "height");
        phase = ANIMATING;
        ui.animator().animate(body.slot(), Animator.HEIGHT, from, to, t == null ? 0 : t.duration(),
            t == null ? null : t.easing(), true, this);
    }

    @Override
    public void finished(int key, int channel, boolean completed) {
        if (!completed) {
            return;
        }
        phase = IDLE;
        if (shownExpanded) {
            ui.visuals().clear(body.slot(), Visuals.HEIGHT); // follow the content from now on
        } else if (content.parent() == body) {
            content.detach();
        }
    }

    @Override
    public void poll(long nanos) {
        if (watch.isChanged()) {
            apply();
        }
        if (phase == MEASURE) {
            Layout l = ui.layout();
            int c = content.slot();
            if (l.shown(c)) {
                Visuals v = ui.visuals();
                animate(v.height(body.slot()), controls.units(l.height(c)));
            }
        }
    }
}
