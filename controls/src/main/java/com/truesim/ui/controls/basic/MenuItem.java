package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;

/**
 * One entry of a {@link Menu} (type {@code menu-item}: a {@code menu-check} box, a {@code label}, a label of style
 * name {@code menu-shortcut}, and for submenus a {@code menu-arrow} icon; {@code :checked} for checked check and
 * radio items, {@code :expanded} while its submenu shows), or a {@code menu-separator}.
 */
public final class MenuItem {

    /** What an item does. */
    enum Kind {
        ACTION, CHECK, RADIO, SUBMENU, SEPARATOR
    }

    final Kind kind;
    final Element element;
    final Menu submenu;
    private final Controls controls;
    private final Runnable action;
    private final VersionedBoolean check;
    private final VersionedInt radio;
    private final int radioValue;
    private final Watch watch;
    private Element shortcutLabel;
    private Object shortcut;

    @AllowAllocation("menus are built once")
    MenuItem(Controls controls, Menu menu, Kind kind, CharSequence text, Runnable action, VersionedBoolean check,
        VersionedInt radio, int radioValue, Menu submenu) {
        this.controls = controls;
        this.kind = kind;
        this.action = action;
        this.check = check;
        this.radio = radio;
        this.radioValue = radioValue;
        this.submenu = submenu;
        if (kind == Kind.SEPARATOR) {
            element = controls.ui().element("menu-separator", null);
            element.semantics(Role.SEPARATOR, null);
            watch = null;
            return;
        }
        element = controls.ui().element("menu-item", null);
        element.semantics(Role.MENU_ITEM, text.toString());
        controls.ui().interaction().focusable(element, true);
        Element box = controls.ui().element("menu-check", null);
        Element label = controls.ui().label(null, text);
        label.semantics(Role.NONE, null);
        element.add(box).add(label);
        if (kind == Kind.SUBMENU) {
            element.add(controls.icon("menu-arrow"));
        } else {
            shortcutLabel = controls.ui().label("menu-shortcut", "");
            shortcutLabel.semantics(Role.NONE, null);
            element.add(shortcutLabel);
        }
        watch = check != null ? check.watch() : radio != null ? radio.watch() : null;
        show();
    }

    /** The item's element. */
    public Element element() {
        return element;
    }

    /** The submenu this item opens, or null. */
    public Menu submenu() {
        return submenu;
    }

    /** Shows the shortcut the engine binds to {@code engineAction} ({@link ShortcutGlyphs}); returns this. */
    public MenuItem shortcut(Object engineAction) {
        shortcut = engineAction;
        return this;
    }

    /** Enables or disables the item; returns this. */
    public MenuItem enabled(boolean on) {
        element.state(UiState.DISABLED, !on);
        return this;
    }

    /** Whether focus and activation can reach it (not a separator, not disabled). */
    boolean focusable() {
        return kind != Kind.SEPARATOR && !element.is(UiState.DISABLED);
    }

    /** Runs the item: its action, or flips its check, or sets its radio value. */
    void activate() {
        switch (kind) {
            case ACTION -> action.run();
            case CHECK -> check.set(!check.get());
            case RADIO -> radio.set(radioValue);
            default -> {
                // submenus open, separators do nothing
            }
        }
        show();
    }

    void poll() {
        if (watch != null && watch.isChanged()) {
            show();
        }
    }

    private void show() {
        if (watch == null) {
            return;
        }
        watch.update();
        Control.deepState(element, UiState.CHECKED, check != null ? check.get() : radio.get() == radioValue);
    }

    void glyph(ShortcutGlyphs glyphs) {
        if (shortcutLabel != null) {
            controls.ui().text(shortcutLabel, shortcut == null ? "" : glyphs.label(shortcut));
        }
    }
}
