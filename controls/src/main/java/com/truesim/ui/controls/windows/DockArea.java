package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import java.util.ArrayList;
import java.util.List;

/**
 * A region of a {@link DockSpace} panels dock in: a tree of splits and panes filling its {@link #root()} (a
 * {@code dock-space} element). Every dock space has its main area ({@link DockSpace#root()}); a host with OS windows
 * ({@link OsDockHost}) makes one per window ({@link DockSpace#newArea}), so tabs move between windows as between panes:
 * dragged onto any area's panes or edges, they dock there.
 */
public final class DockArea {

    final DockSpace space;
    final String id;
    final Element root;
    DockSpace.Node tree;
    Object host; // what the host keeps for it (its OS window), or null

    DockArea(DockSpace space, String id, Element root) {
        WindowLayout.checkId(id);
        this.space = space;
        this.id = id;
        this.root = root;
    }

    /** The dock space it belongs to. */
    public DockSpace space() {
        return space;
    }

    /** Its id: {@code main} for the main area, the window's id for an OS window's. */
    public String id() {
        return id;
    }

    /** Its element ({@code dock-space}): where its tree lays out. */
    public Element root() {
        return root;
    }

    /** Whether it is its space's main area. */
    public boolean isMain() {
        return space.mainArea() == this;
    }

    /** Whether no panel is docked in it. */
    public boolean isEmpty() {
        return tree == null;
    }

    /** The panels docked in it, pane by pane (tree order), first to last tab. */
    @AllowAllocation("a query for tools and tests")
    public List<DockPanel> panels() {
        List<DockPanel> out = new ArrayList<>();
        DockSpace.collect(tree, out);
        return out;
    }

    @Override
    public String toString() {
        return "DockArea[" + id + "]";
    }
}
