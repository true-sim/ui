package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.VersionedInt;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.UiCommand;
import java.util.Arrays;

/**
 * A pop-up menu (type {@code menu} on the pop-up layer: a {@code menu-viewport} scrolling a {@code menu-items}
 * column, a minimal internal scroll for long menus) of {@link MenuItem}s: actions, check items, radio items,
 * {@code menu-separator}s and submenus. Used as a context menu ({@link #attachContext}), a pop-up menu
 * ({@link #open}, {@link #openAt}) and by {@link MenuBar}.
 *
 * <p><b>Pointer.</b> Hovering an item focuses it; resting on a submenu's item for {@code ui.menu.submenuDelay}
 * opens its submenu beside it, and resting on another item closes it, so a pointer crossing other items on its way
 * into an open submenu does not close it (hover intent). A click activates an item; a press outside the menus closes
 * them.
 *
 * <p><b>Keys and gamepad.</b> Up and down move (wrapping, skipping disabled items and separators), home and end
 * jump, right (or ACCEPT) opens a submenu, left or CANCEL closes one, CANCEL closes the menu and returns focus to
 * where it was; in a menu bar's menu, left and right move to the neighbouring menu. Shortcut labels come from
 * {@link Controls#glyphs()} and follow its changes while the menu shows.
 */
public final class Menu implements UiListener, Popup.Owner, Controls.Polled {

    private final Controls controls;
    private final Element element;
    private final Element viewport;
    private final Element items;
    private final Popup popup;
    private final ScrollBox scroll;
    private MenuItem[] entries = new MenuItem[8];
    private int count;
    Menu parent;
    MenuItem parentItem;
    MenuBar bar;
    private Menu openChild;
    private int pending = -1;
    private long pendingSince;
    Element restoreFocus;
    private int player;
    private long seenGlyphs = -1;
    private Element opener;

    /** A menu named {@code name}. */
    @AllowAllocation("controls are built once")
    public Menu(Controls controls, String name) {
        this.controls = controls;
        element = controls.ui().element("menu", null);
        element.semantics(Role.MENU, name);
        viewport = controls.ui().element("menu-viewport", null);
        items = controls.ui().element("menu-items", null);
        element.add(viewport);
        scroll = new ScrollBox(controls, viewport, items, false);
        popup = new Popup(controls, element, this);
        controls.ui().interaction().on(element, EventType.COMMAND.bit() | EventType.WHEEL.bit()
            | EventType.ENTER.bit(), this);
        controls.poll(new Poller());
    }

    /** Polls the open menu: hover intent, placement, shortcut labels. */
    private final class Poller extends Control {
        @AllowAllocation("controls are built once")
        Poller() {
            super(Menu.this.controls, "menu-poller", Role.NONE, null);
        }

        @Override
        public void poll(long nanos) {
            Menu.this.poll(nanos);
        }
    }

    /** The menu element (on the pop-up layer while open). */
    public Element element() {
        return element;
    }

    // ---------------------------------------------------------------- building

    @AllowAllocation("menus are built once")
    private MenuItem add(MenuItem item) {
        if (count == entries.length) {
            entries = Arrays.copyOf(entries, count * 2);
        }
        entries[count++] = item;
        items.add(item.element);
        if (item.kind != MenuItem.Kind.SEPARATOR) {
            controls.ui().interaction().on(item.element, EventType.CLICK.bit() | EventType.ENTER.bit(), this);
        }
        return item;
    }

    /** Adds an item running {@code action}; returns it. */
    @AllowAllocation("menus are built once")
    public MenuItem item(CharSequence text, Runnable action) {
        return add(new MenuItem(controls, this, MenuItem.Kind.ACTION, text, action, null, null, 0, null));
    }

    /** Adds an item running {@code action} with the shortcut of the engine's {@code shortcutAction}; returns it. */
    @AllowAllocation("menus are built once")
    public MenuItem item(CharSequence text, Object shortcutAction, Runnable action) {
        MenuItem item = item(text, action);
        item.shortcut(shortcutAction);
        return item;
    }

    /** Adds a check item bound to {@code model}; returns it. */
    @AllowAllocation("menus are built once")
    public MenuItem check(CharSequence text, VersionedBoolean model) {
        return add(new MenuItem(controls, this, MenuItem.Kind.CHECK, text, null, model, null, 0, null));
    }

    /** Adds a radio item setting {@code model} to {@code value} (checked while it holds it); returns it. */
    @AllowAllocation("menus are built once")
    public MenuItem radio(CharSequence text, VersionedInt model, int value) {
        return add(new MenuItem(controls, this, MenuItem.Kind.RADIO, text, null, null, model, value, null));
    }

    /** Adds a submenu item opening a new menu named {@code text}; returns the submenu to fill. */
    @AllowAllocation("menus are built once")
    public Menu submenu(CharSequence text) {
        Menu child = new Menu(controls, text.toString());
        MenuItem item = add(new MenuItem(controls, this, MenuItem.Kind.SUBMENU, text, null, null, null, 0, child));
        child.parent = this;
        child.parentItem = item;
        return child;
    }

    /** Adds a separator line. */
    @AllowAllocation("menus are built once")
    public Menu separator() {
        add(new MenuItem(controls, this, MenuItem.Kind.SEPARATOR, null, null, null, null, 0, null));
        return this;
    }

    /** Removes every item (their elements are destroyed); returns this. */
    public Menu clear() {
        close();
        for (int i = 0; i < count; i++) {
            controls.ui().destroy(entries[i].element);
            entries[i] = null;
        }
        count = 0;
        return this;
    }

    /** Item {@code index} (separators count). */
    public MenuItem item(int index) {
        return entries[index];
    }

    /** How many items (with separators). */
    public int count() {
        return count;
    }

    /** The open submenu, or null. */
    public Menu openSubmenu() {
        return openChild;
    }

    /** Whether the menu shows. */
    public boolean isOpen() {
        return popup.isOpen();
    }

    // ---------------------------------------------------------------- opening and closing

    /** Opens the menu below ({@code below}) or beside {@code anchor}, focusing its first item. */
    public void open(Element anchor, boolean below) {
        opener = anchor;
        popup.open(anchor, below);
        opened();
    }

    /** Opens the menu at {@code x, y} (UI pixels: a context menu at the pointer), focusing its first item. */
    public void openAt(float x, float y) {
        opener = null;
        popup.openAt(x, y);
        opened();
    }

    private void opened() {
        if (parent == null && restoreFocus == null) {
            restoreFocus = controls.ui().interaction().focused(player);
        }
        scroll.reset();
        refreshGlyphs(true);
        pending = -1;
        focus(next(-1, 1));
    }

    /** Opens the menu as the context menu of {@code target}: on its CONTEXT event, at the pointer or beside it. */
    @AllowAllocation("menus are built once")
    public Menu attachContext(Element target) {
        controls.ui().interaction().on(target, EventType.CONTEXT, event -> {
            event.consume();
            player = event.player();
            if (event.pointerId() < 0) {
                open(target, true);
            } else {
                openAt(event.x(), event.y());
            }
        });
        return this;
    }

    /** Closes this menu and its open submenus (not its parents). */
    public void close() {
        if (openChild != null) {
            openChild.close();
        }
        if (!popup.isOpen()) {
            return;
        }
        popup.close();
        pending = -1;
        if (parent != null) {
            if (parent.openChild == this) {
                parent.openChild = null;
            }
            parentItem.element.state(UiState.EXPANDED, false);
        }
    }

    /** Closes the whole chain from the top menu, returning focus to where it was before it opened. */
    public void closeAll() {
        Menu top = this;
        while (top.parent != null) {
            top = top.parent;
        }
        top.close();
        if (top.bar != null) {
            top.bar.closed(top);
        }
        Element restore = top.restoreFocus;
        top.restoreFocus = null;
        if (restore != null && restore.alive()) {
            controls.ui().interaction().focus(restore, top.player);
        }
    }

    private void openChild(MenuItem item, boolean focusFirst) {
        Menu child = item.submenu;
        if (openChild == child && child.isOpen()) {
            if (focusFirst) {
                child.focus(child.next(-1, 1));
            }
            return;
        }
        if (openChild != null) {
            openChild.close();
        }
        openChild = child;
        child.player = player;
        item.element.state(UiState.EXPANDED, true);
        child.popup.open(item.element, false);
        child.scroll.reset();
        child.refreshGlyphs(true);
        child.pending = -1;
        if (focusFirst) {
            child.focus(child.next(-1, 1));
        }
    }

    // ---------------------------------------------------------------- focus

    private int indexOf(int slot) {
        for (int i = 0; i < count; i++) {
            if (entries[i].element.slot() == slot) {
                return i;
            }
        }
        return -1;
    }

    /** The next focusable item after {@code from} in direction {@code dir}, wrapping; -1 if none. */
    private int next(int from, int dir) {
        for (int k = 1; k <= count; k++) {
            int i = Math.floorMod(from + dir * k, count);
            if (entries[i].focusable()) {
                return i;
            }
        }
        return -1;
    }

    private void focus(int index) {
        if (index >= 0) {
            controls.focusSoon(entries[index].element, player);
            scroll.reveal(entries[index].element);
        }
    }

    private int focusedIndex() {
        Element f = controls.ui().interaction().focused(player);
        return f == null ? -1 : indexOf(f.slot());
    }

    // ---------------------------------------------------------------- events

    @Override
    public void handle(UiEvent event) {
        int slot = event.currentSlot();
        if (slot == element.slot()) {
            switch (event.type()) {
                case ENTER -> {
                    if (parent != null) {
                        parent.pending = -1; // the pointer reached this submenu: keep it
                    }
                }
                case WHEEL -> {
                    if (scroll.wheel(event.scrollY())) {
                        event.consume();
                    }
                }
                case COMMAND -> command(event);
                default -> {
                    // nothing else
                }
            }
            return;
        }
        int i = indexOf(slot);
        if (i < 0) {
            return;
        }
        MenuItem item = entries[i];
        if (event.type() == EventType.ENTER) {
            if (item.focusable()) {
                controls.ui().interaction().focus(item.element, event.player());
            }
            pending = i;
            pendingSince = event.time();
            if (parent != null) {
                parent.pending = -1;
            }
        } else if (event.type() == EventType.CLICK) {
            event.consume();
            player = event.player();
            activate(item);
        }
    }

    private void activate(MenuItem item) {
        if (!item.focusable()) {
            return;
        }
        if (item.kind == MenuItem.Kind.SUBMENU) {
            openChild(item, true);
            return;
        }
        item.activate();
        closeAll();
    }

    private void command(UiEvent event) {
        player = event.player();
        UiCommand c = event.command();
        int at = focusedIndex();
        switch (c) {
            case DOWN -> focus(next(at, 1));
            case UP -> focus(next(at < 0 ? 0 : at, -1));
            case HOME -> focus(next(-1, 1));
            case END -> focus(next(0, -1));
            case RIGHT -> {
                if (at >= 0 && entries[at].kind == MenuItem.Kind.SUBMENU && entries[at].focusable()) {
                    openChild(entries[at], true);
                } else if (topBar() != null) {
                    topBar().step(topMenu(), 1);
                } else {
                    return;
                }
            }
            case LEFT -> {
                if (parent != null) {
                    MenuItem back = parentItem;
                    close();
                    parent.focus(parent.indexOf(back.element.slot()));
                } else if (bar != null) {
                    bar.step(this, -1);
                } else {
                    return;
                }
            }
            case CANCEL -> {
                if (parent != null) {
                    MenuItem back = parentItem;
                    close();
                    parent.focus(parent.indexOf(back.element.slot()));
                } else {
                    closeAll();
                }
            }
            default -> {
                return;
            }
        }
        event.consume();
    }

    private Menu topMenu() {
        Menu top = this;
        while (top.parent != null) {
            top = top.parent;
        }
        return top;
    }

    private MenuBar topBar() {
        return topMenu().bar;
    }

    @Override
    public boolean owns(int slot) {
        if (openChild != null && (openChild.popup.contains(slot) || openChild.owns(slot))) {
            return true;
        }
        if (parent != null) {
            return parent.popup.contains(slot) || parent.ownsUpward(slot);
        }
        return opener != null && controls.inside(opener.slot(), slot) || bar != null && bar.owns(slot);
    }

    private boolean ownsUpward(int slot) {
        if (parent != null) {
            return parent.popup.contains(slot) || parent.ownsUpward(slot);
        }
        return opener != null && controls.inside(opener.slot(), slot) || bar != null && bar.owns(slot);
    }

    @Override
    public void dismissed() {
        if (openChild != null) {
            openChild.close();
        }
        if (parent != null) {
            parentItem.element.state(UiState.EXPANDED, false);
            if (parent.openChild == this) {
                parent.openChild = null;
            }
            parent.dismissedFromChild();
        } else {
            restoreFocus = null;
            if (bar != null) {
                bar.closed(this);
            }
        }
    }

    private void dismissedFromChild() {
        if (popup.isOpen()) {
            close();
            dismissed();
        }
    }

    // ---------------------------------------------------------------- the frame

    /** Hover intent, placement and shortcut labels while open. */
    @Override
    public void poll(long nanos) {
        if (!popup.isOpen()) {
            return;
        }
        popup.place();
        refreshGlyphs(false);
        for (int i = 0; i < count; i++) {
            entries[i].poll();
        }
        if (pending < 0) {
            return;
        }
        long delay = (long) (Controls.SUBMENU_DELAY.get() * 1e9f);
        if (nanos > 0 && nanos - pendingSince < delay) {
            return;
        }
        MenuItem item = entries[pending];
        pending = -1;
        if (item.kind == MenuItem.Kind.SUBMENU && item.focusable()) {
            openChild(item, false);
        } else if (openChild != null) {
            openChild.close();
        }
    }

    private void refreshGlyphs(boolean force) {
        ShortcutGlyphs glyphs = controls.glyphs();
        if (!force && glyphs.version() == seenGlyphs) {
            return;
        }
        seenGlyphs = glyphs.version();
        for (int i = 0; i < count; i++) {
            entries[i].glyph(glyphs);
        }
    }
}
