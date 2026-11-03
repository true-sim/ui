package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.Watch;
import com.truesim.ui.controls.anim.Animator;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Transition;
import java.util.Arrays;

/**
 * A carousel or pager (type {@code carousel}): a {@code carousel-viewport} (the theme clips it and lays it out as
 * anchors) holding the {@code carousel-track}, a row of {@code carousel-page}s each as wide as the viewport; previous
 * and next buttons (ids {@code previous}, {@code next}) and a {@code carousel-dots} row of {@code carousel-dot}
 * indicators ({@code :selected} for the page showing). Bound two-way to a {@link VersionedInt} page.
 *
 * <p>The track is anchored at minus the page position (in pages), so it needs no measuring; turning the page animates
 * that position with the track's {@code transition} of {@code offsets} (instant without one), reversing smoothly when
 * interrupted. A drag or swipe on the viewport moves the pages with the pointer and turns the page when it covered
 * {@code ui.carousel.swipeThreshold} of it or was flicked; left and right (or the buttons and dots) turn it too, home
 * and end jump. The carousel takes focus.
 */
public final class Carousel extends Control implements Animator.Animated {

    private final Element viewport;
    private final Element track;
    private final Element dots;
    private final Button previous;
    private final Button next;
    private Element[] pages = new Element[4];
    private Button[] dotButtons = new Button[4];
    private int count;
    private VersionedInt model;
    private Watch watch;
    private float position;
    private float dragPages;
    private boolean dragging;
    private float lastDelta;

    /** A carousel named {@code name}, bound to {@code page}. */
    @AllowAllocation("controls are built once")
    public Carousel(Controls controls, String name, VersionedInt page) {
        super(controls, "carousel", Role.GROUP, name);
        viewport = part("carousel-viewport");
        viewport.semantics(Role.SCROLL_VIEW, null);
        track = part("carousel-track");
        viewport.add(track);
        previous = new Button(controls, "previous", null, "Previous page");
        previous.element().id("previous");
        next = new Button(controls, "next", null, "Next page");
        next.element().id("next");
        dots = part("carousel-dots");
        root.add(previous.element()).add(viewport).add(next.element()).add(dots);
        previous.onClick(() -> turn(model.get() - 1));
        next.onClick(() -> turn(model.get() + 1));
        focusable(root);
        listen(root, EventType.COMMAND.bit());
        listen(viewport, EventType.DRAG_START.bit() | EventType.DRAG.bit() | EventType.DRAG_END.bit());
        ui.interaction().pickable(viewport, com.truesim.ui.core.event.PointerEvents.ALL);
        controls.poll(this);
        model = page;
        watch = page.watch();
        place(0);
    }

    /** Adds a page showing {@code content}; returns this. */
    @AllowAllocation("controls are built once")
    public Carousel add(Element content) {
        Element p = part("carousel-page");
        p.add(content);
        track.add(p);
        if (count == pages.length) {
            pages = Arrays.copyOf(pages, count * 2);
            dotButtons = Arrays.copyOf(dotButtons, count * 2);
        }
        int index = count;
        Button dot = new Button(controls, "dot", null, "Page " + (index + 1));
        dot.element().style("carousel-dot");
        dot.onClick(() -> turn(index));
        dots.add(dot.element());
        pages[count] = p;
        dotButtons[count] = dot;
        count++;
        show(false);
        return this;
    }

    /** The model: the page index. */
    public VersionedInt model() {
        return model;
    }

    /** The page index. */
    public int page() {
        return model.get();
    }

    /** How many pages. */
    public int count() {
        return count;
    }

    /** The position shown now, in pages (fractional while turning or dragged). */
    public float position() {
        return position;
    }

    /** Turns to page {@code index} (clamped), animated. */
    public void turn(int index) {
        if (count == 0) {
            return;
        }
        model.set(Math.max(0, Math.min(count - 1, index)));
        show(true);
    }

    /** Page {@code index}'s element. */
    public Element pageElement(int index) {
        return pages[index];
    }

    /** Indicator {@code index}. */
    public Button dot(int index) {
        return dotButtons[index];
    }

    /** The previous page button. */
    public Button previousButton() {
        return previous;
    }

    /** The next page button. */
    public Button nextButton() {
        return next;
    }

    /** The viewport. */
    public Element viewport() {
        return viewport;
    }

    private void show(boolean animate) {
        watch.update();
        int p = model.get();
        for (int i = 0; i < count; i++) {
            dotButtons[i].element().state(UiState.SELECTED, i == p);
        }
        previous.element().state(UiState.DISABLED, p <= 0);
        next.element().state(UiState.DISABLED, p >= count - 1);
        Transition t = Animator.find(ui.styles().styleOf(track.slot()).get(Styles.TRANSITION), "offsets");
        if (animate && t != null && t.duration() > 0) {
            ui.animator().animate(this, 0, 0, position, p, t.duration(), t.easing(), null);
        } else {
            ui.animator().cancel(this, 0, 0);
            place(p);
        }
    }

    private void place(float pos) {
        position = pos;
        float shown = pos + dragPages;
        ui.visuals().anchors(track.slot(), -shown, 0, Math.max(1, count) - shown, 1);
    }

    @Override
    public void animate(int key, int channel, float value) {
        place(value);
    }

    @Override
    public void poll(long nanos) {
        if (watch.isChanged()) {
            show(true);
        }
    }

    @Override
    public void handle(UiEvent event) {
        int slot = event.currentSlot();
        if (slot == viewport.slot()) {
            float width = ui.layout().width(viewport.slot());
            switch (event.type()) {
                case DRAG_START -> {
                    dragging = true;
                    ui.animator().cancel(this, 0, 0);
                    event.consume();
                }
                case DRAG -> {
                    if (dragging && width > 0) {
                        event.consume();
                        lastDelta = event.deltaX();
                        dragPages = -(event.x() - event.pressX()) / width;
                        place(position);
                    }
                }
                case DRAG_END -> {
                    if (dragging) {
                        dragging = false;
                        float moved = dragPages;
                        dragPages = 0;
                        position += moved;
                        int p = model.get();
                        float threshold = Controls.SWIPE_THRESHOLD.get();
                        boolean flick = Math.abs(lastDelta) > 8;
                        if (moved > threshold || flick && lastDelta < 0) {
                            p++;
                        } else if (moved < -threshold || flick && lastDelta > 0) {
                            p--;
                        }
                        place(position);
                        turn(p);
                    }
                }
                default -> {
                    // nothing else
                }
            }
            return;
        }
        if (event.type() != EventType.COMMAND || slot != root.slot()) {
            return;
        }
        UiCommand c = event.command();
        int p = model.get();
        switch (c) {
            case LEFT -> turn(p - 1);
            case RIGHT -> turn(p + 1);
            case HOME -> turn(0);
            case END -> turn(count - 1);
            default -> {
                return;
            }
        }
        event.consume();
    }
}
