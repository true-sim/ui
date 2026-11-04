package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.UiCommand;
import java.util.Arrays;

/**
 * A menu bar (type {@code menu-bar}) of {@code menu-title} buttons, each opening its {@link Menu} below it
 * ({@code :expanded} while open). While one is open, moving the pointer onto another title opens that one; left and
 * right in an open menu move to the neighbouring menu; down or ACCEPT on a title opens its menu, left and right move
 * between titles. Titles may mark a mnemonic ({@code "&File"}): Alt and the letter open that menu.
 */
public final class MenuBar extends Control {

    private Button[] titles = new Button[4];
    private Menu[] menus = new Menu[4];
    private int count;
    private Menu open;

    /** A menu bar named {@code name}. */
    @AllowAllocation("controls are built once")
    public MenuBar(Controls controls, String name) {
        super(controls, "menu-bar", Role.MENU_BAR, name);
    }

    /** Adds a menu titled {@code title}; returns it to fill. */
    @AllowAllocation("controls are built once")
    public Menu menu(CharSequence title) {
        Button b = new Button(controls, "menu-title", Role.MENU_ITEM, title, null, null);
        Menu m = new Menu(controls, Button.plain(title));
        m.bar = this;
        if (count == titles.length) {
            titles = Arrays.copyOf(titles, count * 2);
            menus = Arrays.copyOf(menus, count * 2);
        }
        titles[count] = b;
        menus[count] = m;
        count++;
        int index = count - 1;
        b.onClick(() -> toggle(index));
        listen(b.element(), EventType.ENTER.bit() | EventType.COMMAND.bit());
        root.add(b.element());
        return m;
    }

    /** Title button {@code index}. */
    public Button title(int index) {
        return titles[index];
    }

    /** Menu {@code index}. */
    public Menu menu(int index) {
        return menus[index];
    }

    /** The open menu, or null. */
    public Menu openMenu() {
        return open;
    }

    private int indexOf(Menu m) {
        for (int i = 0; i < count; i++) {
            if (menus[i] == m) {
                return i;
            }
        }
        return -1;
    }

    private void toggle(int index) {
        if (open == menus[index]) {
            open.closeAll();
        } else {
            show(index);
        }
    }

    private void show(int index) {
        if (open != null && open != menus[index]) {
            Menu old = open;
            old.close();
            titles[indexOf(old)].element().state(UiState.EXPANDED, false);
        }
        open = menus[index];
        open.restoreFocus = titles[index].element();
        titles[index].element().state(UiState.EXPANDED, true);
        open.open(titles[index].element(), true);
    }

    /** Moves from {@code from} to the menu {@code dir} away (wrapping), open. */
    void step(Menu from, int dir) {
        int i = indexOf(from);
        if (i < 0 || count < 2) {
            return;
        }
        show(Math.floorMod(i + dir, count));
    }

    /** {@code m} closed. */
    void closed(Menu m) {
        int i = indexOf(m);
        if (i >= 0) {
            titles[i].element().state(UiState.EXPANDED, false);
        }
        if (open == m) {
            open = null;
        }
    }

    /** Whether a press on {@code slot} is the bar's (a title toggles its menu itself). */
    boolean owns(int slot) {
        return controls.inside(root.slot(), slot);
    }

    @Override
    public void handle(UiEvent event) {
        int i = -1;
        for (int k = 0; k < count; k++) {
            if (titles[k].element().slot() == event.currentSlot()) {
                i = k;
            }
        }
        if (i < 0) {
            return;
        }
        if (event.type() == EventType.ENTER) {
            if (open != null && open != menus[i]) {
                show(i);
            }
            return;
        }
        UiCommand c = event.command();
        if (c == UiCommand.DOWN) {
            event.consume();
            show(i);
        } else if (c == UiCommand.LEFT || c == UiCommand.RIGHT) {
            event.consume();
            interaction().focus(titles[Math.floorMod(i + (c == UiCommand.LEFT ? -1 : 1), count)].element(),
                event.player());
        }
    }
}
