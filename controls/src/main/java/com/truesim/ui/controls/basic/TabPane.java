package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.style.Facts;
import java.util.Arrays;

/**
 * A tabbed pane (type {@code tab-pane}, facts {@code side=top|bottom|left|right} and
 * {@code overflow=scroll|wrap|menu}), bound two-way to a {@link VersionedInt} (the selected tab). Parts: a
 * {@code tab-header} holding the {@code tab-strip} (a scrolling viewport of the {@code tab-row}) and the overflow
 * button (id {@code tab-overflow}, icon {@code more}: a menu of every tab), then the {@code tab-content} showing the
 * selected tab's content (the others are out of the tree). Each tab is a {@code tab} button ({@code :selected}), with a
 * {@code tab-close} button when closable.
 *
 * <p>How the strip handles more tabs than fit is the theme's {@code tab-overflow}: {@code scroll} (one row scrolled by
 * the wheel and kept on the selected tab), {@code wrap} (more rows: the theme wraps the row) or {@code menu} (one row
 * clipped; the overflow menu reaches the rest). Arrow keys along the strip select the neighbouring tab, home and end
 * the first and last. Closable tabs ask {@link #onClose} (by default they are removed); with {@link #reorderable}
 * tabs are dragged along the strip to reorder them.
 */
public final class TabPane extends Control {

    /** Where the tab strip is. */
    public enum Side {
        TOP, BOTTOM, LEFT, RIGHT
    }

    /** Decides about closing a tab. */
    @FunctionalInterface
    public interface CloseListener {
        /** The player asked to close tab {@code index}: return true to remove it. */
        boolean closing(int index);
    }

    /** One tab: its button, optional close button and content. */
    public final class Tab {
        final Button button;
        final Button close;
        final Element content;

        @AllowAllocation("tabs are made on edit")
        Tab(CharSequence title, Element content, boolean closable) {
            this.content = content;
            button = new Button(controls, "tab", Role.TAB, title, null, null);
            if (closable) {
                close = new Button(controls, "close", null, "Close " + title);
                close.element().style("tab-close");
                button.element().add(close.element());
                close.onClick(() -> askClose(this));
            } else {
                close = null;
            }
            button.onClick(() -> select(indexOf(this)));
        }

        /** The tab's button. */
        public Button button() {
            return button;
        }

        /** The close button, or null. */
        public Button closeButton() {
            return close;
        }

        /** The content. */
        public Element content() {
            return content;
        }
    }

    private final Side side;
    private final Element header;
    private final Element strip;
    private final Element row;
    private final Element content;
    private final ScrollBox scroll;
    private final Button overflow;
    private final Menu menu;
    private Tab[] tabs = new Tab[4];
    private int count;
    private VersionedInt model;
    private Watch watch;
    private boolean reorderable;
    private CloseListener closeListener;
    private BasicStyles.TabOverflow mode;
    private Tab dragged;
    private int shownContent = -1;

    /** A tab pane named {@code name} with its strip on {@code side}, bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public TabPane(Controls controls, String name, Side side, VersionedInt model) {
        super(controls, "tab-pane", Role.GROUP, name);
        this.side = side;
        header = part("tab-header");
        strip = part("tab-strip");
        row = part("tab-row");
        row.semantics(Role.TAB_LIST, name);
        strip.semantics(Role.SCROLL_VIEW, null);
        scroll = new ScrollBox(controls, strip, row, side == Side.TOP || side == Side.BOTTOM);
        overflow = new Button(controls, "more", null, "All tabs");
        overflow.element().id("tab-overflow");
        menu = new Menu(controls, "All tabs");
        overflow.onClick(this::toggleMenu);
        header.add(strip).add(overflow.element());
        content = part("tab-content");
        content.semantics(Role.TAB_PANEL, name);
        root.add(header).add(content);
        listen(row, EventType.COMMAND.bit() | EventType.DRAG_START.bit() | EventType.DRAG.bit()
            | EventType.DRAG_END.bit());
        listen(strip, EventType.WHEEL.bit());
        facts(BasicStyles.TAB_OVERFLOW.defaultValue());
        controls.poll(this);
        bind(model);
    }

    @AllowAllocation("facts change with the theme, rarely")
    private void facts(BasicStyles.TabOverflow value) {
        mode = value;
        ui.styles().facts(root.slot(), Facts.of("side", side.name().toLowerCase(java.util.Locale.ROOT),
            "overflow", value.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private boolean horizontal() {
        return side == Side.TOP || side == Side.BOTTOM;
    }

    /** Binds the pane to {@code value}; returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public TabPane bind(VersionedInt value) {
        model = value;
        watch = value.watch();
        show();
        return this;
    }

    /** Lets tabs be dragged along the strip to reorder them; returns this. */
    public TabPane reorderable(boolean on) {
        reorderable = on;
        return this;
    }

    /** Asks {@code listener} before closing a tab; returns this. */
    public TabPane onClose(CloseListener listener) {
        closeListener = listener;
        return this;
    }

    /** Adds a tab titled {@code title} showing {@code tabContent}; returns it. */
    @AllowAllocation("tabs are made on edit")
    public Tab add(CharSequence title, Element tabContent, boolean closable) {
        Tab t = new Tab(title, tabContent, closable);
        if (count == tabs.length) {
            tabs = Arrays.copyOf(tabs, count * 2);
        }
        tabs[count++] = t;
        listen(t.button.element(), EventType.DRAG_START.bit());
        row.add(t.button.element());
        if (model.get() < 0) {
            model.set(0);
        }
        show();
        return t;
    }

    /** Removes tab {@code index} (its content is detached, not destroyed); the selection follows. */
    public void remove(int index) {
        Tab t = tabs[index];
        if (t.content.parent() != null) {
            t.content.detach();
        }
        ui.destroy(t.button.element());
        System.arraycopy(tabs, index + 1, tabs, index, count - index - 1);
        tabs[--count] = null;
        int s = model.get();
        if (s > index || s == index && s == count) {
            model.set(s - 1);
        }
        shownContent = -1;
        show();
    }

    /** Selects tab {@code index}. */
    public void select(int index) {
        if (index < 0 || index >= count) {
            return;
        }
        model.set(index);
        show();
    }

    /** The selected index (-1: none). */
    public int selected() {
        return model.get();
    }

    /** The model. */
    public VersionedInt model() {
        return model;
    }

    /** How many tabs. */
    public int count() {
        return count;
    }

    /** Tab {@code index}. */
    public Tab tab(int index) {
        return tabs[index];
    }

    /** The index of {@code tab}, or -1. */
    public int indexOf(Tab tab) {
        for (int i = 0; i < count; i++) {
            if (tabs[i] == tab) {
                return i;
            }
        }
        return -1;
    }

    /** The overflow button. */
    public Button overflowButton() {
        return overflow;
    }

    /** The overflow menu. */
    public Menu overflowMenu() {
        return menu;
    }

    /** The scroll position of the strip, in panel units. */
    public float stripScroll() {
        return scroll.scroll();
    }

    private void askClose(Tab t) {
        int i = indexOf(t);
        if (i >= 0 && (closeListener == null || closeListener.closing(i))) {
            remove(i);
        }
    }

    @AllowAllocation("the overflow menu is filled when it opens")
    private void toggleMenu() {
        if (menu.isOpen()) {
            menu.closeAll();
            return;
        }
        menu.clear();
        for (int i = 0; i < count; i++) {
            menu.radio(ui.text(tabs[i].button.labelElement()).toString(), model, i);
        }
        menu.open(overflow.element(), true);
    }

    private void show() {
        watch.update();
        int s = model.get();
        for (int i = 0; i < count; i++) {
            deepState(tabs[i].button.element(), UiState.SELECTED, i == s);
        }
        if (s != shownContent) {
            for (int i = 0; i < count; i++) {
                Element c = tabs[i].content;
                if (i != s && c.parent() == content) {
                    c.detach();
                }
            }
            if (s >= 0 && s < count && tabs[s].content.parent() != content) {
                content.add(tabs[s].content);
            }
            shownContent = s >= 0 && s < count ? s : -1;
        }
        if (s >= 0 && s < count) {
            scroll.reveal(tabs[s].button.element());
        }
    }

    @Override
    public void poll(long nanos) {
        if (watch.isChanged()) {
            show();
        }
        BasicStyles.TabOverflow m = ui.styles().styleOf(root.slot()).get(BasicStyles.TAB_OVERFLOW);
        if (m != mode) {
            facts(m);
        }
        boolean needed = mode != BasicStyles.TabOverflow.WRAP && scroll.range() > 0;
        if (needed != (overflow.element().parent() != null)) {
            if (needed) {
                header.add(overflow.element());
            } else {
                overflow.element().detach();
            }
        }
    }

    private int tabAt(int slot) {
        for (int i = 0; i < count; i++) {
            if (tabs[i].button.element().slot() == slot) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void handle(UiEvent event) {
        int slot = event.currentSlot();
        if (slot == strip.slot()) {
            if (event.type() == EventType.WHEEL && mode == BasicStyles.TabOverflow.SCROLL) {
                float d = event.scrollY() != 0 ? event.scrollY() : event.scrollX();
                if (scroll.wheel(d)) {
                    event.consume();
                }
            }
            return;
        }
        int t = tabAt(slot);
        if (t >= 0) {
            if (event.type() == EventType.DRAG_START && reorderable) {
                dragged = tabs[t];
                dragged.button.element().state(UiState.DRAGGING, true);
            }
            return;
        }
        if (slot != row.slot()) {
            return;
        }
        switch (event.type()) {
            case DRAG -> {
                if (dragged != null) {
                    event.consume();
                    reorder(horizontal() ? event.x() : event.y());
                }
            }
            case DRAG_END -> {
                if (dragged != null) {
                    dragged.button.element().state(UiState.DRAGGING, false);
                    dragged = null;
                }
            }
            case COMMAND -> command(event);
            default -> {
                // nothing else
            }
        }
    }

    /** Moves the dragged tab to where the pointer is along the strip. */
    private void reorder(float at) {
        Layout l = ui.layout();
        int from = indexOf(dragged);
        int to = 0;
        for (int i = 0; i < count; i++) {
            if (i == from) {
                continue;
            }
            int s = tabs[i].button.element().slot();
            float centre = horizontal() ? l.x(s) + l.width(s) * 0.5f : l.y(s) + l.height(s) * 0.5f;
            if (at > centre) {
                to++;
            }
        }
        if (to == from) {
            return;
        }
        int selected = model.get();
        Tab selectedTab = selected >= 0 && selected < count ? tabs[selected] : null;
        Tab moving = tabs[from];
        if (to > from) {
            System.arraycopy(tabs, from + 1, tabs, from, to - from);
        } else {
            System.arraycopy(tabs, to, tabs, to + 1, from - to);
        }
        tabs[to] = moving;
        for (int i = 0; i < count; i++) {
            tabs[i].button.element().detach();
        }
        for (int i = 0; i < count; i++) {
            row.add(tabs[i].button.element());
        }
        if (selectedTab != null) {
            model.set(indexOf(selectedTab));
            shownContent = model.get();
        }
        show();
    }

    private void command(UiEvent event) {
        Element f = interaction().focused(event.player());
        int at = f == null ? -1 : tabAt(f.slot());
        if (at < 0 || count == 0) {
            return;
        }
        UiCommand c = event.command();
        boolean h = horizontal();
        int to;
        if (c == (h ? UiCommand.LEFT : UiCommand.UP)) {
            to = Math.floorMod(at - 1, count);
        } else if (c == (h ? UiCommand.RIGHT : UiCommand.DOWN)) {
            to = Math.floorMod(at + 1, count);
        } else if (c == UiCommand.HOME) {
            to = 0;
        } else if (c == UiCommand.END) {
            to = count - 1;
        } else {
            return;
        }
        event.consume();
        select(to);
        interaction().focus(tabs[to].button.element(), event.player());
    }
}
