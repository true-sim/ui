package com.truesim.ui.controls.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.input.Cursor;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.testkit.SyntheticInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Floating windows: move, snap, resize, raise, modes, modality, keyboard, remembered layouts, hosts. */
class WindowTest {

    static FloatingWindow window(Fx f, WindowManager windows, String id, float x, float y, float w, float h) {
        FloatingWindow win = windows.create(id, id.toUpperCase()).bounds(x, y, w, h);
        win.content().add(f.ui.label(null, "content of " + id));
        return win;
    }

    /** A point on the title bar away from its buttons. */
    static float titleX(Fx f, FloatingWindow w) {
        return f.x(w.titleBar()) + 20;
    }

    static float titleY(Fx f, FloatingWindow w) {
        return f.cy(w.titleBar());
    }

    @Test
    void itOpensWhereItIsPutMovesByItsTitleBarAndSnapsToTheScreenAndOtherWindows() {
        Fx f = new Fx();
        WindowManager windows = new WindowManager(f.overlays);
        FloatingWindow a = window(f, windows, "a", 100, 100, 300, 200).open();
        f.run();
        assertThat(f.x(a.root())).isEqualTo(100);
        assertThat(f.y(a.root())).isEqualTo(100);
        assertThat(f.w(a.root())).isEqualTo(300);
        assertThat(f.h(a.root())).isEqualTo(200);
        assertThat(f.w(a.frame())).isEqualTo(300);
        assertThat(f.y(a.titleBar())).isEqualTo(101); // inside the frame's 1 px border
        assertThat(a.root().parent()).isEqualTo(windows.host());
        assertThat(windows.host().parent()).isEqualTo(f.ui.layer(Layers.OVERLAY).root());
        assertThat(a.active()).isTrue();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(a.root());
        f.drag(titleX(f, a), titleY(f, a), titleX(f, a) + 50, titleY(f, a) + 30);
        assertThat(a.x()).isEqualTo(150);
        assertThat(a.y()).isEqualTo(130);
        assertThat(f.x(a.root())).isEqualTo(150);
        // near the left edge: snaps to it
        f.drag(titleX(f, a), titleY(f, a), titleX(f, a) - 144, titleY(f, a));
        assertThat(a.x()).isEqualTo(0);
        // near another window's left edge: abuts it
        FloatingWindow b = window(f, windows, "b", 600, 130, 200, 150).open();
        f.run();
        f.drag(titleX(f, a), titleY(f, a), titleX(f, a) + 296, titleY(f, a) + 3);
        assertThat(a.x()).isEqualTo(300); // its right edge on b's left edge (600)
        assertThat(a.y()).isEqualTo(130); // and its top on b's top
        // dragged far off the screen it keeps some of itself on it
        f.drag(titleX(f, a), titleY(f, a), -900, -500);
        assertThat(a.y()).isEqualTo(0);
        assertThat(a.x()).isEqualTo(WindowManager.KEEP_VISIBLE.get() - 300);
        assertThat(b.isOpen()).isTrue();
    }

    @Test
    void edgesAndCornersResizeItWithinItsMinimumWithTheirCursors() {
        Fx f = new Fx();
        WindowManager windows = new WindowManager(f.overlays);
        FloatingWindow a = window(f, windows, "a", 100, 100, 300, 200).open();
        f.run();
        Element se = a.handle("se");
        f.mouse(f.cx(se), f.cy(se));
        assertThat(f.ui.interaction().cursor(SyntheticInput.MOUSE)).isEqualTo(Cursor.RESIZE_NWSE);
        f.drag(f.cx(se), f.cy(se), f.cx(se) + 40, f.cy(se) + 20);
        assertThat(a.width()).isEqualTo(340);
        assertThat(a.height()).isEqualTo(220);
        assertThat(f.w(a.root())).isEqualTo(340);
        Element w = a.handle("w");
        f.mouse(f.cx(w), f.cy(w));
        assertThat(f.ui.interaction().cursor(SyntheticInput.MOUSE)).isEqualTo(Cursor.RESIZE_EW);
        f.drag(f.cx(w), f.cy(w), f.cx(w) + 300, f.cy(w));
        assertThat(a.width()).isEqualTo(160); // the theme's min-width
        assertThat(a.x()).isEqualTo(280);
        Element n = a.handle("n");
        f.drag(f.cx(n), f.cy(n), f.cx(n), f.cy(n) - 50);
        assertThat(a.y()).isEqualTo(50);
        assertThat(a.height()).isEqualTo(270);
        f.mouse(titleX(f, a), titleY(f, a));
        assertThat(f.ui.interaction().cursor(SyntheticInput.MOUSE)).isEqualTo(Cursor.MOVE);
        a.resizable(false);
        f.run();
        assertThat(f.ui.layout().shown(se.slot())).isFalse();
        assertThatThrownBy(() -> a.handle("x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPressRaisesAndActivatesAWindowAndFocusCyclesThroughThem() {
        Fx f = new Fx();
        WindowManager windows = new WindowManager(f.overlays);
        FloatingWindow a = window(f, windows, "a", 100, 100, 300, 200).open();
        FloatingWindow b = window(f, windows, "b", 200, 150, 300, 200).open();
        f.run();
        UiStore store = f.ui.store();
        assertThat(store.lastChild(windows.host().slot())).isEqualTo(b.root().slot());
        assertThat(windows.active()).isSameAs(b);
        f.click(120, 250); // a's content, below b's left edge
        assertThat(store.lastChild(windows.host().slot())).isEqualTo(a.root().slot());
        assertThat(windows.active()).isSameAs(a);
        assertThat(a.root().is(com.truesim.ui.core.UiState.SELECTED)).isTrue();
        assertThat(b.root().is(com.truesim.ui.core.UiState.SELECTED)).isFalse();
        windows.focusNext();
        assertThat(windows.active()).isSameAs(b);
        assertThat(f.ui.interaction().focused(0)).isEqualTo(b.root());
        windows.focusNext();
        assertThat(windows.active()).isSameAs(a);
        assertThat(windows.openCount()).isEqualTo(2);
        assertThat(windows.openAt(1)).isSameAs(a);
        b.close();
        assertThat(windows.active()).isSameAs(a);
        a.close();
        windows.focusNext();
        assertThat(windows.active()).isNull();
    }

    @Test
    void itMinimisesToItsTitleBarMaximisesToTheScreenAndRestores() {
        Fx f = new Fx();
        WindowManager windows = new WindowManager(f.overlays);
        FloatingWindow a = window(f, windows, "a", 100, 100, 300, 200).open();
        f.run();
        f.click(f.cx(a.minimiseButton()), f.cy(a.minimiseButton())).run();
        assertThat(a.mode()).isEqualTo(WindowMode.MINIMISED);
        assertThat(f.ui.layout().shown(a.content().slot())).isFalse();
        assertThat(f.h(a.root())).isEqualTo(f.h(a.titleBar()) + 2); // the title bar and the frame's border
        f.click(f.cx(a.minimiseButton()), f.cy(a.minimiseButton())).run();
        assertThat(a.mode()).isEqualTo(WindowMode.NORMAL);
        assertThat(f.h(a.root())).isEqualTo(200);
        f.click(titleX(f, a), titleY(f, a)).click(titleX(f, a), titleY(f, a)).run(); // a double click
        assertThat(a.mode()).isEqualTo(WindowMode.MAXIMISED);
        assertThat(f.x(a.root())).isEqualTo(0);
        assertThat(f.w(a.root())).isEqualTo(1280);
        assertThat(f.h(a.root())).isEqualTo(720);
        assertThat(a.maximiseButton().is(com.truesim.ui.core.UiState.CHECKED)).isTrue();
        // dragging a maximised window by its title restores it under the pointer
        float px = 640;
        f.drag(px, titleY(f, a), px + 10, titleY(f, a) + 100);
        assertThat(a.mode()).isEqualTo(WindowMode.NORMAL);
        assertThat(a.width()).isEqualTo(300);
        assertThat(a.x()).isEqualTo(650 - 150); // the pointer at the same share of its width: the middle
        f.click(f.cx(a.maximiseButton()), f.cy(a.maximiseButton())).run();
        assertThat(a.mode()).isEqualTo(WindowMode.MAXIMISED);
        a.restore();
        f.run();
        assertThat(f.w(a.root())).isEqualTo(300);
        a.maximise();
        a.maximise();
        assertThat(a.mode()).isEqualTo(WindowMode.NORMAL);
    }

    @Test
    void theCloseButtonClosesItUnlessItsListenerKeepsIt() {
        Fx f = new Fx();
        WindowManager windows = new WindowManager(f.overlays);
        FloatingWindow a = window(f, windows, "a", 100, 100, 300, 200).open();
        List<String> said = new ArrayList<>();
        boolean[] allow = {false};
        a.listener(new FloatingWindow.Listener() {
            @Override
            public boolean closing(FloatingWindow window) {
                said.add("closing");
                return allow[0];
            }

            @Override
            public void closed(FloatingWindow window) {
                said.add("closed");
            }

            @Override
            public void changed(FloatingWindow window) {
                said.add("changed");
            }
        });
        f.run();
        f.click(f.cx(a.closeButton()), f.cy(a.closeButton()));
        assertThat(a.isOpen()).isTrue();
        allow[0] = true;
        f.click(f.cx(a.closeButton()), f.cy(a.closeButton()));
        assertThat(a.isOpen()).isFalse();
        assertThat(a.root().parent()).isNull();
        assertThat(said).containsExactly("closing", "closing", "closed");
        a.open();
        f.run();
        f.drag(titleX(f, a), titleY(f, a), titleX(f, a) + 20, titleY(f, a));
        assertThat(said).endsWith("changed");
        a.closable(false).minimisable(false).maximisable(false);
        f.run();
        assertThat(f.ui.layout().shown(a.closeButton().slot())).isFalse();
        windows.destroy(a);
        assertThat(a.root().alive()).isFalse();
        assertThat(windows.count()).isZero();
    }

    @Test
    void aModalWindowDimsAndBlocksTheRestTrapsFocusAndClosesOnCancel() {
        Fx f = new Fx();
        WindowManager windows = new WindowManager(f.overlays);
        Element hud = f.box(10, 10, 100, 40);
        List<String> clicks = new ArrayList<>();
        f.ui.on(hud, EventType.CLICK, e -> clicks.add("hud"));
        FloatingWindow plain = window(f, windows, "plain", 400, 300, 300, 200).open();
        FloatingWindow m = window(f, windows, "m", 0, 0, 300, 160).modal(true);
        m.placedAt(0, 0);
        m.bounds(0, 0, 300, 160);
        f.run();
        m.open();
        f.run().run();
        assertThat(m.root().parent().parent()).isEqualTo(f.ui.layer(Layers.MODAL).root());
        assertThat(f.w(m.backdrop())).isEqualTo(1280);
        assertThat(f.ui.interaction().focused(0)).isEqualTo(m.root());
        f.click(50, 30);
        f.click(450, 350);
        assertThat(clicks).isEmpty(); // the HUD and the other window are blocked
        assertThat(windows.active()).isSameAs(m);
        assertThat(Semantics.of(f.ui.store(), f.ui.interaction()).toString()).contains("DIALOG \"M\"");
        f.command(UiCommand.CANCEL);
        assertThat(m.isOpen()).isFalse();
        assertThat(m.backdrop().parent()).isNull();
        f.run();
        f.click(50, 30);
        assertThat(clicks).containsExactly("hud");
        assertThat(plain.modal()).isFalse();
        assertThatThrownBy(() -> plain.modal(true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theKeysOrTheDpadMoveAndResizeItAndCancelPutsItBack() {
        Fx f = new Fx();
        WindowManager windows = new WindowManager(f.overlays);
        FloatingWindow a = window(f, windows, "a", 100, 100, 300, 200).open();
        f.run();
        a.keyboardMove();
        assertThat(a.keyboardActive()).isTrue();
        f.command(UiCommand.RIGHT, UiCommand.RIGHT, UiCommand.DOWN);
        float step = WindowManager.KEY_STEP.get();
        assertThat(a.x()).isEqualTo(100 + 2 * step);
        assertThat(a.y()).isEqualTo(100 + step);
        assertThat(f.x(a.root())).isEqualTo(100 + 2 * step);
        f.command(UiCommand.ACCEPT);
        assertThat(a.keyboardActive()).isFalse();
        f.command(UiCommand.RIGHT); // no longer moving: a navigation command again
        assertThat(a.x()).isEqualTo(100 + 2 * step);
        a.keyboardResize();
        f.command(UiCommand.RIGHT, UiCommand.UP);
        assertThat(a.width()).isEqualTo(300 + step);
        assertThat(a.height()).isEqualTo(200 - step);
        f.command(UiCommand.CANCEL);
        assertThat(a.width()).isEqualTo(300);
        assertThat(a.height()).isEqualTo(200);
        assertThat(a.isOpen()).isTrue(); // CANCEL ended the resize, it did not close the window
        a.maximise();
        a.keyboardMove(); // from a maximised window: restored first
        assertThat(a.mode()).isEqualTo(WindowMode.NORMAL);
        f.command(UiCommand.LEFT, UiCommand.SUBMIT);
        assertThat(a.x()).isEqualTo(100 + step);
    }

    @Test
    void theLayoutIsRememberedAsTextAndRestoredForWindowsMadeLater() {
        Fx f = new Fx();
        WindowManager windows = new WindowManager(f.overlays);
        LayoutStore store = LayoutStore.inMemory();
        windows.persistence(store, "windows");
        FloatingWindow a = window(f, windows, "a", 100, 100, 300, 200).open();
        FloatingWindow b = window(f, windows, "b", 500, 120, 240, 180);
        f.run();
        f.drag(titleX(f, a), titleY(f, a), titleX(f, a) + 33, titleY(f, a) + 17);
        a.minimise();
        a.changed();
        String text = store.load("windows");
        assertThat(text).isEqualTo("window a 133 117 300 200 minimised open\nwindow b 500 120 240 180 normal closed\n");
        assertThat(WindowLayout.parse(text)).isEqualTo(windows.layout());
        assertThat(WindowLayout.parse(text).toText()).isEqualTo(text);
        // a new session: the remembered places apply as the windows are made
        Fx g = new Fx();
        WindowManager again = new WindowManager(g.overlays);
        again.persistence(store, "windows");
        FloatingWindow a2 = again.create("a", "A");
        assertThat(a2.x()).isEqualTo(133);
        assertThat(a2.mode()).isEqualTo(WindowMode.MINIMISED);
        again.restore(WindowLayout.parse(text));
        assertThat(a2.isOpen()).isTrue();
        again.restore(WindowLayout.parse("window a 1.5 2 300 200 normal closed # a comment\n\n"));
        assertThat(a2.isOpen()).isFalse();
        assertThat(a2.x()).isEqualTo(1.5f);
        assertThat(again.layout().toText()).contains("window a 1.5 2 300 200 normal closed");
        assertThatThrownBy(() -> WindowLayout.parse("window a 1 2 3\n")).hasMessageContaining("line 1");
        assertThatThrownBy(() -> WindowLayout.parse("window a 1 2 3 4 normal maybe")).hasMessageContaining("open");
        assertThatThrownBy(() -> WindowLayout.parse("window a 1 2 3 4 big open")).hasMessageContaining("big");
        assertThatThrownBy(() -> again.create("has space", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> again.create("a", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(WindowLayout.EMPTY.find("a")).isNull();
        assertThat(b.isOpen()).isFalse();
    }

    @Test
    void windowsLiveInAnyHostAWorldPanelsRootOrAPaneNotTheScreen() {
        Fx f = new Fx();
        Element panel = f.box(200, 100, 600, 400); // a card: 1 px border and 8 x 12 px padding
        WindowManager windows = new WindowManager(f.overlays, panel);
        FloatingWindow a = window(f, windows, "a", 0, 0, 200, 120).open();
        f.run();
        float left = 200 + 1 + 12;
        float top = 100 + 1 + 8;
        assertThat(f.x(a.root())).isEqualTo(left);
        assertThat(f.y(a.root())).isEqualTo(top);
        f.drag(titleX(f, a), titleY(f, a), titleX(f, a) + 1000, titleY(f, a));
        assertThat(a.x()).isEqualTo(600 - 2 - 24 - WindowManager.KEEP_VISIBLE.get()); // kept within the panel
        a.maximise();
        f.run();
        assertThat(f.w(a.root())).isEqualTo(600 - 2 - 24);
        FloatingWindow m = window(f, windows, "m", 10, 10, 100, 100).modal(true).open();
        assertThat(m.host()).isEqualTo(panel); // a modal window in a panel stays in the panel
        // an unplaced window opens centred
        FloatingWindow c = windows.create("c", "C");
        c.open();
        f.run().run();
        assertThat(c.x()).isEqualTo((600 - 2 - 24 - 320) / 2f);
        assertThat(Semantics.unnamed(Semantics.of(f.ui.store(), f.ui.interaction()))).isEmpty();
        assertThat(WindowManager.tunables()).contains(WindowManager.SNAP_DISTANCE);
        assertThat(windows.find("zzz")).isNull();
        assertThat(windows.at(0)).isSameAs(a);
        assertThat(a.toString()).contains("a open");
    }
}
