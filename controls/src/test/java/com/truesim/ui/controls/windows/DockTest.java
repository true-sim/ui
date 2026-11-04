package com.truesim.ui.controls.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.testkit.SyntheticInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Docking: panes, splits, tabs, dragging tabs and windows, splitters, floating, the layout as text. */
class DockTest {

    /** A dock space of 1000 x 600 at the HUD's top left, with five panels made (not docked). */
    static final class Rig {
        final Fx f = new Fx();
        final WindowManager windows = new WindowManager(f.overlays);
        final DockSpace dock = new DockSpace(f.overlays, windows);
        final DockPanel scene = panel("scene", "Scene");
        final DockPanel hierarchy = panel("hierarchy", "Hierarchy");
        final DockPanel inspector = panel("inspector", "Inspector");
        final DockPanel console = panel("console", "Console");
        final DockPanel game = panel("game", "Game");

        Rig() {
            f.pin(dock.root(), 0, 0, 1000, 600);
        }

        DockPanel panel(String id, String title) {
            DockPanel p = dock.panel(id, title);
            p.content().add(f.ui.label(null, title + " content"));
            return p;
        }

        /** The editor layout: hierarchy left, inspector right, scene and game over the console in the middle. */
        Rig editor() {
            dock.dock(scene, DockPosition.CENTER);
            dock.dock(hierarchy, DockPosition.LEFT);
            dock.dock(inspector, DockPosition.RIGHT);
            dock.dock(console, scene, DockPosition.BOTTOM);
            dock.dock(game, scene, DockPosition.CENTER);
            f.run().run();
            return this;
        }

        float tabX(DockPanel p) {
            return f.cx(p.tab());
        }

        float tabY(DockPanel p) {
            return f.cy(p.tab());
        }
    }

    static final String EDITOR = "dock split(row; 0.1875 0.5625 0.25; tabs(0; hierarchy) split(column; 0.5 0.5; "
        + "tabs(1; scene game) tabs(0; console)) tabs(0; inspector))\n";

    @Test
    void panelsDockBesidePanesAndAtTheOuterEdgesNestingSplits() {
        Rig r = new Rig().editor();
        assertThat(r.dock.layout().toText()).isEqualTo(EDITOR);
        assertThat(r.dock.paneCount()).isEqualTo(4);
        Element h = r.dock.paneOf(r.hierarchy);
        Element i = r.dock.paneOf(r.inspector);
        assertThat(r.f.x(h)).isEqualTo(0);
        assertThat(r.f.w(h)).isCloseTo((1000 - 8) * 0.1875f, within(1f)); // two 4 px splitters
        assertThat(r.f.x(i) + r.f.w(i)).isEqualTo(1000);
        assertThat(r.f.w(i)).isCloseTo((1000 - 8) * 0.25f, within(1f));
        Element s = r.dock.paneOf(r.scene);
        Element c = r.dock.paneOf(r.console);
        assertThat(r.f.y(c)).isCloseTo(r.f.y(s) + r.f.h(s) + 4, within(1f));
        assertThat(r.game.isActive()).isTrue();
        assertThat(r.scene.isActive()).isFalse();
        assertThat(r.f.ui.layout().shown(r.scene.content().slot())).isFalse();
        assertThat(r.f.ui.layout().shown(r.game.content().slot())).isTrue();
        assertThat(r.game.tab().is(UiState.SELECTED)).isTrue();
        assertThat(r.dock.pane(1)).containsExactly(r.scene, r.game);
        assertThat(Semantics.unnamed(Semantics.of(r.f.ui.store(), r.f.ui.interaction()))).isEmpty();
        String semantics = Semantics.of(r.f.ui.store(), r.f.ui.interaction()).toString();
        assertThat(semantics).contains("TAB_LIST", "TAB \"Scene\"", "TAB_PANEL \"Game\"", "BUTTON \"Close\"",
            "SEPARATOR");
        // outer edges: a quarter of the space
        r.dock.dock(r.console, DockPosition.BOTTOM);
        assertThat(r.dock.layout().toText()).startsWith("dock split(column; 0.75 0.25; split(row;");
        r.dock.dock(r.console, DockPosition.TOP);
        assertThat(r.dock.layout().toText()).startsWith("dock split(column; 0.25 0.75; tabs(0; console) split(row;");
        assertThatThrownBy(() -> r.dock.panel("scene", "again")).isInstanceOf(IllegalArgumentException.class);
        DockPanel loose = r.panel("loose", "Loose");
        assertThatThrownBy(() -> r.dock.dock(r.scene, loose, DockPosition.LEFT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(r.dock.toString()).contains("6 panels");
    }

    @Test
    void tabsActivateOnPressAndByKeysAndDragToReorderWithinTheirStrip() {
        Rig r = new Rig().editor();
        r.f.click(r.tabX(r.scene), r.tabY(r.scene)).run();
        assertThat(r.scene.isActive()).isTrue();
        r.f.ui.interaction().focus(r.scene.tab());
        r.f.command(UiCommand.RIGHT).run();
        assertThat(r.game.isActive()).isTrue();
        assertThat(r.f.ui.interaction().focused(0)).isEqualTo(r.game.tab());
        r.f.command(UiCommand.LEFT).run();
        assertThat(r.scene.isActive()).isTrue();
        // drag game before scene
        float gx = r.tabX(r.game);
        float sx = r.f.x(r.scene.tab()) + 2;
        r.f.drag(gx, r.tabY(r.game), sx, r.tabY(r.scene));
        r.f.run();
        assertThat(r.dock.pane(1)).containsExactly(r.game, r.scene);
        assertThat(r.game.isActive()).isTrue();
        r.dock.dockTab(r.game, r.scene, 2);
        assertThat(r.dock.pane(1)).containsExactly(r.scene, r.game);
        assertThat(r.dock.stripOf(r.scene)).isNotNull();
    }

    @Test
    void aTabDraggedOntoAnotherPaneJoinsItsTabsOrSplitsItAtTheSideAndThePreviewShowsWhere() {
        Rig r = new Rig().editor();
        Element console = r.dock.paneOf(r.console);
        float tx = r.tabX(r.game);
        float ty = r.tabY(r.game);
        r.f.dragTo(tx, ty, r.f.cx(console), r.f.cy(console) + 10);
        assertThat(r.dock.previewShowing()).isTrue();
        Element preview = r.dock.previewElement();
        assertThat(r.f.x(preview)).isEqualTo(r.f.x(console));
        assertThat(r.f.w(preview)).isEqualTo(r.f.w(console));
        assertThat(zone(r, "center").is(UiState.SELECTED)).isTrue();
        assertThat(r.f.cx(r.dock.zonesElement())).isCloseTo(r.f.cx(console), within(1f));
        assertThat(r.game.tab().is(UiState.DRAGGING)).isTrue();
        r.f.release().run();
        assertThat(r.dock.previewShowing()).isFalse();
        assertThat(r.dock.layout().toText()).contains("tabs(1; console game)");
        // onto the inspector's left edge: the inspector's pane splits there
        Element inspector = r.dock.paneOf(r.inspector);
        r.f.dragTo(r.tabX(r.game), r.tabY(r.game), r.f.x(inspector) + 6, r.f.cy(inspector));
        assertThat(zone(r, "left").is(UiState.SELECTED)).isTrue();
        assertThat(r.f.w(preview)).isCloseTo(r.f.w(inspector) / 2, within(1f));
        r.f.release().run();
        assertThat(r.dock.layout().toText()).contains("tabs(0; game) tabs(0; inspector))");
        // onto the space's outer bottom edge: across the whole space
        r.f.dragTo(r.tabX(r.hierarchy), r.tabY(r.hierarchy), 500, 597);
        assertThat(r.f.w(preview)).isEqualTo(1000);
        assertThat(r.f.h(preview)).isEqualTo(150);
        r.f.release().run();
        assertThat(r.dock.layout().toText()).startsWith("dock split(column; 0.75 0.25;")
            .endsWith("tabs(0; hierarchy))\n");
    }

    private static Element zone(Rig r, String id) {
        for (int c = r.f.ui.store().firstChild(r.dock.zonesElement().slot()); c >= 0;
             c = r.f.ui.store().nextSibling(c)) {
            if (id.equals(r.f.ui.store().id(c))) {
                return r.f.ui.store().element(c);
            }
        }
        throw new AssertionError(id);
    }

    @Test
    void aTabDroppedOutsideFloatsInAWindowWhoseTitleDraggedBackDocksIt() {
        Rig r = new Rig().editor();
        r.f.drag(r.tabX(r.console), r.tabY(r.console), 1150, 650);
        r.f.run().run();
        assertThat(r.console.isFloating()).isTrue();
        assertThat(r.console.isDocked()).isFalse();
        FloatingWindow w = ((WindowDockHost) r.dock.host()).window(r.console);
        assertThat(w).isNotNull();
        assertThat(w.isOpen()).isTrue();
        assertThat(w.title()).isEqualTo("Console");
        assertThat(r.console.content().parent()).isEqualTo(w.content());
        assertThat(r.dock.layout().toText()).isEqualTo("dock split(row; 0.1875 0.5625 0.25; tabs(0; hierarchy) "
            + "tabs(1; scene game) tabs(0; inspector))\nfloating console " + fmt(w.x()) + " " + fmt(w.y()) + " "
            + fmt(w.width()) + " " + fmt(w.height()) + "\n");
        // its title over the hierarchy's pane: the preview shows, the drop docks it there
        Element h = r.dock.paneOf(r.hierarchy);
        float tx = r.f.x(w.titleBar()) + 20;
        float ty = r.f.cy(w.titleBar());
        r.f.dragTo(tx, ty, r.f.cx(h), r.f.cy(h));
        assertThat(r.dock.previewShowing()).isTrue();
        r.f.release().run();
        assertThat(r.console.isDocked()).isTrue();
        assertThat(w.isOpen()).isFalse();
        assertThat(r.dock.pane(0)).containsExactly(r.hierarchy, r.console);
        // with Shift held nothing docks: dropped on the space, it floats there
        r.f.in.modifiers(SyntheticInput.MOUSE, Pointer.SHIFT);
        r.f.drag(r.tabX(r.console), r.tabY(r.console), 500, 300);
        r.f.in.modifiers(SyntheticInput.MOUSE, 0);
        r.f.run();
        assertThat(r.console.isFloating()).isTrue();
        // a floating panel's window closing closes the panel
        FloatingWindow again = ((WindowDockHost) r.dock.host()).window(r.console);
        again.requestClose();
        assertThat(r.console.isOpen()).isFalse();
        r.dock.undock(r.scene);
        assertThat(r.scene.isFloating()).isTrue();
        r.scene.activate();
        r.dock.dock(r.scene, r.game, DockPosition.CENTER);
        assertThat(r.scene.isDocked()).isTrue();
        assertThat(((WindowDockHost) r.dock.host()).windows()).isSameAs(r.windows);
    }

    private static String fmt(float v) {
        return v == (int) v ? Integer.toString((int) v) : Float.toString(v);
    }

    @Test
    void splittersResizeNeighboursWithinTheirMinimumByPointerAndKeys() {
        Rig r = new Rig().editor();
        Element[] splitters = r.dock.splittersBeside(r.hierarchy);
        assertThat(splitters).hasSize(2);
        Element first = splitters[0];
        float before = r.f.w(r.dock.paneOf(r.hierarchy));
        r.f.drag(r.f.cx(first), r.f.cy(first), r.f.cx(first) + 100, r.f.cy(first));
        r.f.run();
        assertThat(r.f.w(r.dock.paneOf(r.hierarchy))).isCloseTo(before + 100, within(1f));
        float[] ratios = r.dock.ratiosBeside(r.hierarchy);
        assertThat(ratios[0] + ratios[1] + ratios[2]).isCloseTo(1f, within(0.001f));
        r.f.drag(r.f.cx(first), r.f.cy(first), -500, r.f.cy(first));
        r.f.run();
        assertThat(r.f.w(r.dock.paneOf(r.hierarchy))).isCloseTo(48, within(1f)); // dock-min-pane
        r.f.ui.interaction().focus(first);
        r.f.run().command(UiCommand.RIGHT).run();
        assertThat(r.f.w(r.dock.paneOf(r.hierarchy))).isCloseTo(48 + WindowManager.KEY_STEP.get(), within(1f));
        r.f.mouse(r.f.cx(first), r.f.cy(first));
        assertThat(r.f.ui.interaction().cursor(SyntheticInput.MOUSE))
            .isEqualTo(com.truesim.ui.core.input.Cursor.RESIZE_EW);
    }

    @Test
    void closingATabTakesItOutAndAnEmptiedPaneCollapsesItsSplit() {
        Rig r = new Rig().editor();
        boolean[] keep = {true};
        r.inspector.listener(new DockPanel.Listener() {
            @Override
            public boolean closing(DockPanel panel) {
                return !keep[0];
            }
        });
        Element close = r.inspector.closeButton();
        r.f.click(r.f.cx(close), r.f.cy(close));
        assertThat(r.inspector.isOpen()).isTrue();
        keep[0] = false;
        r.f.click(r.f.cx(close), r.f.cy(close)).run();
        assertThat(r.inspector.isOpen()).isFalse();
        assertThat(r.dock.layout().toText()).isEqualTo("dock split(row; 0.1875 0.8125; tabs(0; hierarchy) "
            + "split(column; 0.5 0.5; tabs(1; scene game) tabs(0; console)))\n");
        r.dock.close(r.console);
        assertThat(r.dock.layout().toText()).isEqualTo("dock split(row; 0.1875 0.8125; tabs(0; hierarchy) "
            + "tabs(1; scene game))\n");
        r.dock.close(r.hierarchy);
        r.dock.close(r.scene);
        assertThat(r.dock.layout().toText()).isEqualTo("dock tabs(0; game)\n");
        r.game.closable(false);
        r.f.run();
        r.dock.close(r.game);
        assertThat(r.dock.layout().toText()).isEqualTo("dock none\n");
        r.dock.dock(r.game, DockPosition.LEFT);
        assertThat(r.dock.layout().toText()).isEqualTo("dock tabs(0; game)\n");
        r.game.title("Play");
        assertThat(r.game.title()).isEqualTo("Play");
    }

    @Test
    void theLayoutIsDataItsTextRoundTripsAndRestoresTheSameArrangementWithStyles() {
        Rig r = new Rig().editor();
        r.dock.undock(r.console, 40, 50, 300, 200);
        r.f.run();
        DockLayout layout = r.dock.layout();
        String text = layout.toText();
        assertThat(DockLayout.parse(text)).isEqualTo(layout);
        assertThat(DockLayout.parse(text).toText()).isEqualTo(text);
        String styled = "dock split.editor(row; 0.3 0.7; tabs.tools(0; hierarchy inspector) "
            + "split(column; 0.6 0.4; tabs(1; scene game) tabs(0; console)))  # the editor\n";
        DockLayout parsed = DockLayout.parse(styled);
        assertThat(parsed.root().style()).isEqualTo("editor");
        assertThat(parsed.panels()).containsExactly("hierarchy", "inspector", "scene", "game", "console");
        // restored into a fresh space: the same arrangement, the style names on the elements
        Rig fresh = new Rig();
        fresh.dock.restore(parsed);
        fresh.f.run().run();
        assertThat(fresh.dock.layout()).isEqualTo(parsed);
        assertThat(fresh.dock.paneOf(fresh.hierarchy).style()).isEqualTo("tools");
        assertThat(fresh.dock.layout().toText()).isEqualTo(
            "dock split.editor(row; 0.3 0.7; tabs.tools(0; hierarchy inspector) split(column; 0.6 0.4; "
                + "tabs(1; scene game) tabs(0; console)))\n");
        assertThat(fresh.f.w(fresh.dock.paneOf(fresh.hierarchy))).isCloseTo((1000 - 4) * 0.3f, within(1f));
        // unknown ids are skipped and their splits collapse; floating panels float again
        fresh.dock.restore(DockLayout.parse("dock split(row; 0.5 0.5; tabs(0; nothing) tabs(0; game))\n"
            + "floating console 10 20 300 200\n"));
        assertThat(fresh.dock.layout().toText()).isEqualTo("dock tabs(0; game)\nfloating console 10 20 300 200\n");
        assertThat(fresh.scene.isOpen()).isFalse();
        // persistence
        LayoutStore store = LayoutStore.inMemory();
        store.save("dock", EDITOR);
        Rig third = new Rig();
        third.dock.persistence(store, "dock");
        assertThat(third.dock.layout().toText()).isEqualTo(EDITOR);
        third.dock.close(third.game);
        assertThat(store.load("dock")).contains("tabs(0; scene)");
        assertThat(DockLayout.parse("dock none\n")).isEqualTo(DockLayout.EMPTY);
        assertThatThrownBy(() -> DockLayout.parse("dock split(row; 1; tabs(0; a))"))
            .hasMessageContaining("line 1");
        assertThatThrownBy(() -> DockLayout.parse("dock tabs(0 a)")).hasMessageContaining("expected ';'");
        assertThatThrownBy(() -> DockLayout.parse("dock box(0; a)")).hasMessageContaining("split or tabs");
        assertThatThrownBy(() -> DockLayout.parse("dock split(diagonal; 1 1; tabs(0; a) tabs(0; b))"))
            .hasMessageContaining("row or column");
        assertThatThrownBy(() -> DockLayout.parse("dock split(row; x y; tabs(0; a) tabs(0; b))"))
            .hasMessageContaining("number");
        assertThatThrownBy(() -> DockLayout.parse("dock tabs(0; a) extra")).hasMessageContaining("unexpected");
        assertThatThrownBy(() -> DockLayout.parse("dock none\ndock none")).hasMessageContaining("second");
        assertThatThrownBy(() -> DockLayout.parse("floating a 1 2")).hasMessageContaining("floating");
        assertThatThrownBy(() -> DockLayout.parse("windows")).hasMessageContaining("dock or floating");
        assertThatThrownBy(() -> new DockLayout.Tabs(List.of("a"), 3, null)).isInstanceOf(
            IllegalArgumentException.class);
    }
}
