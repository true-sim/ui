package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.Facts;
import java.util.Arrays;

/**
 * Mnemonics: the underlined letters of menus, buttons and labels that Alt and the letter activate (design 10). Text
 * marks its letter with {@code &} ({@code "&File"}, {@code "Save &as..."}); {@code &&} is a literal ampersand, and an
 * {@code &} not before a letter or digit stays as it is ({@code "Salt & pepper"}). The letter shows underlined while
 * the engine reports the mnemonic modifier held ({@link UiInput#mnemonicsShown()}): each label gets a style part
 * {@code span.mnemonic} with the fact {@code mnemonics=shown} or {@code hidden}, and draws the underline while that
 * part's {@code text-decoration} underlines ({@link Ui#underline}), so a theme shows mnemonics always, only while Alt is
 * held, or never.
 *
 * <p>A mnemonic key ({@link UiInput#mnemonic()}) goes, in order, to:
 * <ol>
 *   <li>the open menu (the deepest open submenu): its item with that letter is activated (a submenu opens);</li>
 *   <li>the buttons (menu bar titles, tabs, checkboxes, any {@link Button}) and labels made by
 *   {@link Controls#labelFor} in the focused scope: the window or dialog holding the focus (the nearest ancestor
 *   announced as {@link Role#WINDOW} or {@link Role#DIALOG}; the trapping modal scope; with no focus, what is in no
 *   window). A button is clicked, a label focuses its target, a menu bar's title opens its menu.</li>
 * </ol>
 * When several have the letter, each press focuses the next of them without activating any, as Windows does.
 */
public final class Mnemonics {

    /** The fact on {@code span.mnemonic} parts while the mnemonic modifier is held. */
    public static final Facts SHOWN = Facts.of("mnemonics", "shown");
    /** The fact on {@code span.mnemonic} parts otherwise. */
    public static final Facts HIDDEN = Facts.of("mnemonics", "hidden");

    private final Controls controls;
    private final Ui ui;
    private final StringBuilder scratch = new StringBuilder();
    private Element[] labels = new Element[16];
    private Element[] parts = new Element[16];
    private int[] keys = new int[16];
    private Element[] focus = new Element[16];
    private Runnable[] actions = new Runnable[16];
    private int count;
    private boolean shown;
    private Menu[] openMenus = new Menu[4];
    private int openCount;
    private final int[] matches = new int[64];

    @AllowAllocation("one per UI")
    Mnemonics(Controls controls) {
        this.controls = controls;
        this.ui = controls.ui();
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Writes {@code text} without its mnemonic marker into {@code out} (cleared first): {@code &&} becomes {@code &};
     * returns the index in {@code out} of the marked character, or -1 if there is none. Allocates nothing.
     */
    public static int strip(CharSequence text, StringBuilder out) {
        out.setLength(0);
        int at = -1;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < n) {
                char next = text.charAt(i + 1);
                if (next == '&') {
                    out.append('&');
                    i++;
                    continue;
                }
                if (Character.isLetterOrDigit(Character.codePointAt(text, i + 1))) {
                    if (at < 0) {
                        at = out.length(); // the first marker counts; later ones are dropped
                    }
                    continue;
                }
            }
            out.append(c);
        }
        return at;
    }

    /** The key (lower case) of the character at {@code index} of {@code text}, or 0 for -1. */
    static int key(CharSequence text, int index) {
        return index < 0 ? 0 : Character.toLowerCase(Character.codePointAt(text, index));
    }

    /** {@code text} without its marker, in a scratch buffer valid until the next call. */
    CharSequence display(CharSequence text) {
        strip(text, scratch);
        return scratch;
    }

    // ---------------------------------------------------------------- registering

    /**
     * Makes a label showing {@code text} (its marker taken out) with its mnemonic registered: {@code action} runs and
     * {@code target} takes the focus when duplicates cycle (both null: shown only, as a menu item's, which its menu
     * handles); returns the label.
     */
    @AllowAllocation("controls are built once")
    Element label(CharSequence text, Element target, Runnable action) {
        int at = strip(text, scratch);
        Element label = ui.label(null, scratch);
        if (at >= 0) {
            register(label, at, key(scratch, at), target, action);
        }
        return label;
    }

    /** Sets {@code label}'s text to {@code text}, its mnemonic following; returns the shown text (scratch). */
    @AllowAllocation("text changes on edit")
    CharSequence text(Element label, CharSequence text, Element target, Runnable action) {
        int at = strip(text, scratch);
        ui.text(label, scratch);
        int i = indexOf(label);
        if (at < 0) {
            if (i >= 0) {
                keys[i] = 0;
                ui.underline(label, -1, parts[i]);
            }
        } else if (i >= 0) {
            keys[i] = key(scratch, at);
            ui.underline(label, at, parts[i]);
        } else {
            register(label, at, key(scratch, at), target, action);
        }
        return scratch;
    }

    @AllowAllocation("controls are built once")
    private void register(Element label, int at, int key, Element target, Runnable action) {
        if (count == labels.length) {
            int n = count * 2;
            labels = Arrays.copyOf(labels, n);
            parts = Arrays.copyOf(parts, n);
            keys = Arrays.copyOf(keys, n);
            focus = Arrays.copyOf(focus, n);
            actions = Arrays.copyOf(actions, n);
        }
        Element part = ui.part(label, "span", "mnemonic");
        ui.styles().facts(part.slot(), shown ? SHOWN : HIDDEN);
        ui.underline(label, at, part);
        labels[count] = label;
        parts[count] = part;
        keys[count] = key;
        focus[count] = target;
        actions[count] = action;
        count++;
    }

    private int indexOf(Element label) {
        for (int i = 0; i < count; i++) {
            if (labels[i] == label) {
                return i;
            }
        }
        return -1;
    }

    /** How many mnemonics are registered (alive or not). */
    public int count() {
        return count;
    }

    /** Whether the underlines show (the modifier is held). */
    public boolean shown() {
        return shown;
    }

    // ---------------------------------------------------------------- menus

    @AllowAllocation("growing with nested menus, rarely")
    void opened(Menu menu) {
        closed(menu);
        if (openCount == openMenus.length) {
            openMenus = Arrays.copyOf(openMenus, openCount * 2);
        }
        openMenus[openCount++] = menu;
    }

    void closed(Menu menu) {
        for (int i = 0; i < openCount; i++) {
            if (openMenus[i] == menu) {
                System.arraycopy(openMenus, i + 1, openMenus, i, openCount - i - 1);
                openMenus[--openCount] = null;
                return;
            }
        }
    }

    // ---------------------------------------------------------------- the frame

    /** Follows the modifier and routes this frame's mnemonic key; called by the controls' frame. */
    void frame(UiInput input) {
        if (input == null) {
            return;
        }
        if (input.mnemonicsShown() != shown) {
            show(input.mnemonicsShown());
        }
        int key = input.mnemonic();
        if (key != 0) {
            press(key, 0);
        }
    }

    /** Shows or hides the underlines of every registered mnemonic. */
    public void show(boolean on) {
        shown = on;
        Facts facts = on ? SHOWN : HIDDEN;
        for (int i = 0; i < count; i++) {
            if (parts[i].alive()) {
                ui.styles().facts(parts[i].slot(), facts);
            }
        }
    }

    /** The mnemonic key {@code key} (a lower-case code point) from {@code player}; true if something took it. */
    public boolean press(int key, int player) {
        int k = Character.toLowerCase(key);
        for (int i = openCount - 1; i >= 0; i--) {
            if (openMenus[i].isOpen()) {
                openMenus[i].mnemonic(k, player);
                return true; // an open menu takes every mnemonic, as a menu does
            }
        }
        Element f = ui.interaction().focused(player);
        Element modal = f == null ? ui.interaction().modalRoot() : null;
        int scope = f == null ? UiStore.NONE : scope(f.slot());
        int n = 0;
        for (int i = 0; i < count && n < matches.length; i++) {
            if (keys[i] != k || actions[i] == null || !available(i)) {
                continue;
            }
            int slot = labels[i].slot();
            if (modal != null ? inside(modal.slot(), slot) : scope(slot) == scope) {
                matches[n++] = i;
            }
        }
        if (n == 0) {
            return false;
        }
        if (n == 1) {
            actions[matches[0]].run();
            return true;
        }
        int next = 0;
        for (int m = 0; m < n; m++) {
            if (focus[matches[m]] == f) {
                next = (m + 1) % n;
                break;
            }
        }
        ui.interaction().focus(focus[matches[next]], player);
        return true;
    }

    private boolean available(int i) {
        Element label = labels[i];
        if (!label.alive()) {
            return false;
        }
        int slot = label.slot();
        Element target = focus[i];
        int t = target == null ? slot : target.slot();
        return ui.layout().shown(slot) && attached(slot) && !ui.interaction().disabled(t);
    }

    /** Whether {@code slot} is in a layer's tree (not detached: a closed menu's, a removed tab's). */
    private boolean attached(int slot) {
        UiStore store = ui.store();
        int top = slot;
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            top = s;
        }
        for (int i = 0; i < ui.layerStack().count(); i++) {
            if (ui.layerStack().at(i).rootSlot() == top) {
                return true;
            }
        }
        return false;
    }

    private boolean inside(int ancestor, int slot) {
        UiStore store = ui.store();
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (s == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** The nearest window or dialog at or above {@code slot}, or NONE. */
    private int scope(int slot) {
        UiStore store = ui.store();
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            Role r = store.role(s);
            if (r == Role.WINDOW || r == Role.DIALOG) {
                return s;
            }
        }
        return UiStore.NONE;
    }
}
