package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Insets;
import java.util.Arrays;

/**
 * A toolbar (type {@code toolbar}): a row of tools (buttons, toggles, separators, any element) in a
 * {@code toolbar-viewport} (the theme lays it out as {@code none}, so the {@code toolbar-items} row keeps its natural
 * width) that moves the tools that do not fit into the menu of an overflow button (id {@code toolbar-overflow}, icon
 * {@code more}) at its end. Fitting reads the widths of the last layout, so after a resize the row settles in the next
 * frame; it allocates only when the set of overflowing tools changes.
 */
public final class Toolbar extends Control {

    private Element[] tools = new Element[8];
    private CharSequence[] labels = new CharSequence[8];
    private Runnable[] actions = new Runnable[8];
    private float[] widths = new float[8];
    private int count;
    private int fitting = -1;
    private final Button overflow;
    private final Menu menu;
    private final Element viewport;
    private final Element items;
    private float overflowWidth;
    private float seenWidth = -1;

    /** A toolbar named {@code name}; {@code more} names its overflow button. */
    @AllowAllocation("controls are built once")
    public Toolbar(Controls controls, String name, String more) {
        super(controls, "toolbar", Role.TOOLBAR, name);
        overflow = new Button(controls, "more", null, more);
        overflow.element().id("toolbar-overflow");
        menu = new Menu(controls, more);
        overflow.onClick(this::toggleMenu);
        viewport = part("toolbar-viewport");
        items = part("toolbar-items");
        viewport.add(items);
        root.add(viewport).add(overflow.element());
        controls.poll(this);
    }

    private void toggleMenu() {
        if (menu.isOpen()) {
            menu.closeAll();
        } else {
            menu.open(overflow.element(), true);
        }
    }

    /** Adds {@code button}, listed in the overflow menu as {@code label} (clicking it) when it does not fit. */
    @AllowAllocation("controls are built once")
    public Toolbar add(Button button, CharSequence label) {
        return add(button.element(), label, button::click);
    }

    /**
     * Adds {@code tool}; when it does not fit it shows in the overflow menu as {@code label} running {@code action}
     * (a null label: left out of the menu, as a separator is).
     */
    @AllowAllocation("controls are built once")
    public Toolbar add(Element tool, CharSequence label, Runnable action) {
        if (count == tools.length) {
            tools = Arrays.copyOf(tools, count * 2);
            labels = Arrays.copyOf(labels, count * 2);
            actions = Arrays.copyOf(actions, count * 2);
            widths = Arrays.copyOf(widths, count * 2);
        }
        tools[count] = tool;
        labels[count] = label;
        actions[count] = action;
        count++;
        items.add(tool);
        fitting = -1;
        seenWidth = -1;
        return this;
    }

    /** Adds a vertical separator. */
    @AllowAllocation("controls are built once")
    public Toolbar separator() {
        return add(new Separator(controls, true).element(), null, null);
    }

    /** How many tools show in the row now (all of them before the first fit). */
    public int fitting() {
        return fitting < 0 ? count : fitting;
    }

    /** The overflow button. */
    public Button overflowButton() {
        return overflow;
    }

    /** The overflow menu. */
    public Menu overflowMenu() {
        return menu;
    }

    @Override
    public void poll(long nanos) {
        Layout l = ui.layout();
        int r = root.slot();
        if (!l.shown(r)) {
            return;
        }
        for (int i = 0; i < count; i++) {
            int s = tools[i].slot();
            if (tools[i].parent() == items && l.shown(s)) {
                widths[i] = l.width(s);
            }
        }
        int o = overflow.element().slot();
        if (overflow.element().parent() == root && l.shown(o)) {
            overflowWidth = l.width(o);
        }
        float width = l.width(r);
        if (width == seenWidth && fitting >= 0) {
            return;
        }
        seenWidth = width;
        ResolvedStyle style = ui.styles().styleOf(r);
        float gap = ui.styles().styleOf(items.slot()).getFloat(Styles.GAP) * l.scale();
        Insets pad = style.get(Styles.PADDING);
        float available = width - (pad.left() + pad.right() + 2 * style.get(Styles.BORDER).width()) * l.scale();
        float total = 0;
        for (int i = 0; i < count; i++) {
            total += widths[i] + (i > 0 ? gap : 0);
        }
        int fit = count;
        if (total > available) {
            float room = available - overflowWidth - style.getFloat(Styles.GAP) * l.scale();
            float used = 0;
            fit = 0;
            while (fit < count && used + (fit > 0 ? gap : 0) + widths[fit] <= room) {
                used += (fit > 0 ? gap : 0) + widths[fit];
                fit++;
            }
        }
        if (fit != fitting) {
            fitting = fit;
            arrange();
        }
    }

    @AllowAllocation("the overflowing tools change on resize")
    private void arrange() {
        for (int i = 0; i < count; i++) {
            if (tools[i].parent() != null) {
                tools[i].detach();
            }
        }
        if (overflow.element().parent() != null) {
            overflow.element().detach();
        }
        menu.clear();
        for (int i = 0; i < count; i++) {
            if (i < fitting) {
                items.add(tools[i]);
            } else if (labels[i] != null) {
                menu.item(labels[i], actions[i]);
            }
        }
        if (fitting < count) {
            root.add(overflow.element());
        }
    }
}
