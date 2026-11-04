package com.truesim.ui.controls.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.ui.controls.overlay.ElementAnchor;
import com.truesim.ui.controls.overlay.Overlay;
import com.truesim.ui.controls.overlay.Placement;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/** The less travelled paths: hover cards by keys and touch, every side of a card, docking edge cases, snapping. */
class WindowsMoreTest {

    @Test
    void aHoverCardOpensOnVisibleFocusAndByATapAndClosesWhenFocusLeavesIt() {
        Fx f = new Fx();
        Popovers popovers = new Popovers(f.overlays);
        Element trigger = f.box(100, 100, 100, 30);
        Element other = f.box(400, 100, 100, 30);
        trigger.semantics(Role.BUTTON, "Trigger");
        other.semantics(Role.BUTTON, "Other");
        f.ui.interaction().focusable(trigger, true);
        f.ui.interaction().focusable(other, true);
        Element inside = f.ui.panel("card");
        inside.semantics(Role.BUTTON, "Inside");
        f.ui.interaction().focusable(inside, true);
        Popover card = popovers.hover(trigger, inside);
        f.run();
        f.command(UiCommand.NEXT);
        assertThat(card.isOpen()).isTrue();
        f.run();
        f.ui.interaction().focus(inside); // into the card: it stays
        f.run();
        assertThat(card.isOpen()).isTrue();
        f.ui.interaction().focus(trigger); // back to the trigger: it stays
        f.run();
        assertThat(card.isOpen()).isTrue();
        f.ui.interaction().focus(inside);
        f.run();
        f.ui.interaction().focus(other); // away from both: it closes
        f.run();
        assertThat(card.isOpen()).isFalse();
        f.ui.interaction().focus(trigger);
        f.command(UiCommand.ACCEPT); // ACCEPT on a hover card's trigger toggles it like a click
        f.run();
        f.ui.interaction().focus(other);
        f.run();
        // a tap opens it, another closes it
        f.in.pointer(5, Pointer.Kind.TOUCH, 150, 115, Pointer.PRIMARY);
        f.run();
        f.in.pointer(5, Pointer.Kind.TOUCH, 150, 115, 0);
        f.run();
        assertThat(card.isOpen()).isTrue();
        f.in.pointer(5, Pointer.Kind.TOUCH, 150, 115, Pointer.PRIMARY);
        f.run();
        f.in.pointer(5, Pointer.Kind.TOUCH, 150, 115, 0);
        f.run();
        assertThat(card.isOpen()).isFalse();
        f.in.lift(5);
        f.run();
        // a mouse click on a hover card's trigger does nothing; CANCEL closes an open one
        card.open();
        f.click(150, 115);
        assertThat(card.isOpen()).isTrue();
        f.ui.interaction().focus(trigger);
        f.command(UiCommand.CANCEL);
        assertThat(card.isOpen()).isFalse();
    }

    static final StyleSheet SIDES = Tss.compileString("sides.tss", """
        layer { layout: none; }
        panel { layout: none; pointer-events: all; }
        hover-card { width: 80px; height: 40px; placement: top; placement-gap: 4px; }
        hover-card.left { placement: left; }
        hover-card.right { placement: right; }
        label { color: white; }
        """);

    @Test
    void hoverIntentAimsAtTheCardsNearEdgeOnEverySide() {
        for (Placement side : new Placement[] {Placement.TOP, Placement.LEFT, Placement.RIGHT}) {
            Fx f = new Fx(SIDES, false);
            Popovers popovers = new Popovers(f.overlays);
            Element trigger = f.box(400, 300, 100, 30);
            Popover card = popovers.hover(trigger, f.ui.panel(null));
            card.surface().style(side == Placement.TOP ? null : side == Placement.LEFT ? "left" : "right");
            f.run().mouse(450, 315).idle(Popovers.OPEN_DELAY.get() + 0.05);
            assertThat(card.isOpen()).isTrue();
            f.run();
            assertThat(card.overlay().side()).isEqualTo(side);
            // towards the card's middle, just outside the trigger
            float tx = side == Placement.TOP ? 450 : side == Placement.LEFT ? 398 : 502;
            float ty = side == Placement.TOP ? 298 : 315;
            f.mouse(tx, ty).idle(Popovers.CLOSE_DELAY.get() + 0.05);
            assertThat(card.isOpen()).as("%s: moving towards it keeps it", side).isTrue();
            f.mouse(450, 600).idle(Popovers.CLOSE_DELAY.get() + 0.05);
            assertThat(card.isOpen()).as("%s: away", side).isFalse();
        }
    }

    @Test
    void anOverlayInAnotherHostAndRightToLeftStartAndEnd() {
        Fx f = new Fx(SIDES, false);
        f.overlays.rightToLeft(true);
        Element host = f.box(100, 100, 600, 400);
        Element anchor = f.box(300, 300, 50, 20);
        Element card = f.ui.element("hover-card", "right");
        Overlay o = f.overlays.create(card, Layers.POPUP).host(host).anchor(new ElementAnchor(f.ui.layout(), anchor));
        assertThat(o.host()).isEqualTo(host);
        o.show();
        f.run();
        assertThat(card.parent()).isEqualTo(host);
        assertThat(f.x(card)).isEqualTo(354);
        assertThatThrownBy(() -> o.host(f.ui.root())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> o.modal(true)).isInstanceOf(IllegalStateException.class);
        assertThat(o.modal()).isFalse();
        assertThat(o.input()).isNotNull();
        assertThat(o.y()).isEqualTo(290);
        card.destroy(); // a destroyed overlay root hides its overlay
        f.run();
        assertThat(o.showing()).isFalse();
    }

    @Test
    void dockingEdgeCasesKeepTheTreeTidy() {
        DockTest.Rig r = new DockTest.Rig();
        // the first panel docked at the centre of an empty space, then more at the centre join the first pane
        r.dock.dock(r.scene, DockPosition.CENTER);
        r.dock.dock(r.hierarchy, DockPosition.RIGHT);
        r.dock.dock(r.game, DockPosition.CENTER);
        assertThat(r.dock.layout().toText()).isEqualTo("dock split(row; 0.75 0.25; tabs(1; scene game) "
            + "tabs(0; hierarchy))\n");
        // docking beside itself when alone changes nothing
        r.dock.dock(r.hierarchy, r.hierarchy, DockPosition.LEFT);
        assertThat(r.dock.layout().toText()).contains("tabs(0; hierarchy)");
        // a column with a row in it: taking the column's other pane out merges the row into the outer row
        r.dock.dock(r.console, r.hierarchy, DockPosition.BOTTOM);
        r.dock.dock(r.inspector, r.console, DockPosition.RIGHT);
        assertThat(r.dock.layout().toText()).isEqualTo("dock split(row; 0.75 0.25; tabs(1; scene game) "
            + "split(column; 0.5 0.5; tabs(0; hierarchy) split(row; 0.5 0.5; tabs(0; console) tabs(0; inspector))))\n");
        r.dock.close(r.hierarchy);
        assertThat(r.dock.layout().toText()).isEqualTo("dock split(row; 0.75 0.125 0.125; tabs(1; scene game) "
            + "tabs(0; console) tabs(0; inspector))\n");
        // splitters of a column move with UP and DOWN
        r.dock.dock(r.hierarchy, r.scene, DockPosition.TOP);
        r.f.run().run();
        Element[] splitters = r.dock.splittersBeside(r.scene);
        assertThat(splitters).hasSize(1);
        float before = r.f.h(r.dock.paneOf(r.hierarchy));
        r.f.ui.interaction().focus(splitters[0]);
        r.f.run().command(UiCommand.DOWN).run();
        assertThat(r.f.h(r.dock.paneOf(r.hierarchy))).isCloseTo(before + WindowManager.KEY_STEP.get(), within(1f));
        r.f.command(UiCommand.UP).run();
        assertThat(r.f.h(r.dock.paneOf(r.hierarchy))).isCloseTo(before, within(1f));
        r.f.command(UiCommand.LEFT).run(); // across a column's splitter: nothing
        assertThat(r.f.h(r.dock.paneOf(r.hierarchy))).isCloseTo(before, within(1f));
        r.f.drag(r.f.cx(splitters[0]), r.f.cy(splitters[0]), r.f.cx(splitters[0]), r.f.cy(splitters[0]) + 40);
        r.f.run();
        assertThat(r.f.h(r.dock.paneOf(r.hierarchy))).isCloseTo(before + 40, within(1f));
        // a loose panel has no pane, splitters or ratios
        DockPanel loose = r.panel("loose", "Loose");
        assertThat(r.dock.paneOf(loose)).isNull();
        assertThat(r.dock.stripOf(loose)).isNull();
        assertThat(r.dock.splittersBeside(loose)).isEmpty();
        assertThat(r.dock.ratiosBeside(loose)).isEmpty();
        assertThat(r.dock.panelCount()).isEqualTo(6);
        assertThat(loose.toString()).contains("closed");
        r.dock.close(loose); // not open: nothing
        assertThat(loose.isActive()).isFalse();
        r.dock.closeName("Schließen");
        DockPanel german = r.panel("german", "Deutsch");
        assertThat(r.f.ui.store().label(german.closeButton().slot())).isEqualTo("Schließen");
        r.dock.host(null);
        assertThat(r.dock.host()).isNull();
        assertThatThrownBy(() -> r.dock.undock(r.scene)).isInstanceOf(IllegalStateException.class);
        DockPanel ghost = r.panel("ghost", "Ghost");
        r.dock.restore(DockLayout.parse("dock split(row; 0 0; tabs(0; ghost) tabs(0; scene))"));
        assertThat(r.dock.layout().toText()).isEqualTo("dock split(row; 0.5 0.5; tabs(0; ghost) tabs(0; scene))\n");
        assertThat(ghost.isDocked()).isTrue();
        r.dock.restore(DockLayout.parse("dock split(row; 1 1; tabs(0; nobody) tabs(0; noone))"));
        assertThat(r.dock.layout().toText()).isEqualTo("dock none\n");
    }

    @Test
    void windowsSnapToTheFarEdgesAndOtherWindowsWhenMovedAndResized() {
        Fx f = new Fx();
        WindowManager windows = new WindowManager(f.overlays);
        windows.buttonNames("Min", "Max", "Shut");
        FloatingWindow a = WindowTest.window(f, windows, "a", 100, 100, 300, 200).open();
        FloatingWindow b = WindowTest.window(f, windows, "b", 600, 100, 200, 200).open();
        f.run();
        assertThat(f.ui.store().label(a.closeButton().slot())).isEqualTo("Shut");
        // right and bottom edges of the screen
        f.drag(WindowTest.titleX(f, b), WindowTest.titleY(f, b), WindowTest.titleX(f, b) + 474,
            WindowTest.titleY(f, b) + 416);
        assertThat(b.x()).isEqualTo(1080);
        assertThat(b.y()).isEqualTo(520);
        // a's right edge resized near b's left edge snaps to it; its bottom near the screen's bottom too
        Element e = a.handle("e");
        f.drag(f.cx(e), f.cy(e), f.cx(e) + 676, f.cy(e));
        assertThat(a.x() + a.width()).isEqualTo(1080);
        Element s = a.handle("s");
        f.drag(f.cx(s), f.cy(s), f.cx(s), f.cy(s) + 416);
        assertThat(a.y() + a.height()).isEqualTo(720);
        Element w = a.handle("w");
        f.drag(f.cx(w), f.cy(w), f.cx(w) - 96, f.cy(w));
        assertThat(a.x()).isEqualTo(0);
        // a minimised window snaps by its title bar's height
        b.minimise();
        f.run();
        FloatingWindow c = WindowTest.window(f, windows, "c", 300, 300, 200, 100).open();
        f.run();
        float bottomOfB = b.y() + f.h(b.root());
        f.drag(WindowTest.titleX(f, c), WindowTest.titleY(f, c), WindowTest.titleX(f, c) + 780 - 3,
            WindowTest.titleY(f, c) + bottomOfB - 300 + 2);
        assertThat(c.y()).isEqualTo(bottomOfB);
        // snapping off: nothing snaps
        WindowManager.SNAP_DISTANCE.set(0);
        try {
            f.drag(WindowTest.titleX(f, c), WindowTest.titleY(f, c), WindowTest.titleX(f, c) + 20,
                WindowTest.titleY(f, c) + 3);
            assertThat(c.y()).isEqualTo(bottomOfB + 3);
            Element ce = c.handle("e");
            f.drag(f.cx(ce), f.cy(ce), f.cx(ce) + 7, f.cy(ce));
            assertThat(c.width()).isEqualTo(207);
        } finally {
            WindowManager.SNAP_DISTANCE.set(WindowManager.SNAP_DISTANCE.initial());
        }
        // a window whose drag starts on one of its title bar buttons does not move
        float bx = c.x();
        f.drag(f.cx(c.closeButton()), f.cy(c.closeButton()), f.cx(c.closeButton()) - 80, f.cy(c.closeButton()));
        assertThat(c.x()).isEqualTo(bx);
        // icons, titles, a modal window raised and destroyed
        a.icon("caption").icon("title");
        assertThat(a.icon().style()).isEqualTo("title");
        assertThat(f.ui.store().firstChild(a.titleBar().slot())).isEqualTo(a.icon().slot());
        a.title("Renamed");
        assertThat(a.title()).isEqualTo("Renamed");
        assertThat(a.titleLabel()).isNotNull();
        FloatingWindow m = WindowTest.window(f, windows, "m", 10, 10, 200, 100).modal(true).open();
        f.run();
        m.open(); // again: raised, the backdrop with it
        assertThat(f.ui.store().lastChild(m.host().slot())).isEqualTo(m.root().slot());
        windows.destroy(m);
        assertThat(m.backdrop().alive()).isFalse();
        f.run();
        assertThat(windows.overlays()).isSameAs(f.overlays);
        windows.restore(new WindowLayout(java.util.List.of(new WindowLayout.State("a", 5, 6, 300, 200,
            WindowMode.NORMAL, false), new WindowLayout.State("later", 1, 2, 300, 200, WindowMode.MAXIMISED, true))));
        assertThat(a.isOpen()).isFalse();
        assertThat(windows.layout().find("later")).isNotNull();
        assertThat(windows.create("later", "Later").mode()).isEqualTo(WindowMode.MAXIMISED);
        a.listener(null);
        a.close();
        windows.persistence(null, "none");
    }

    @Test
    void tooltipsFollowTheirTargetsTextAndGoWhenTheirPointerLeavesOrAnotherPresses() {
        Fx f = new Fx();
        Tooltips tips = new Tooltips(f.overlays);
        Element a = f.box(100, 100, 100, 30);
        Element rich = f.ui.panel(null);
        tips.content(a, rich, "first");
        tips.text(a, "now text");
        f.run().mouse(150, 115).idle(Interaction.TOOLTIP_DELAY.get() + 0.05);
        assertThat(tips.showingFor()).isEqualTo(a);
        tips.content(a, rich, "rich again"); // changed while showing: shown at once
        assertThat(rich.parent()).isEqualTo(tips.element());
        f.in.pointer(9, Pointer.Kind.MOUSE, 900, 600, Pointer.PRIMARY); // another pointer presses elsewhere
        f.run();
        assertThat(tips.showing()).isFalse();
        f.in.lift(9);
        f.run().mouse(700, 600).mouse(150, 115).idle(Interaction.TOOLTIP_DELAY.get() + 0.05);
        assertThat(tips.showing()).isTrue();
        f.in.lift(0); // the mouse goes away
        f.run();
        assertThat(tips.showing()).isFalse();
        tips.remove(a);
        tips.show(a); // no tooltip: nothing
        assertThat(tips.showing()).isFalse();
        Fx g = new Fx(SIDES, false);
        Tooltips none = new Tooltips(g.overlays);
        Element b = g.box(10, 10, 10, 10);
        assertThatThrownBy(() -> none.text(b, "x")).isInstanceOf(IllegalStateException.class);
    }
}
