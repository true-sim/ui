package com.truesim.ui.controls.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.ui.controls.overlay.Overlay;
import com.truesim.ui.controls.overlay.PointAnchor;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import org.junit.jupiter.api.Test;

/**
 * Docking into OS windows through fake ones ({@link FakeOsWindows}): a tab dragged beyond the window tears out into
 * an OS window that follows the pointer; its tab dragged over a dock space of any window docks it; closing a window
 * re-docks its panels where they were; panels move between windows; layouts with windows on monitors round trip.
 */
class OsDockTest {

    /** The editor layout in a 1000 x 600 main window at 100, 100 on the screen, docking into fake OS windows. */
    static final class Rig {
        final Fx f = new Fx();
        final FakeOsWindows os = new FakeOsWindows(1000, 600);
        final DockSpace dock = new DockSpace(f.overlays, null);
        final OsDockHost host = new OsDockHost(f.overlays, dock, os);
        final DockPanel scene = panel("scene", "Scene");
        final DockPanel hierarchy = panel("hierarchy", "Hierarchy");
        final DockPanel inspector = panel("inspector", "Inspector");
        final DockPanel console = panel("console", "Console");
        final DockPanel game = panel("game", "Game");
        final float[] at = new float[2];

        Rig() {
            f.width = 1000;
            f.height = 600;
            f.pin(dock.root(), 0, 0, 1000, 600);
            dock.dock(scene, DockPosition.CENTER);
            dock.dock(hierarchy, DockPosition.LEFT);
            dock.dock(inspector, DockPosition.RIGHT);
            dock.dock(console, scene, DockPosition.BOTTOM);
            dock.dock(game, scene, DockPosition.CENTER);
            f.run().run();
        }

        DockPanel panel(String id, String title) {
            DockPanel p = dock.panel(id, title);
            p.content().add(f.ui.label(null, title + " content"));
            return p;
        }

        /** The mouse at screen point {@code sx, sy}: mapped into the UI as the engine maps it, one frame. */
        Rig screen(float sx, float sy, int buttons) {
            os.cursorX = sx;
            os.cursorY = sy;
            host.toUi(sx, sy, at);
            f.mouse(at[0], at[1], buttons);
            return this;
        }

        /** The screen point of UI point {@code x, y}. */
        float[] onScreen(float x, float y) {
            float[] out = new float[2];
            host.toScreen(x, y, out);
            return out;
        }

        /** Presses {@code e}'s middle and drags (holding) in steps to screen point {@code sx, sy}. */
        Rig dragTo(Element e, float sx, float sy) {
            float[] from = onScreen(f.cx(e), f.cy(e));
            screen(from[0], from[1], Pointer.PRIMARY);
            for (int i = 1; i <= 4; i++) {
                screen(from[0] + (sx - from[0]) * i / 4, from[1] + (sy - from[1]) * i / 4, Pointer.PRIMARY);
            }
            return this;
        }

        Rig release() {
            return screen(os.cursorX, os.cursorY, 0);
        }

        OsDockHost.Window only() {
            assertThat(host.windowCount()).isEqualTo(1);
            for (int i = 0; i < host.slotCount(); i++) {
                if (host.windowAt(i) != null) {
                    return host.windowAt(i);
                }
            }
            throw new AssertionError();
        }

        /** Tears {@code p} out to screen point {@code sx, sy} and lets go there. */
        OsDockHost.Window tearOut(DockPanel p, float sx, float sy) {
            dragTo(p.tab(), sx, sy).release();
            f.run();
            OsDockHost.Window w = host.window(dock.areaOf(p));
            assertThat(w).isNotNull();
            return w;
        }
    }

    static final String EDITOR = "dock split(row; 0.1875 0.5625 0.25; tabs(0; hierarchy) split(column; 0.5 0.5; "
        + "tabs(1; scene game) tabs(0; console)) tabs(0; inspector))\n";

    @Test
    void aTabDraggedBeyondTheWindowTearsOutIntoAnOsWindowThatFollowsThePointerUntilReleased() {
        Rig r = new Rig();
        assertThat(r.dock.layout().toText()).isEqualTo(EDITOR);
        assertThat(r.dock.host()).isSameAs(r.host);
        float[] tab = r.onScreen(r.f.cx(r.console.tab()), r.f.cy(r.console.tab()));
        assertThat(tab[0]).isEqualTo(100 + r.f.cx(r.console.tab()));
        r.dragTo(r.console.tab(), 1300, 500); // right of the main window, which ends at 1100
        assertThat(r.os.open()).hasSize(1);
        FakeOsWindows.Win win = r.os.open().get(0);
        assertThat(win.title).isEqualTo("Console");
        OsDockHost.Window w = r.only();
        assertThat(w.area().panels()).containsExactly(r.console);
        assertThat(r.dock.areaOf(r.console)).isSameAs(w.area());
        assertThat(r.host.followingWindow()).isSameAs(w);
        assertThat(r.dock.areaCount()).isEqualTo(2);
        assertThat(r.dock.mainArea().panels()).doesNotContain(r.console);
        // the cursor keeps holding the tab where it held it
        int grabX = 1300 - win.x;
        int grabY = 500 - win.y;
        assertThat(grabX).isBetween(0, win.w);
        assertThat(grabY).isBetween(0, 40);
        r.screen(1400, 650, Pointer.PRIMARY).f.run();
        assertThat(win.x).isEqualTo(1400 - grabX);
        assertThat(win.y).isEqualTo(650 - grabY);
        assertThat(r.dock.previewShowing()).isFalse();
        r.release();
        assertThat(r.host.followingWindow()).isNull();
        assertThat(r.os.open()).hasSize(1);
        // its region of the UI: right of the main window's, the window's size
        r.f.run();
        assertThat(w.originX()).isEqualTo(OsDockHost.ORIGIN_X);
        assertThat(w.width()).isEqualTo(win.w);
        assertThat(r.f.x(w.root())).isEqualTo(OsDockHost.ORIGIN_X);
        assertThat(r.f.w(w.root())).isEqualTo(win.w);
        assertThat(r.f.x(r.dock.paneOf(r.console))).isGreaterThanOrEqualTo(OsDockHost.ORIGIN_X);
        assertThat(r.dock.layout().toText()).contains("window w1 \"Primary\" " + win.x + " " + win.y + " " + win.w
            + " " + win.h + " tabs(0; console)");
        assertThat(r.dock.toString()).contains("1 windows");
        assertThat(r.host.toString()).contains("1 windows");
        assertThat(Semantics.unnamed(Semantics.of(r.f.ui.store(), r.f.ui.interaction()))).isEmpty();
    }

    @Test
    void theTabOfAWindowDraggedOverADockSpaceDocksItAndTheWindowCloses() {
        Rig r = new Rig();
        OsDockHost.Window w = r.tearOut(r.console, 1300, 500);
        int id = w.id();
        // its only tab drags the window along; over the inspector's pane it docks there
        Element inspector = r.dock.paneOf(r.inspector);
        float[] target = r.onScreen(r.f.cx(inspector), r.f.cy(inspector) + 40);
        r.dragTo(r.console.tab(), target[0], target[1]);
        assertThat(r.host.followingWindow()).isSameAs(w);
        assertThat(r.dock.previewShowing()).isTrue();
        FakeOsWindows.Win win = r.os.win(id);
        assertThat(win.x).isLessThan(1300); // it moved with the pointer
        r.release();
        assertThat(r.os.win(id).closed).isTrue();
        assertThat(r.host.windowCount()).isZero();
        assertThat(r.dock.areaCount()).isEqualTo(1);
        assertThat(r.dock.pane(r.dock.paneCount() - 1)).containsExactly(r.inspector, r.console);
        assertThat(r.dock.layout().toText()).doesNotContain("window");
    }

    @Test
    void closingAWindowDocksItsPanelsAgainWhereTheyWereInTheMainArea() {
        Rig r = new Rig();
        OsDockHost.Window w = r.tearOut(r.console, 1300, 500);
        r.tearOut(r.game, 1300, 200);
        assertThat(r.host.windowCount()).isEqualTo(2);
        r.host.closeRequested(w.id());
        r.f.run();
        assertThat(r.dock.layout().toText()).startsWith("dock split(row; 0.1875 0.5625 0.25; tabs(0; hierarchy) "
            + "split(column; 0.5 0.5; tabs(0; scene) tabs(0; console))");
        OsDockHost.Window g = r.host.window(r.dock.areaOf(r.game));
        r.host.closeRequested(g.id());
        r.f.run();
        assertThat(r.dock.layout().toText()).isEqualTo(EDITOR);
        assertThat(r.os.open()).isEmpty();
        assertThat(r.game.isActive()).isTrue();
        // a window that went away by itself (its display, the OS) docks its panels again too
        OsDockHost.Window again = r.tearOut(r.inspector, 1500, 300);
        r.os.win(again.id()).closed = true;
        r.f.run();
        assertThat(r.dock.layout().toText()).isEqualTo(EDITOR);
        r.host.closeRequested(12345); // none such: nothing happens
    }

    @Test
    void panelsMoveBetweenWindowsAndTearOutOfThem() {
        Rig r = new Rig();
        OsDockHost.Window a = r.tearOut(r.console, 1300, 500);
        OsDockHost.Window b = r.tearOut(r.game, 1300, 100);
        r.f.run();
        // game's window dragged by its tab over console's window's pane: game joins its tabs, its window closes
        Element pane = r.dock.paneOf(r.console);
        float[] target = r.onScreen(r.f.cx(pane), r.f.cy(pane));
        r.dragTo(r.game.tab(), target[0], target[1]).release();
        r.f.run();
        assertThat(r.os.win(b.id()).closed).isTrue();
        assertThat(a.area().panels()).containsExactly(r.console, r.game);
        // two tabs: dragging one beyond every window tears it out of this one
        FakeOsWindows.Win aw = r.os.win(a.id());
        r.dragTo(r.game.tab(), aw.x + aw.w + 300, aw.y + 50);
        assertThat(r.host.windowCount()).isEqualTo(2);
        assertThat(r.host.followingWindow()).isNotSameAs(a);
        r.release();
        assertThat(a.area().panels()).containsExactly(r.console);
        // a tab of the main area dropped on a window's pane edge splits it
        Element console = r.dock.paneOf(r.console);
        float[] edge = r.onScreen(r.f.x(console) + 4, r.f.cy(console) + 30);
        r.dragTo(r.hierarchy.tab(), edge[0], edge[1]).release();
        assertThat(r.dock.layout().toText()).contains("window " + a.area().id() + " ")
            .contains("split(row; 0.5 0.5; tabs(0; hierarchy) tabs(0; console))");
        // dropping a window's tab onto the main area's outer edge
        r.dragTo(r.hierarchy.tab(), 100 + 3, 100 + 300).release();
        assertThat(r.dock.layout().toText()).startsWith("dock split(row; 0.25");
    }

    @Test
    void aTabDroppedOnTheMainWindowWhereNothingDocksOpensAWindowThere() {
        Rig r = new Rig();
        // the main area taken by a pinned panel over part of the window: dropped on it, nothing docks
        r.f.pin(r.dock.root(), 0, 0, 600, 600);
        r.f.run().run();
        r.dragTo(r.console.tab(), 100 + 900, 100 + 300).release();
        r.f.run();
        OsDockHost.Window w = r.host.window(r.dock.areaOf(r.console));
        assertThat(w).isNotNull();
        FakeOsWindows.Win win = r.os.win(w.id());
        assertThat(win.x).isLessThan(1000).isGreaterThan(800);
        // and undocked in code, it opens where its pane was
        float paneX = r.f.x(r.dock.paneOf(r.inspector));
        float paneW = r.f.w(r.dock.paneOf(r.inspector));
        r.dock.undock(r.inspector);
        OsDockHost.Window i = r.host.window(r.dock.areaOf(r.inspector));
        assertThat(r.os.win(i.id()).x).isEqualTo(100 + Math.round(paneX));
        assertThat(r.os.win(i.id()).w).isEqualTo(Math.round(paneW));
        assertThatThrownBy(() -> r.host.undock(r.game, 0, 0, 1, 1)).isInstanceOf(UnsupportedOperationException.class);
        r.host.release(r.game);
        r.host.raise(r.game);
        assertThat(r.host.bounds(r.game, new float[4])).isFalse();
    }

    @Test
    void layoutsWithWindowsOnMonitorsRoundTripAndRestoreOnMissingMonitorsInsideThePrimary() {
        String text = "dock split(row; 0.5 0.5; tabs(0; scene) tabs(0; game))\n"
            + "window w1 \"DELL U2720Q #2\" 120 80 640 480 maximized split(column; 0.5 0.5; tabs(0; console) "
            + "tabs(0; hierarchy))\n"
            + "window w2 \"\" 0 0 300 200 tabs(0; inspector)\n";
        DockLayout parsed = DockLayout.parse(text + "# a comment\n");
        assertThat(parsed.toText()).isEqualTo(text);
        assertThat(parsed.windows()).hasSize(2);
        assertThat(parsed.windows().get(0).monitor()).isEqualTo("DELL U2720Q #2");
        assertThat(parsed.windows().get(0).maximized()).isTrue();
        assertThat(parsed.panels()).contains("console", "hierarchy", "inspector");
        assertThatThrownBy(() -> DockLayout.parse("window w1 \"x 0 0 1 1 tabs(0; a)"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closing");
        assertThatThrownBy(() -> DockLayout.parse("window w1 \"x\" 0 0 1 1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockLayout.Window("w", "a\"b", 0, 0, 1, 1, false, parsed.root()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockLayout.Window("w", null, 0, 0, 1, 1, false, null))
            .isInstanceOf(IllegalArgumentException.class);

        Rig r = new Rig();
        r.os.monitors.add(new OsWindows.Monitor("DELL U2720Q #2", 1920, 0, 2560, 1440, 1920, 0, 2560, 1400, false));
        r.dock.restore(parsed);
        r.f.run();
        assertThat(r.host.windowCount()).isEqualTo(2);
        FakeOsWindows.Win w1 = r.os.win(r.host.window(r.dock.findArea("w1")).id());
        assertThat(w1.x).isEqualTo(1920 + 120);
        assertThat(w1.y).isEqualTo(80);
        assertThat(w1.maximized).isTrue();
        FakeOsWindows.Win w2 = r.os.win(r.host.window(r.dock.findArea("w2")).id());
        assertThat(w2.x).isZero();
        assertThat(r.dock.layout().toText()).isEqualTo(text.replace("\"\" 0 0", "\"Primary\" 0 0"));
        // the second monitor unplugged: on the primary one, inside its usable area
        Rig again = new Rig();
        again.dock.restore(parsed);
        FakeOsWindows.Win moved = again.os.win(again.host.window(again.dock.findArea("w1")).id());
        assertThat(moved.x + moved.w).isLessThanOrEqualTo(1920);
        assertThat(moved.y).isEqualTo(80);
        // a window too large for the monitor: as large as its usable area
        again.dock.restore(DockLayout.parse(
            "dock tabs(0; scene)\nwindow big \"Primary\" -50 900 5000 3000 tabs(0; game)\n"));
        FakeOsWindows.Win big = again.os.win(again.host.window(again.dock.findArea("big")).id());
        assertThat(big.x).isZero();
        assertThat(big.y).isZero();
        assertThat(big.w).isEqualTo(1920);
        assertThat(big.h).isEqualTo(1040);
        assertThat(again.os.open()).hasSize(1); // restoring closed the windows before
    }

    @Test
    void layoutsPersistThroughTheStoreOnceAMovedWindowKeepsStill() {
        Rig r = new Rig();
        LayoutStore store = LayoutStore.inMemory();
        r.dock.persistence(store, "dock");
        OsDockHost.Window w = r.tearOut(r.console, 1300, 500);
        r.f.frames(OsDockHost.SAVE_AFTER + 2);
        assertThat(store.load("dock")).contains("window w1 \"Primary\"");
        FakeOsWindows.Win win = r.os.win(w.id());
        win.x = 1500; // the user moved it by its title bar
        r.f.run();
        assertThat(store.load("dock")).doesNotContain(" 1500 ");
        r.f.frames(OsDockHost.SAVE_AFTER + 2);
        assertThat(store.load("dock")).contains(" 1500 ");
        win.maximized = true;
        r.dock.save();
        assertThat(store.load("dock")).contains(" maximized tabs(0; console)");
        // a new dock space restores it from the store, windows and all
        Rig other = new Rig();
        other.dock.persistence(store, "dock");
        assertThat(other.host.windowCount()).isEqualTo(1);
        assertThat(other.os.open().get(0).maximized).isTrue();
        assertThat(other.dock.areaOf(other.console).id()).isEqualTo("w1");
        // a window is not followed when maximised: dragging its tab restores it first
        other.f.run().run();
        other.dragTo(other.console.tab(), 600, 600);
        assertThat(other.os.open().get(0).maximized).isFalse();
        other.release();
    }

    @Test
    void windowsOnMonitorsOfOtherScalesShowTheirContentAtTheSameSizeInPoints() {
        Rig r = new Rig();
        OsDockHost.Window w = r.tearOut(r.console, 1300, 500);
        FakeOsWindows.Win win = r.os.win(w.id());
        win.contentScale = 2; // moved onto a 200 % monitor: the same UI region covers twice the pixels
        r.f.run();
        assertThat(w.uiPerScreen()).isEqualTo(0.5f);
        assertThat(w.width()).isEqualTo(win.w * 0.5f);
        float[] ui = new float[2];
        assertThat(r.host.toUi(win.x + 10, win.y + 20, ui)).isEqualTo(win.id);
        assertThat(ui[0]).isEqualTo(OsDockHost.ORIGIN_X + 5);
        assertThat(ui[1]).isEqualTo(10);
        float[] back = new float[2];
        r.host.toScreen(ui[0], ui[1], back);
        assertThat(back[0]).isEqualTo(win.x + 10);
        assertThat(back[1]).isEqualTo(win.y + 20);
        // the main window on a Retina display: two pixels a screen coordinate
        r.os.main().pixelRatio = 2;
        r.f.run();
        assertThat(r.host.mainUiPerScreen()).isEqualTo(2);
        assertThat(r.host.toUi(100 + 10, 100 + 10, ui)).isEqualTo(OsWindows.MAIN);
        assertThat(ui[0]).isEqualTo(20);
        // the front-most window under the cursor takes it: the main window raised over the other one
        r.os.main().pixelRatio = 1;
        win.contentScale = 1;
        win.x = 150;
        win.y = 150;
        r.f.run();
        assertThat(r.host.toUi(200, 200, ui)).isEqualTo(win.id);
        r.host.focused(OsWindows.MAIN);
        assertThat(r.host.toUi(200, 200, ui)).isEqualTo(OsWindows.MAIN);
        r.host.focused(win.id);
        assertThat(r.host.toUi(200, 200, ui)).isEqualTo(win.id);
        r.host.focused(999);
        assertThat(r.host.regionAt(10, 10)).isNull();
        assertThat(r.host.window(999)).isNull();
    }

    @Test
    void withoutAGlobalCursorTheFollowingWindowGoesWhereThePointerIs() {
        Rig r = new Rig();
        r.os.globalCursor = false; // Wayland
        r.dragTo(r.console.tab(), 1300, 500);
        OsDockHost.Window w = r.only();
        FakeOsWindows.Win win = r.os.win(w.id());
        int x = win.x;
        r.screen(1350, 520, Pointer.PRIMARY).f.run();
        assertThat(win.x).isEqualTo(x + 50);
        r.release();
    }

    @Test
    void overlaysAnchoredInAWindowStayInsideItsRegion() {
        Rig r = new Rig();
        OsDockHost.Window w = r.tearOut(r.console, 1300, 500);
        r.f.run();
        Element tip = r.f.ui.panel("card");
        r.f.ui.codeLayout(tip, com.truesim.ui.controls.Ui.CODE_WIDTH | com.truesim.ui.controls.Ui.CODE_HEIGHT);
        r.f.ui.layout().width(tip.slot(), 120, com.truesim.ui.layout.Unit.PX).height(tip.slot(), 60,
            com.truesim.ui.layout.Unit.PX);
        Overlay o = r.f.overlays.create(tip, Layers.POPUP)
            .anchor(new PointAnchor(w.originX() + w.width() - 10, w.originY() + 20));
        o.show();
        r.f.run().run();
        float x = r.f.x(tip);
        assertThat(x).isGreaterThanOrEqualTo(w.originX());
        assertThat(x + r.f.w(tip)).isLessThanOrEqualTo(w.originX() + w.width());
        float[] view = new float[4];
        assertThat(r.host.viewportAt(10, 10, view)).isFalse();
        assertThat(r.host.viewportAt(w.originX() + 1, 1, view)).isTrue();
        assertThat(view[2]).isEqualTo(w.width());
    }

    @Test
    void theTestThemeRestylesTheWindowsContent() {
        Rig r = new Rig();
        OsDockHost.Window w = r.tearOut(r.console, 1300, 500);
        r.f.run();
        ResolvedStyle def = r.f.ui.styles().styleOf(w.root().slot());
        r.f.ui.styles().sheet(Fx.garish());
        r.f.run();
        ResolvedStyle test = r.f.ui.styles().styleOf(w.root().slot());
        assertThat(test.get(Styles.BACKGROUND)).isNotEqualTo(def.get(Styles.BACKGROUND));
        assertThat(test.get(Styles.BORDER)).isNotEqualTo(def.get(Styles.BORDER));
        assertThat(test.get(Styles.PADDING)).isNotEqualTo(def.get(Styles.PADDING));
        assertThat(r.f.h(r.dock.areaOf(r.console).root())).isCloseTo(w.height() - 22, within(1f));
    }

    @Test
    void eachWindowMayHaveAPaperWhiteOfItsOwnWhichTheLayoutSavesBesideItsPlace() {
        Rig r = new Rig();
        LayoutStore store = LayoutStore.inMemory();
        r.dock.persistence(store, "dock");
        OsDockHost.Window a = r.tearOut(r.console, 1300, 500);
        OsDockHost.Window b = r.tearOut(r.game, 1300, 100);
        r.f.run();
        assertThat(a.paperWhite()).as("none of its own: it follows the global one").isNaN();
        r.host.paperWhite(a.id(), 320);
        r.host.paperWhite(OsWindows.MAIN, 160);
        assertThat(r.os.win(a.id()).paperWhite).isEqualTo(320);
        assertThat(r.os.win(b.id()).paperWhite).as("the other follows the global one").isNaN();
        assertThat(r.os.main().paperWhite).isEqualTo(160);
        assertThat(r.host.paperWhite(a.id())).isEqualTo(320);
        assertThat(r.host.paperWhite(b.id())).isNaN();
        assertThat(r.host.paperWhite(OsWindows.MAIN)).isEqualTo(160);
        assertThat(r.host.paperWhite(999)).isNaN();
        r.host.paperWhite(999, 100); // none such: nothing happens
        assertThatThrownBy(() -> r.host.paperWhite(a.id(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.host.paperWhite(a.id(), Float.POSITIVE_INFINITY))
            .isInstanceOf(IllegalArgumentException.class);
        // saved beside the window's place once it settled (a slider's drag saves once)
        r.f.frames(OsDockHost.SAVE_AFTER + 2);
        String saved = store.load("dock");
        assertThat(saved).contains(" paper-white 320 tabs(0; console)").contains("\nmain paper-white 160\n")
            .containsOnlyOnce("paper-white 320");
        assertThat(saved.lines().filter(l -> l.contains("tabs(0; game)")).findFirst().orElseThrow())
            .doesNotContain("paper-white");
        DockLayout parsed = DockLayout.parse(saved);
        assertThat(parsed.toText()).isEqualTo(saved);
        assertThat(parsed.mainPaperWhite()).isEqualTo(160);
        // a new dock space restores the windows at their paper whites; the main window gets its own
        Rig other = new Rig();
        other.dock.persistence(store, "dock");
        OsDockHost.Window restored = other.host.window(other.dock.areaOf(other.console));
        assertThat(restored.paperWhite()).isEqualTo(320);
        assertThat(other.os.win(restored.id()).paperWhite).isEqualTo(320);
        assertThat(other.host.window(other.dock.areaOf(other.game)).paperWhite()).isNaN();
        assertThat(other.os.main().paperWhite).isEqualTo(160);
        // cleared: it follows the global one again, and the layout says nothing of it
        other.host.paperWhite(restored.id(), Float.NaN);
        other.host.paperWhite(OsWindows.MAIN, Float.NaN);
        assertThat(other.os.win(restored.id()).paperWhite).isNaN();
        assertThat(other.os.main().paperWhite).isNaN();
        assertThat(other.dock.layout().toText()).doesNotContain("paper-white");
        // the format: a window's after its maximised mark, the main window's on a line of its own
        String text = "dock tabs(0; scene)\nwindow w1 \"\" 0 0 300 200 maximized paper-white 250.5 tabs(0; console)\n"
            + "main paper-white 80\n";
        assertThat(DockLayout.parse(text).toText()).isEqualTo(text);
        assertThat(DockLayout.parse(text).windows().get(0).paperWhite()).isEqualTo(250.5f);
        assertThatThrownBy(() -> DockLayout.parse("main paper-white -5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockLayout.parse("main brightness 5")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("main paper-white");
        assertThatThrownBy(() -> DockLayout.parse("main paper-white bright"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockLayout.parse("window w1 \"\" 0 0 1 1 paper-white 0 tabs(0; a)"))
            .isInstanceOf(IllegalArgumentException.class);
        // a host without OS windows has no main paper white
        assertThat(new DockSpace(r.f.overlays, null).layout().mainPaperWhite()).isNaN();
    }

    @Test
    void aWindowFollowingADragIsSeeThroughUntilDroppedWhereTheSystemCan() {
        Rig r = new Rig();
        r.dragTo(r.console.tab(), 1300, 500);
        OsDockHost.Window w = r.only();
        FakeOsWindows.Win win = r.os.win(w.id());
        assertThat(win.opacity).as("the default theme's drag-opacity").isEqualTo(0.6f);
        assertThat(w.opacity()).isEqualTo(0.6f);
        int calls = r.os.opacityCalls;
        r.screen(1350, 520, Pointer.PRIMARY).f.run();
        assertThat(r.os.opacityCalls).as("asked once, not every frame").isEqualTo(calls);
        r.release();
        assertThat(win.opacity).as("opaque once dropped").isEqualTo(1);
        // its only tab drags it again: see-through again, opaque when the drag is cancelled
        r.f.run();
        float[] tab = r.onScreen(r.f.cx(r.console.tab()), r.f.cy(r.console.tab()));
        r.dragTo(r.console.tab(), tab[0] + 200, tab[1] + 100);
        assertThat(r.host.followingWindow()).isSameAs(w);
        assertThat(win.opacity).isEqualTo(0.6f);
        for (int p = 0; p < com.truesim.ui.core.input.UiInput.MAX_POINTERS; p++) {
            r.f.ui.interaction().cancelDrag(p);
        }
        r.f.run();
        assertThat(r.host.followingWindow()).isNull();
        assertThat(win.opacity).isEqualTo(1);
        r.release();
        // the test theme's value
        Rig garish = new Rig();
        garish.f.ui.styles().sheet(Fx.garish());
        garish.f.run().run();
        garish.dragTo(garish.console.tab(), 1300, 500);
        assertThat(garish.os.win(garish.only().id()).opacity).isEqualTo(0.45f);
        garish.release();
        // a system that refuses (Wayland, SDL's offscreen driver): asked, it stays opaque, nothing breaks
        Rig refused = new Rig();
        refused.os.translucency = false;
        refused.dragTo(refused.console.tab(), 1300, 500);
        FakeOsWindows.Win opaque = refused.os.win(refused.only().id());
        assertThat(refused.os.opacityCalls).isEqualTo(1);
        assertThat(opaque.opacity).isEqualTo(1);
        refused.release();
        assertThat(refused.host.followingWindow()).isNull();
    }
}
