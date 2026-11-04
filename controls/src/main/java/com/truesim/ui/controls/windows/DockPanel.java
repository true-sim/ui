package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;

/**
 * A panel of a {@link DockSpace}, made by {@link DockSpace#panel}: content (a {@code dock-panel} element to fill) with
 * a title, shown docked as a tab of a pane, floating in a window, or closed. Its tab ({@code dock-tab}: a label and a
 * {@code dock-tab-close} button) moves with it.
 */
public final class DockPanel {

    /** What a panel tells its owner. */
    public interface Listener {
        /** The user asked to close the panel; false keeps it. */
        default boolean closing(DockPanel panel) {
            return true;
        }

        /** The panel closed. */
        default void closed(DockPanel panel) {
        }
    }

    private static final Listener NONE = new Listener() {
    };

    private final DockSpace space;
    private final String id;
    private String title;
    final Element tab;
    final Element tabLabel;
    final Element closeButton;
    final Element content;
    DockSpace.Tabs tabs;
    boolean floating;
    // where it last was in the main area: beside the first of these panels still there, at this position (and tab
    // index for the centre)
    java.util.List<DockPanel> homeNear = java.util.List.of();
    DockPosition homePosition = DockPosition.CENTER;
    int homeIndex = -1;
    int homeUp; // how far up from the pane of those panels the node it docked beside was
    float homeShare; // the share of its split it had
    private boolean closable = true;
    Listener listener = NONE;

    @AllowAllocation("panels are made when a UI is built")
    DockPanel(DockSpace space, String id, String title, Element tab, Element tabLabel, Element closeButton,
        Element content) {
        WindowLayout.checkId(id);
        this.space = space;
        this.id = id;
        this.title = title;
        this.tab = tab;
        this.tabLabel = tabLabel;
        this.closeButton = closeButton;
        this.content = content;
    }

    /** The dock space it belongs to. */
    public DockSpace space() {
        return space;
    }

    /** The id: what layouts name it by. */
    public String id() {
        return id;
    }

    /** The title its tab and window show. */
    public String title() {
        return title;
    }

    /** Sets the title; returns this. */
    @AllowAllocation("titles change on user action")
    public DockPanel title(String value) {
        title = value;
        space.retitle(this);
        return this;
    }

    /** Where its content goes ({@code dock-panel}). */
    public Element content() {
        return content;
    }

    /** Its tab ({@code dock-tab}). */
    public Element tab() {
        return tab;
    }

    /** Its tab's close button ({@code dock-tab-close}). */
    public Element closeButton() {
        return closeButton;
    }

    /** Whether it is docked in a pane. */
    public boolean isDocked() {
        return tabs != null;
    }

    /** Whether it floats in a window (or another host). */
    public boolean isFloating() {
        return floating;
    }

    /** Whether it shows: docked or floating. */
    public boolean isOpen() {
        return tabs != null || floating;
    }

    /** Whether it is its pane's shown tab. */
    public boolean isActive() {
        return tabs != null && tabs.active() == this;
    }

    /** Whether the user may close it (its tab shows a close button); returns this. */
    public DockPanel closable(boolean value) {
        closable = value;
        return this;
    }

    /** Whether the user may close it. */
    public boolean closable() {
        return closable;
    }

    /** Sets what is told when it closes; returns this. */
    public DockPanel listener(Listener value) {
        listener = value == null ? NONE : value;
        return this;
    }

    /** Shows it: its pane's active tab, or its window raised. */
    public void activate() {
        space.activate(this);
    }

    /** Asks to close it: closes unless its listener keeps it. Returns whether it closed. */
    public boolean requestClose() {
        if (!listener.closing(this)) {
            return false;
        }
        space.close(this);
        return true;
    }

    @AllowAllocation("names are set when a panel is made")
    void name(String closeName) {
        tab.semantics(Role.TAB, title);
        tabLabel.semantics(Role.TEXT, title);
        closeButton.semantics(Role.BUTTON, closeName);
    }

    @Override
    public String toString() {
        return "DockPanel[" + id + (tabs != null ? " docked" : floating ? " floating" : " closed") + "]";
    }
}
